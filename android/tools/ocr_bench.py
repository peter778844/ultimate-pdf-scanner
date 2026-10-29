"""OCR experiment bench: runs Tesseract variants on the pipeline's exact OCR inputs
(scancore/build/eval/*/ocrin/*.png, written by SeriesEval) and compares them.

Accuracy proxy without ground truth: the number of recognised words (>= 3 letters) that are in
the Tesseract deu/eng dictionaries. A variant that reads more real words is better; confidence
alone can be fooled. Also reported: mean confidence, share with conf >= 90, non-words.

usage: python -I tools/ocr_bench.py <variant> [<variant> ...]   (variants: see VARIANTS)
"""
import concurrent.futures as cf
import csv
import glob
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import time

import cv2
import numpy as np

sys.stdout.reconfigure(encoding='utf-8')
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
INPUTS = sorted(glob.glob(os.path.join(ROOT, 'scancore', 'build', 'eval', '*', 'ocrin', '*.png')))
TESS = r'C:\Program Files\Tesseract-OCR\tesseract.exe'
TESSDATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'prototype', 'tessdata')
DICT_DIR = os.environ.get('OCR_DICT', '')
OUT = os.path.join(ROOT, 'scancore', 'build', 'ocrbench')
LANG = os.environ.get('OCR_LANG', 'deu+eng')

# dictionaries: Tesseract's own deu/eng lists, Hunspell de_DE_frami (GPL, handles compounds) and
# words of German Wikipedia titles (technical terms; only >= 6 letters, short ones are often names)
words_ok = set()
for name in ('deu.words.txt', 'eng.words.txt'):
    path = os.path.join(DICT_DIR, name)
    if os.path.exists(path):
        words_ok.update(w.strip().lower() for w in open(path, encoding='utf-8'))
wiki = set()
wiki_path = os.path.join(DICT_DIR, 'wiki', 'wiki_words.txt')
if os.path.exists(wiki_path):
    wiki = {line.split('	')[0].lower() for line in open(wiki_path, encoding='utf-8')}
hunspell = None
try:
    import site
    sys.path.append(site.getusersitepackages())  # spylls is a --user install; -I hides it otherwise
    from spylls.hunspell import Dictionary
    hunspell = Dictionary.from_files(os.path.join(DICT_DIR, 'hunspell', 'de_DE_frami'))
except Exception as e:
    print('no hunspell:', e)
CACHE = os.path.join(DICT_DIR, 'word_cache.json')
cache = json.load(open(CACHE, encoding='utf-8')) if os.path.exists(CACHE) else {}


def is_word(w):
    lw = w.lower()
    if lw in words_ok or (len(w) >= 6 and lw in wiki):
        return True
    if hunspell is None:
        return False
    if w not in cache:
        cache[w] = bool(hunspell.lookup(w))
    return cache[w]


# --- image variants -------------------------------------------------------------------------

def char_height(gray):
    """Median height of letter-sized ink components (px)."""
    _, ink = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    n, _, stats, _ = cv2.connectedComponentsWithStats(ink, 8)
    h = stats[1:, cv2.CC_STAT_HEIGHT]
    w = stats[1:, cv2.CC_STAT_WIDTH]
    keep = (h >= 6) & (h <= 150) & (w <= 3 * h) & (w >= 2)
    return float(np.median(h[keep])) if keep.sum() > 50 else None


def scaled_to(target):
    def f(gray):
        ch = char_height(gray)
        if not ch:
            return gray, 1.0
        s = target / ch
        if 0.85 < s < 1.18:
            return gray, 1.0
        interp = cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC
        return cv2.resize(gray, None, fx=s, fy=s, interpolation=interp), s
    return f


def sauvola(gray):
    g = gray.astype(np.float32)
    k, r, win = 0.2, 128.0, 31
    mean = cv2.boxFilter(g, -1, (win, win))
    sq = cv2.boxFilter(g * g, -1, (win, win))
    std = np.sqrt(np.maximum(sq - mean * mean, 0))
    t = mean * (1 + k * (std / r - 1))
    return np.where(g > t, 255, 0).astype(np.uint8), 1.0


