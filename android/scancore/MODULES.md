# Ultimate PDF Scanner – Bausteine (scancore)

Stand: 25.10.2026, Modul-Audit vor dem Neuaufbau. Alle Module liegen in
`android/scancore/src/main/kotlin/de/ultimatepdfscanner/core/`, sind reines Kotlin + OpenCV (+ ONNX Runtime
für die Lernmodelle) und laufen unverändert auf Android und am PC (JVM-Tests).

Grundregeln für alle Bausteine:

- **Speicher:** OpenCV-`Mat`s belegen nativen Speicher. Jede Funktion gibt neue Mats zurück, die
  der Aufrufer freigibt (`release()` / `PageResult.close()`); intern wird `matScope { … }` benutzt.
- **Threads:** Alle `object`-Module sind zustandslos und threadsicher. `PaddleOcr`, `CornerModel`
  (ONNX Runtime) dürfen von mehreren Threads gleichzeitig benutzt werden.
  `TesseractOcr` (App) ist **nicht** threadsicher: eine Instanz pro Thread.
- **Koordinaten:** Wortboxen (`Word`) sind in Pixeln des Seitenbildes, das die Pipeline liefert.

## Der Hauptweg: `Pipeline.processPhoto`

```kotlin
val pages: List<PageResult> = Pipeline.processPhoto(
    image,                       // Foto, BGR
    PipelineConfig(split = SplitMode.BOOK, mode = Mode.COLOR),
    engine = paddleOcr,          // OcrEngine (PaddleOcr empfohlen) oder null = ohne OCR
    focal = 0.54,                // Brennweite / Bilddiagonale (EXIF), Default 0.55
    manualQuad = null,           // vom Nutzer korrigierte Ecken
    secondEngine = paddleOcr,    // beide Seiten einer Doppelseite parallel
    corners = cornerModel,       // DocAligner als zweite Meinung (optional)
) { step -> /* Fortschritt, deutscher Schrittname */ }
```

`PageResult`: `image` (fertige Seite), `rectified` (bereinigte Seite vor dem Farbmodus, für späteres
Umschalten mit `Pipeline.render(rectified, mode)`), `words`, `text`, `notes`, `quad`, `issues`
(`Issue`: NO_PAGE, BLURRY, DARK, POOR_TEXT, NO_TEXT), `rotation`.

Ablauf und zuständige Bausteine:

| Schritt | Baustein | Kurz |
|---|---|---|
| Seite finden | `Detect` (+ `CornerModel`) | GrabCut-Segmentierung, Haut-/Holzfilter, RANSAC-Kanten; DocAligner nur wo Segmentierung scheitert |
| Perspektive | `Warp` | Homographie mit echtem Seitenverhältnis (Zhang-He, feste Brennweite) |
| Falz / Teilen | `Spread` | Falz über Papierhelligkeit; **neu:** schräger Falz wird gerade gezogen (`foldShift`) |
| Exakter Umriss + Entzerrung | `Outline`, `Dewarp` | **neu:** Kanten auf voller Auflösung, lokale GrabCut-Verfeinerung, RANSAC, Coons-Patch; danach Textzeilen-Begradigung |
| Randstreifen | `Borders` | Nachbarseite / Buchblock-Kanten weg; **neu:** Text nur jenseits des Falzes, Füllung mit Papierverlauf |
| Finger | `Fingers`, `PaperFill` | Hautfarbene Bereiche vom Rand her; **neu:** Füllung mit geschätztem Papier statt Inpainting-Schlieren |
| Schärfe | `Deblur` | Richardson-Lucy nur bei geschätzter Unschärfe > 1,4 px |
| Ausrichtung | `Orientation` / `OcrEngine.orientation` | **neu (Paddle):** Zeilenachse aus Textkästen + Zeilen-Klassifikator 0/180°, Ober-/Unterlängen als Schiedsrichter |
| Bildverbesserung | `Enhance` | Hintergrund-Division (Schatten, Vergilbung); **neu:** Farbseiten über Beleuchtungsfeld (`colorFlatten`), Rauschbremse |
| OCR | `PaddleOcr` / Tesseract, `TextSize`, `Leaders`, `OcrGate` | **neu:** PaddleOCR PP-OCRv5; Textur-Torwächter; Zeitlimit |
| Qualität | `Quality` | Hinweise für „neu aufnehmen“ |

`SplitMode`: AUTO (teilt bei klarem Falz), BOOK (Buchmodus: teilt bei schwachem Falz, Deckel/Einzelblatt bleiben ganz), SINGLE, DOUBLE (immer teilen – auch Deckel!).
`Mode`: COLOR (Standard), GRAY, BW, AUTO, NONE.

## Einzelne Bausteine (direkt nutzbar)

