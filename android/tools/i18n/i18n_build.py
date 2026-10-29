"""Build app/src/main/assets/i18n/<code>.tsv from keys.tsv (numbered German keys) and trans_<code>.txt
(lines "N<TAB>translation"). Checks completeness and placeholders. English comes from keys.tsv itself."""
import os, re, sys
sys.stdout.reconfigure(encoding='utf-8')
S = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(S, '..', '..', 'app', 'src', 'main', 'assets', 'i18n')
SKIP = {11, 81, 83, 206, 250}  # no longer used in the code
EXTRA = {446: ('Hindi', 'Hindi'), 447: ('Bengalisch', 'Bengali'), 448: ('Urdu', 'Urdu'), 449: ('Indonesisch', 'Indonesian'),
         450: ('Seite erkennen', 'Detecting page'), 451: ('Seite entzerren', 'Flattening page'),
         452: ('Finger entfernen', 'Removing fingers'), 453: ('Schärfe prüfen', 'Checking sharpness'),
         454: ('Ausrichtung', 'Orientation'), 455: ('Bild verbessern', 'Enhancing image'),
         456: ('Speicherort', 'Storage location'),
         457: ('Speicherort für Exporte', 'Storage location for exports'),
         458: ('Jedes Mal fragen', 'Ask every time'),
         459: ('Ordner wählen …', 'Choose folder …'),
         460: ('„Speichern“ legt PDFs und andere Exporte direkt in diesem Ordner ab, ohne jedes Mal nachzufragen. Auch eine SD-Karte ist möglich.', '“Save” puts PDFs and other exports straight into this folder without asking every time. An SD card works too.'),
         461: ('Gespeichert in „%s“', 'Saved to “%s”'),
         462: ('Der Speicherort ist nicht erreichbar. Bitte neu wählen.', 'The storage location cannot be reached. Please choose it again.'),
         463: ('Neu aufnehmen', 'Retake'),
         464: ('Um 90° links drehen', 'Rotate 90° left'),
         465: ('Seite gelöscht', 'Page deleted'),
         466: ('Lange drücken: neu aufnehmen, drehen oder löschen', 'Press and hold: retake, rotate or delete'),
         467: ('Ersetzt beide Seiten', 'Replaces both pages'),
         468: ('Ränder bereinigen', 'Cleaning borders'),
         469: ('Scan-Qualität', 'Scan quality'),
         470: ('Schnell', 'Fast'),
         471: ('Standard', 'Standard'),
         472: ('Genau', 'Accurate'),
         473: ('Für große, klare Schrift wie Briefe und Ausdrucke: kleinere Fotos, schnellste Verarbeitung.', 'For large, clear print such as letters and printouts: smaller photos, fastest processing.'),
         474: ('Für Bücher und die meisten Dokumente: gute Erkennung bei normaler Geschwindigkeit.', 'For books and most documents: good recognition at normal speed.'),
         475: ('Für Kleingedrucktes, alte Bücher, Fußnoten und Formeln: volle Kameraauflösung, langsamer, mehr Speicher.', 'For small print, old books, footnotes and formulas: full camera resolution, slower, more memory.'),
         476: ('BILD', 'PICTURE'),
         477: ('Bild flach hinlegen · kein Blitz · Spiegelungen vermeiden', 'Lay the picture flat · no flash · avoid reflections'),
         478: ('an', 'on'),
         479: ('aus', 'off'),
         480: ('Automatische Auslösung an: nach dem Umblättern', 'Auto capture on: after each page turn'),
         481: ('Automatische Auslösung an: sobald die Seite ruhig liegt', 'Auto capture on: as soon as the page is steady'),
         482: ('Automatische Auslösung aus', 'Auto capture off'),
         483: ('PDF & Export', 'PDF & export'),
         484: ('Speicherort für PDFs und Bilder', 'Storage location for PDFs and images'),
         485: ('Beim Exportieren lässt sich das für einzelne Dateien ändern.', 'You can change this for single files when exporting.'),
         486: ('Weitere Optionen …', 'More options …')}

keys = {}
for n, line in enumerate(open(os.path.join(S, 'keys.tsv'), encoding='utf-8'), 1):
    de, en = line.rstrip('\n').split('\t')
    if n not in SKIP: keys[n] = (de, en)
keys.update(EXTRA)

def unesc(s): return s.replace('\\"', '"')  # Kotlin escapes; \n stays as backslash-n in the TSV
ph = lambda s: sorted(re.findall(r'%[sd%]', s))

def write(code, table):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, code + '.tsv'), 'w', encoding='utf-8', newline='\n') as f:
        for n in sorted(keys):
            f.write(unesc(keys[n][0]) + '\t' + table[n] + '\n')

write('en', {n: unesc(en) for n, (_, en) in keys.items()})
ok = True
for code in sys.argv[1:]:
    table, problems = {}, []
    for line in open(os.path.join(S, f'trans_{code}.txt'), encoding='utf-8'):
        line = line.rstrip('\n')
        if not line.strip(): continue
        n, _, text = line.partition('\t')
        if not n.strip().isdigit(): problems.append('bad line: ' + line[:40]); continue
        n = int(n)
        if n in SKIP: continue
        if n not in keys: problems.append(f'unknown number {n}'); continue
        if n in table: problems.append(f'duplicate {n}')
        table[n] = text
        if ph(text) != ph(keys[n][0]): problems.append(f'{n}: placeholders {ph(text)} vs {ph(keys[n][0])}')
        if ('\\n' in keys[n][0]) != ('\\n' in text): problems.append(f'{n}: line break differs')
    missing = sorted(set(keys) - set(table))
    if missing: problems.append(f'missing {missing}')
    if problems: ok = False; print(code, 'PROBLEMS:', *problems, sep='\n  ')
    else:
        write(code, table); print(code, 'ok', len(table))
sys.exit(0 if ok else 1)
