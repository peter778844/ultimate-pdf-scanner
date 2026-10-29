"""List the German UI texts used in the code (tr/trn and translated labels) that have no entry in
assets/i18n/en.tsv yet (--missing, default) or all of them (--all). New entries go into keys.tsv
(German TAB English) and every trans_<code>.txt, then run i18n_build.py."""
import os, re, sys
sys.stdout.reconfigure(encoding='utf-8')
BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
APP = os.path.join(BASE, 'app', 'src', 'main', 'java', 'de', 'ultimatepdfscanner', 'app')
CORE = os.path.join(BASE, 'scancore', 'src', 'main', 'kotlin', 'de', 'ultimatepdfscanner', 'core')
LIT = r'"((?:\\.|[^"\\])*)"'

def unescape(s): return s.replace('\\"', '"').replace('\\n', '\n').replace('\\$', '$')

keys = []
def add(k):
    if k not in keys: keys.append(k)

for d, _, fs in os.walk(APP):
    for f in sorted(fs):
        if not f.endswith('.kt'): continue
        s = open(os.path.join(d, f), encoding='utf-8').read()
        for m in re.finditer(r'\btr\(\s*' + LIT, s): add(unescape(m.group(1)))
        for m in re.finditer(r'\btrn\([^,]+,\s*' + LIT + r'\s*,\s*' + LIT, s): add(unescape(m.group(1))); add(unescape(m.group(2)))
        # labels translated where shown
        for m in re.finditer(r'Language\("[a-z_]+", ' + LIT, s): add(unescape(m.group(1)))
        for m in re.finditer(r'"[a-z]{2}" to ' + LIT, s): add(unescape(m.group(1)))
        if f in ('ScanViewModel.kt', 'Exporter.kt', 'I18n.kt'):
            for m in re.finditer(r'\b[A-Z_]+\(' + LIT, s):
                v = unescape(m.group(1))
                if not re.fullmatch(r'[a-z0-9_]+|application/.*|image/.*|text/.*', v): add(v)
        for m in re.finditer(r'mapOf\("CELL" to ' + LIT + r', "FAX" to ' + LIT + r'\)\.getOrDefault\(t, ' + LIT, s):
            for g in m.groups(): add(unescape(g))
for f in ('Classify.kt', 'Quality.kt'):
    s = open(os.path.join(CORE, f), encoding='utf-8').read()
    for m in re.finditer(r'\b[A-Z_]+\(' + LIT + r'(?:,\s*' + LIT + r')?', s):
        add(unescape(m.group(1)))
        if m.group(2): add(unescape(m.group(2)))

tsv = os.path.join(BASE, 'app', 'src', 'main', 'assets', 'i18n', 'en.tsv')
have = set(l.split('\t')[0].replace('\\n', '\n') for l in open(tsv, encoding='utf-8') if '\t' in l)
show = keys if '--all' in sys.argv else [k for k in keys if k not in have]
for k in show: print(repr(k))
print(f'--- {len(keys)} keys, {len([k for k in keys if k not in have])} missing', file=sys.stderr)
