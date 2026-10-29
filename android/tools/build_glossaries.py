"""Translation glossaries from the IATE export (https://iate.europa.eu/download-iate, reuse allowed with
attribution): one gzip file per language, lines "concept id <TAB> subject fields <TAB> term". Two language
files joined on the concept id give a glossary for any language pair, so 24 files cover all 552 pairs.
Kept: reliability 2-4 (1 = not verified), not deprecated/superseded; subject fields = EuroVoc top level (2 digits).
usage: python -I tools/build_glossaries.py <IATE_export.tbx> <out dir>   (writes glossary-<lang>.tsv.gz + glossaries.json)"""
import gzip
import hashlib
import json
import os
import sys
import xml.etree.ElementTree as ET

XML_LANG = '{http://www.w3.org/XML/1998/namespace}lang'
src, out_dir = sys.argv[1], sys.argv[2]
os.makedirs(out_dir, exist_ok=True)
files, counts = {}, {}
for _, entry in ET.iterparse(src):
    if entry.tag != 'termEntry':
        continue
    cid = entry.get('id', '').removeprefix('IATE-')
    fields = sorted({f.strip()[:2] for d in entry.iter('descrip') if d.get('type') == 'subjectField'
                     for f in (d.text or '').split(';') if f.strip()[:2].isdigit()})
    for lang_set in entry.findall('langSet'):
        lang = lang_set.get(XML_LANG)
        if lang in ('la', 'mul'):
            continue
        for tig in lang_set.findall('tig'):
            term = ' '.join((tig.findtext('term') or '').split())
            rel = next((d.text for d in tig.findall('descrip') if d.get('type') == 'reliabilityCode'), '1')
            status = next((n.text for n in tig.findall('termNote') if n.get('type') == 'administrativeStatus'), '')
            if not term or rel == '1' or status in ('deprecatedTerm-admn-sts', 'supersededTerm-admn-sts'):
                continue
            if lang not in files:
                files[lang] = gzip.open(os.path.join(out_dir, f'glossary-{lang}.tsv.gz'), 'wt', encoding='utf-8', compresslevel=9)
            files[lang].write(f"{cid}\t{','.join(fields)}\t{term}\n")
            counts[lang] = counts.get(lang, 0) + 1
    entry.clear()
manifest = []
for lang, f in sorted(files.items()):
    f.close()
    path = os.path.join(out_dir, f'glossary-{lang}.tsv.gz')
    data = open(path, 'rb').read()
    manifest.append({'lang': lang, 'file': os.path.basename(path), 'terms': counts[lang], 'bytes': len(data),
                     'sha256': hashlib.sha256(data).hexdigest()})
json.dump({'source': os.path.basename(src), 'packs': manifest}, open(os.path.join(out_dir, 'glossaries.json'), 'w'), indent=1)
for m in manifest:
    print(m['lang'], m['terms'], round(m['bytes'] / 1e6, 1), 'MB')
print('total', round(sum(m['bytes'] for m in manifest) / 1e6, 1), 'MB')