### PDF – `PdfWriter` (neu)
```kotlin
PdfWriter(outputStream, PdfInfo(title = "…", password = null /* AES-256 wenn gesetzt */)).use { w ->
    w.addPage(PdfPage.natural(PdfImage.jpeg(jpegBytes), words, dpi = 300.0, rotation = 0))
    w.addPage(PdfPage.onPaper(PdfImage.bilevel(grayMat), PdfPage.A4.first, PdfPage.A4.second, words))
}
```
- Unsichtbare Textebene mit **vollem Unicode** (griechisch, Formelzeichen, €, Umlaute), Wörter mit Leerzeichen, Markierung deckt die Zeile.
- **PDF/A-2b** (ohne Passwort): von veraPDF geprüft (618 Regeln, Test `PdfaTest`).
- **AES-256** (Revision 6) mit Passwort: von Ghostscript und xpdf geprüft.
- **Streaming:** jede Seite geht sofort in den Ausgabestrom – auch 500 Seiten brauchen kaum Speicher.
- Schwarzweiß-Seiten als 1-Bit-Bild (`PdfImage.bilevel`): kleiner und schärfer als JPEG.
- In der App bereits benutzt (`Exporter.writePdf`); der Ausweis-PDF-Export nutzt noch PdfBox.

### OCR – `PaddleOcr` (neu, empfohlen) und Tesseract
```kotlin
val ocr = PaddleOcr(detBytes, recBytes, threads = 2, clsModel = clsBytes)  // einmal, geteilt
val words = ocr.recognize(grayMat)          // Wörter in Lesereihenfolge (Block/Zeile)
val rot = ocr.orientation(grayMat, hint)    // 0/90/180/270
```
Modelle (Apache-2.0, in `app/src/main/assets/models/`, Quelle `models/paddle` im Projektordner):
`ch_PP-OCRv5_det_mobile.onnx` (4,8 MB), `latin_PP-OCRv5_rec_mobile.onnx` (7,9 MB),
`ch_ppocr_mobile_v2.0_cls_mobile.onnx` (0,6 MB).
Gemessen auf 6 von Hand abgeschriebenen Seiten der Testserie (`tools/research/gt`, `ocr_score.py`):

| Engine | Zeichenfehler | Wörter richtig | Präzision |
|---|---|---|---|
| Tesseract (tessdata_best, deu+eng) | 5,65 % | 91,0 % | 81,1 % |
| **PaddleOCR v5 latin** | **3,76 %** | **95,5 %** | **86,8 %** |

Paddle liest zusätzlich viele Formeln mit griechischen Buchstaben. Gilt für lateinische Schrift
(`OcrLanguages.PADDLE_LATIN`); andere Schriften und Fraktur → Tesseract (`TesseractOcr`, App).
Auf dem Handy (Xiaomi 17T Pro): ca. 4–6 s Texterkennung pro Seite.

### Weitere Bausteine
- `OcrGate.assess(gray)` – Ist überhaupt Text da? (Tischfoto 256 s → 13 s)
- `TextSize` – Buchstabengröße messen/normieren (für Tesseract 20 px; Paddle liest unskaliert)
- `Leaders` – Punktlinien in Inhaltsverzeichnissen entfernen
- `Quality.assess(...)` – Unscharf/dunkel/schlecht lesbar/kein Text
- `Classify` – Dokumenttyp + Dateiname aus dem Text
- `Tables` – Tabellen aus Wortboxen → CSV/XLSX (`Office`)
- `Office` – DOCX/XLSX ohne Bibliothek
- `Contact` – Visitenkarte → vCard
- `Redact` – Kartennummer/IBAN/Steuer-ID/Passwörter finden (mit Prüfsummen)
- `Signature` – Unterschrift einblenden
- `TextEdit` – Textkorrekturen auf Wortboxen zurückführen
- `Picture` – Fotos/Zeitschriften farbtreu (Raster entfernen)
- `Passport` – Passbild 35×45 mm + Druckbogen
- `PageTurn` – Buchmodus: automatisch auslösen nach dem Umblättern
- `Handwriting` – farbige Stifte/Bleistift entfernen (aus, weil auch Farbdruck betroffen)

## Messwerkzeuge (für jede Änderung)

- `./gradlew :scancore:test` – 70+ Tests (u. a. PDF/A, Verschlüsselung, Ausleuchtung, Finger, Ränder, Paddle).
- Serienauswertung auf echten Fotos: `./gradlew :scancore:test -Deval=true -Docr=paddle -DevalOut=build/eval_x --tests '*SeriesEval'`
  (Optionen: `-DevalRoot=../../testdata/series2 -DevalSplit=BOOK`, `-DevalPhoto=a.jpg,b.jpg`,
  `-Dultimatepdfscanner.debug=1`, `-Deval=stages -DevalPhoto=x.jpg` für Zwischenbilder). Ergebnis: `summary.csv`, `pages.csv`, Seiten, Texte.
- `tools/research/ocr_score.py gt name=<glob mit {page}>` – OCR-Genauigkeit gegen Referenztexte.
- `tools/research/edge_residue.py build/eval_x` – dunkle Reste an den Seitenrändern.

## Bekannte Grenzen (Stand heute)

- Stark gewölbte Seiten (Buchrücken-nah) werden noch nicht vollständig geglättet (Zeilen leicht gebogen).
- `SplitMode.DOUBLE` teilt auch Deckel und Einzelblätter – für die Kamera BOOK verwenden.
- Formeln: Paddle liest einfache Formeln als Text; echtes LaTeX braucht ein Formelmodell (geplant).
- Fingerentfernung erkennt Haut über Farbe; sehr warmes Licht oder bräunliches Papier kann stören.