def unsharp(gray):
    blur = cv2.GaussianBlur(gray, (0, 0), 1.5)
    return cv2.addWeighted(gray, 1.6, blur, -0.6, 0), 1.0


VARIANTS = {
    'base': lambda g: (g, 1.0),
    'ch20': scaled_to(20), 'ch26': scaled_to(26), 'ch32': scaled_to(32), 'ch40': scaled_to(40),
    'sauvola': sauvola,
    'unsharp': unsharp,
}
EXTRA_ARGS = {}  # variant -> extra tesseract args


# --- OCR + metrics --------------------------------------------------------------------------

def ocr(path, variant):
    gray = cv2.imdecode(np.fromfile(path, np.uint8), cv2.IMREAD_GRAYSCALE)
    img, scale = VARIANTS[variant.split(':')[0]](gray)
    ok, png = cv2.imencode('.png', img)
    cmd = [TESS, 'stdin', 'stdout', '--tessdata-dir', TESSDATA, '-l', LANG, '--psm', '3', '--dpi', '300',
           '-c', 'tessedit_create_tsv=1'] + EXTRA_ARGS.get(variant, [])
    t0 = time.time()
    p = subprocess.run(cmd, input=png.tobytes(), capture_output=True, env={**os.environ, 'OMP_THREAD_LIMIT': '1'})  # many Tesseracts side by side
    seconds = time.time() - t0
    rows = []
    for line in p.stdout.decode('utf-8', 'replace').splitlines()[1:]:
        f = line.split('\t')
        if len(f) < 12 or f[0] != '5' or not f[11].strip():
            continue
        rows.append((f[11].strip(), float(f[10])))
    return path, rows, scale, char_height(gray), seconds


LETTERS = re.compile(r"[A-Za-zÄÖÜäöüß]{3,}")


def metrics(rows):
    alpha = []
    for text, conf in rows:
        core = text.strip('.,;:!?()[]{}"\'„“”‚‘’»«-–—/*')
        if LETTERS.fullmatch(core):
            alpha.append((core, conf))
    in_dict = sum(1 for w, _ in alpha if is_word(w))
    confs = [c for _, c in rows]
    return dict(words=len(rows), alpha=len(alpha), dict=in_dict,
                conf=float(np.mean(confs)) if confs else 0.0,
                sure=sum(1 for c in confs if c >= 90))


def run(variant):
    os.makedirs(OUT, exist_ok=True)
    per_page = {}
    with cf.ThreadPoolExecutor(max_workers=max(1, os.cpu_count() * 3 // 4)) as ex:
        for path, rows, scale, ch, sec in ex.map(lambda p: ocr(p, variant), INPUTS):
            per_page[os.path.relpath(path, ROOT)] = (metrics(rows), scale, ch, rows, sec)
    with open(os.path.join(OUT, f'{variant}.tsv'), 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f, delimiter='\t')
        w.writerow(['page', 'charHeight', 'scale', 'words', 'alpha', 'dict', 'conf', 'sure', 'text'])
        for page, (m, scale, ch, rows, sec) in sorted(per_page.items()):
            w.writerow([page, ch, round(scale, 2), m['words'], m['alpha'], m['dict'], round(m['conf'], 1), m['sure'],
                        ' '.join(t for t, _ in rows)[:3000]])
    tot = {k: sum(m[k] for m, *_ in per_page.values()) for k in ('words', 'alpha', 'dict', 'sure')}
    conf = np.mean([m['conf'] for m, *_ in per_page.values() if m['words']])
    sec = np.mean([v[4] for v in per_page.values()])
    print(f"{variant:10s} pages={len(per_page)} words={tot['words']} dictWords={tot['dict']} "
          f"dictShare={tot['dict'] / max(1, tot['alpha']):.3f} sure={tot['sure']} meanConf={conf:.1f} secPerPage={sec:.1f}", flush=True)


if __name__ == '__main__':
    if not words_ok:
        sys.exit('set OCR_DICT to the folder with deu.words.txt / eng.words.txt')
    for v in sys.argv[1:]:
        run(v)
        json.dump(cache, open(CACHE, 'w', encoding='utf-8'))
