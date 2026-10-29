# Ultimate PDF Scanner

Offline document and book scanner for Android. Everything runs on the phone: page detection,
perspective and curvature correction, splitting of book spreads, image enhancement, text
recognition (Tesseract, PaddleOCR) and export to PDF (searchable, PDF/A, AES-256), Word, Excel,
images and text. On-device translation keeps the page layout and can use special-term glossaries.

Licence: GNU GPL v3 (see `android/LICENSE`).

## Layout

- `android/scancore` – the scan pipeline (pure Kotlin + OpenCV), JVM tests
- `android/app` – the Android app (Jetpack Compose, CameraX)
- `prototype` – Python reference implementation of the pipeline
- `android/tools` – helper scripts (glossary build, translations of the user interface)

## Building

`cd android && ./gradlew :app:assembleRelease` (JDK 17+, Android SDK). The models are not part
of the repository: the ONNX models (DocAligner page corners, PaddleOCR PP-OCRv5, PP-DocLayout,
handwriting) go into `android/app/src/main/assets/models/` – the file names are in
`ScanViewModel` – and Tesseract's `deu`/`eng.traineddata` into `android/app/src/main/assets/tessdata/`.

## Glossaries

The special-term glossaries offered for download in the app (release `glossaries-v1`) are
built by `android/tools/build_glossaries.py` from IATE, the terminology database of the European
Union. Source: IATE, © European Union, https://iate.europa.eu – reuse is authorised provided the
source is acknowledged.
