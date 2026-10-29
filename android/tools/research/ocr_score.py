"""OCR accuracy against hand-made reference texts (tools/research/gt/<page>.txt).

For each page and each OCR output (files <dir>/<page>*.txt, or a glob pattern per source):
  CER   - character error rate: every reference paragraph is aligned semi-globally (free start and
          end) inside the OCR text, so another reading order of captions/columns is not an error;
          cost = edit operations / reference characters
  WRec  - word recall: share of reference words found (multiset, exact, punctuation trimmed)
  WPrec - word precision: share of OCR words that are reference words (low = junk words)
Usage: python ocr_score.py <gt_dir> name=<glob with {page}> [name=<glob> ...]
"""
import sys, os, glob, re, collections
import numpy as np

def normalise(t):
    t = t.replace('­', '')
    t = re.sub(r'(\w)-\s*\n\s*(?=[a-zäöüß])', r'\1', t)  # hyphenation at line ends
    t = t.replace('ﬁ', 'fi').replace('ﬂ', 'fl').replace('’', "'").replace('‘', "'").replace('′', "'")
    t = t.replace('„', '"').replace('“', '"').replace('”', '"')
    return re.sub(r'\s+', ' ', t).strip()

def words(t):
    out = []
    for w in t.split():
        w = w.strip('.,;:!?()[]{}"\'»«|')
        if w: out.append(w)
    return out

def semiglobal(p, t):
    """Minimal edit distance of p against any substring of t (numpy over the columns)."""
    if not p: return 0
    tb = np.frombuffer(t.encode('utf-32-le'), dtype=np.uint32)
    prev = np.zeros(len(tb) + 1, dtype=np.int32)  # free start
    for i, ch in enumerate(p):
        c = ord(ch)
        cur = np.empty_like(prev)
        cur[0] = i + 1
        sub = prev[:-1] + (tb != c)
        dele = prev[1:] + 1
        best = np.minimum(sub, dele)
        # insertion term needs a running minimum: cur[j] = min(best[j-1], cur[j-1] + 1)
        # = min over k<=j of best[k] + (j - k)  -> cumulative min of (best - idx) + idx
        idx = np.arange(1, len(tb) + 1, dtype=np.int32)
        run = np.minimum.accumulate(np.concatenate(([cur[0] - 0], best - idx)))
        cur[1:] = np.minimum(best, run[1:] + idx)
        cur[1:] = np.minimum(cur[1:], run[:-1] + idx)
        prev = cur
    return int(prev.min())  # free end

def score(gt_text, ocr_text):
    g = normalise(gt_text); o = normalise(ocr_text)
    paras = [normalise(p) for p in gt_text.split('\n') if p.strip()]
    errors = sum(semiglobal(p, o) for p in paras)
    chars = sum(len(p) for p in paras)
    gw = collections.Counter(words(g)); ow = collections.Counter(words(o))
    hit = sum((gw & ow).values())
    return errors / max(1, chars), hit / max(1, sum(gw.values())), hit / max(1, sum(ow.values()))

if __name__ == '__main__':
    gt_dir = sys.argv[1]
    sources = [a.split('=', 1) for a in sys.argv[2:]]
    totals = collections.defaultdict(lambda: [0.0, 0.0, 0.0, 0])
    for gt in sorted(glob.glob(os.path.join(gt_dir, '*.txt'))):
        page = os.path.splitext(os.path.basename(gt))[0]
        ref = open(gt, encoding='utf-8').read()
        line = [page]
        for name, pattern in sources:
            files = glob.glob(pattern.replace('{page}', page))
            if not files: line.append(f'{name}: -'); continue
            cer, rec, prec = score(ref, open(files[0], encoding='utf-8', errors='replace').read())
            t = totals[name]; t[0] += cer; t[1] += rec; t[2] += prec; t[3] += 1
            line.append(f'{name}: CER {cer*100:5.1f}% WRec {rec*100:5.1f}% WPrec {prec*100:5.1f}%')
        print(' | '.join(line))
    for name, (c, r, p, n) in totals.items():
        print(f'MEAN {name} ({n} pages): CER {c/n*100:5.2f}%  WRec {r/n*100:5.2f}%  WPrec {p/n*100:5.2f}%')
