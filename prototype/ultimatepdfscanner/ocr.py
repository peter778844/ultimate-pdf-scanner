"""OCR engines. Tesseract runs as an external process (same engine as Tesseract4Android)."""
import csv
import io
import os
import shutil
import subprocess
from pathlib import Path

import cv2
import numpy as np

from .types import Word

_DEFAULT_EXE = Path(r"C:\Program Files\Tesseract-OCR\tesseract.exe")
_LOCAL_TESSDATA = Path(__file__).resolve().parent.parent / "tessdata"  # tessdata_best models (deu, eng, osd)


def find_tesseract() -> str:
    exe = os.environ.get("TESSERACT_EXE") or shutil.which("tesseract")
    if exe:
        return exe
    if _DEFAULT_EXE.exists():
        return str(_DEFAULT_EXE)
    raise FileNotFoundError("Tesseract not found; install it or set TESSERACT_EXE")


class TesseractEngine:
    def __init__(self, lang: str = "eng", psm: int = 3, exe: str | None = None, tessdata: Path | None = None):
        self.lang = lang
        self.psm = psm
        self.exe = exe or find_tesseract()
        self.tessdata = tessdata or (_LOCAL_TESSDATA if _LOCAL_TESSDATA.is_dir() else None)

    def _run(self, image: np.ndarray, args: list[str]) -> str:
        ok, png = cv2.imencode(".png", image)
        if not ok:
            raise RuntimeError("could not encode image for OCR")
        cmd = [self.exe, "stdin", "stdout"]
        if self.tessdata:
            cmd += ["--tessdata-dir", str(self.tessdata)]
        res = subprocess.run(cmd + args, input=png.tobytes(), capture_output=True, check=False)
        if res.returncode != 0:
            raise RuntimeError(f"tesseract failed: {res.stderr.decode(errors='replace').strip()}")
        return res.stdout.decode("utf-8", errors="replace")

    def recognize(self, image: np.ndarray, dpi: int = 300) -> list[Word]:
        # -c instead of the "tsv" config name: config files are not in a custom --tessdata-dir
        args = ["-l", self.lang, "--psm", str(self.psm), "--dpi", str(dpi), "-c", "tessedit_create_tsv=1"]
        return _parse_tsv(self._run(image, args))

    def orientation(self, image: np.ndarray) -> int | None:
        """Clockwise rotation (0/90/180/270) that makes the text upright, or None if OSD is unsure."""
        try:
            out = self._run(image, ["--psm", "0", "--dpi", "300"])
        except RuntimeError:
            return None  # too little text for OSD
        info = dict(line.split(":", 1) for line in out.splitlines() if ":" in line)
        if float(info.get("Orientation confidence", "0")) < 1.5:
            return None
        return int(info["Rotate"])


def _parse_tsv(tsv: str) -> list[Word]:
    words = []
    for row in csv.DictReader(io.StringIO(tsv), delimiter="\t", quoting=csv.QUOTE_NONE):
        if row.get("level") != "5" or not (text := (row.get("text") or "").strip()):
            continue
        conf = float(row["conf"])
        if conf < 0:
            continue
        x, y, w, h = (int(row[k]) for k in ("left", "top", "width", "height"))
        line_id = (int(row["block_num"]), int(row["par_num"]), int(row["line_num"]))
        words.append(Word(text, conf, (x, y, x + w, y + h), line_id))
    return words
