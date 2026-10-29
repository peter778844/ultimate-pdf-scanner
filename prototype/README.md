# ultimatepdfscanner – offline document scanner (PC prototype)

Offline book and document scan pipeline built only from open-source components
(reference implementation of the Android app's core). Target platform: Android.

## Pipeline

```
photo ─► detect ─► warp ─► split ─► dewarp ─► orient ─► enhance ─► OCR ─► export
         GrabCut   perspective  fold at   edges +    Tesseract  shadow/    Tesseract  PDF + text layer,
         mask      + aspect     gutter    text lines OSD        colour               TXT, DOCX
```

| Module | Status | Method | Android port |
|---|---|---|---|
| `detect.py` | done | GrabCut segmentation (copes with wooden tables), quad from hull; Canny fallback | OpenCV Android SDK, same calls |
| `warp.py` | done | homography; page aspect ratio from camera geometry (Zhang & He 2007); margin for bulging edges | OpenCV |
| `spread.py` | done | open book: gutter = two-sided paper-darkening valley at 40–60 % present across the whole width | OpenCV |
| `dewarp.py` | done | 1) rows resampled between the curved side edges (arc-length spacing) 2) text-line refinement: tile profile alignment → smooth shift surface | OpenCV |
| `fingers.py` | done | skin colour (YCrCb) + shape rules (reaches in from the border, compact) → inpaint | OpenCV |
| `deblur.py` | done | blur sigma from text-edge steepness; Richardson-Lucy (Gaussian PSF) only if sigma > 1.4 px | OpenCV |
| orientation | done | Tesseract OSD (`--psm 0`) | Tesseract4Android |
| `handwriting.py` | done, opt-in | blue/violet/red pen by hue, pencil = mid-grey pixels away from dark print; black pen not handled | OpenCV |
| `enhance.py` | done | background division + white/black point (paper → pure white); colour pages: global white balance; modes auto / color / gray / bw | OpenCV |
| `ocr.py` | done | Tesseract 5 via TSV (words + boxes), local `tessdata/` (tessdata_best: deu, eng, osd) | Tesseract4Android, or PaddleOCR (ONNX) |
| `export.py` | done | reportlab PDF with invisible text layer, TXT, DOCX | `android.graphics.pdf.PdfDocument` |

Measured on 6 real phone photos (5 magazine spreads + cover, 11 pages, German):
share of words (>2 chars) with Tesseract confidence ≥ 90 — no dewarp 88.9 %,
edge dewarp 93.2 %, edge + text-line dewarp 95.6 %.

Optional ML upgrades (ONNX, check licences first): LaMa inpainting for fingers over text,
NAFNet for motion blur, a handwriting segmentation model for black pen, UVDoc for strongly curled pages.

## Usage

```
pip install -r requirements.txt          # plus Tesseract 5 (C:\Program Files\Tesseract-OCR)
python -m ultimatepdfscanner photo1.jpg photo2.jpg -o book.pdf --mode bw --txt --docx --lang deu
python -m ultimatepdfscanner C:\path\to\folder -o book.pdf --lang deu   # folders and wildcards work too
python -m ultimatepdfscanner page.jpg --debug-dir debug   # writes detection + fold overlays
```

Options: `--mode auto|color|gray|bw`, `--color-pages 1,5-7` (these pages in colour, e.g. the cover),
`--remove-handwriting`, `--split off` (never split spreads), `--no-dewarp`, `--no-fingers`, `--no-deblur`,
`--no-orient`, `--no-detect`, `--no-ocr`.
The language models live in `tessdata/` (tessdata_best from github.com/tesseract-ocr).
Runtime on the PC: ~15 s per spread photo (12 MP), mostly Tesseract.

## Tests

```
python -m unittest discover -s tests -t . -v
```

The test builds a synthetic photo (text page with a shadow, tilted in front of a
pinhole camera) and checks corner accuracy, aspect ratio, shadow removal, OCR
recall and that the PDF text layer is extractable.
