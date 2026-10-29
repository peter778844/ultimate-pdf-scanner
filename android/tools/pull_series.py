"""Copy every document from the phone (doc.json, original photos, finished pages, OCR words) into
testdata/series/<created>_<name>/ for evaluation. Usage: python -I pull_series.py [since_epoch_ms]"""
import json, os, subprocess, sys

ADB = os.path.join(os.environ['LOCALAPPDATA'], 'Android', 'Sdk', 'platform-tools', 'adb.exe')
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'testdata', 'series')
since = int(sys.argv[1]) if len(sys.argv) > 1 else 0

def sh(*args):
    return subprocess.run([ADB, 'exec-out', 'run-as', 'de.ultimatepdfscanner.app', *args], capture_output=True).stdout

for d in sh('ls', 'files/docs').decode().split():
    raw = sh('cat', f'files/docs/{d}/doc.json')
    try:
        doc = json.loads(raw)
    except ValueError:
        continue
    if doc['created'] < since or doc.get('deletedAt'):
        continue
    pending = sum(1 for p in doc.get('photos', []) if not p.get('processed'))
    name = f"{doc['created']}_{doc['name']}".replace(' ', '_').replace(':', '-')
    target = os.path.join(OUT, name)
    os.makedirs(os.path.join(target, 'originals'), exist_ok=True)
    os.makedirs(os.path.join(target, 'pages'), exist_ok=True)
    open(os.path.join(target, 'doc.json'), 'wb').write(raw)
    for f in sh('ls', f'files/docs/{d}/originals').decode().split():
        open(os.path.join(target, 'originals', f), 'wb').write(sh('cat', f'files/docs/{d}/originals/{f}'))
    for p in doc['pages']:
        for suffix in ('.jpg', '.words.json'):
            open(os.path.join(target, 'pages', p['id'] + suffix), 'wb').write(sh('cat', f"files/docs/{d}/{p['id']}{suffix}"))
    print(f"{name}: {len(doc['photos'])} photos, {len(doc['pages'])} pages, type={doc.get('type')}, pending={pending}")
