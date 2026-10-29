"""End-to-end test on a synthetic photo: a text page with a shadow, photographed at an angle."""
import subprocess
import tempfile
import unittest
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from ultimatepdfscanner import PipelineConfig, process_image
from ultimatepdfscanner.export import export_docx, export_pdf, export_txt

WORDS = ("The quick brown fox jumps over the lazy dog while seven wizards "
         "quickly judge boxing matches near the river bank").split()
FONT = Path(r"C:\Windows\Fonts\DejaVuSans.ttf")
GS = Path(r"C:\Program Files\gs\gs10.08.0\bin\gswin64c.exe")


def make_page(w=1240, h=1754) -> np.ndarray:
    img = Image.new("L", (w, h), 245)
    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype(str(FONT), 46)
    y, line = 140, []
    for word in WORDS * 2:
        if draw.textlength(" ".join(line + [word]), font=font) > w - 240:
            draw.text((120, y), " ".join(line), fill=20, font=font)
            y, line = y + 80, []
        line.append(word)
    draw.text((120, y), " ".join(line), fill=20, font=font)
    page = np.array(img, dtype=np.float32)
    shadow = np.linspace(1.0, 0.55, w, dtype=np.float32)[None, :]  # darker towards the right edge
    return cv2.cvtColor(np.clip(page * shadow, 0, 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)


def project_page(w: int, h: int, size=(2000, 2400), f=2200.0) -> np.ndarray:
    """Corners of a w x h page seen by a pinhole camera (principal point at image centre), tilted."""
    rx, ry, rz = np.radians([18, -14, 4])
    rot = cv2.Rodrigues(np.array([rx, ry, rz]))[0]
    corners = np.array([[-w / 2, -h / 2, 0], [w / 2, -h / 2, 0], [w / 2, h / 2, 0], [-w / 2, h / 2, 0]])
    cam = corners @ rot.T + np.array([0, 0, 2600.0])
    return np.float32(cam[:, :2] / cam[:, 2:] * f + np.array(size) / 2)


def make_photo(page: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(0)
    bg = rng.integers(40, 80, (2400, 2000, 3), dtype=np.uint8)
    bg = cv2.GaussianBlur(bg, (9, 9), 0)
    h, w = page.shape[:2]
    quad = project_page(w, h)
    m = cv2.getPerspectiveTransform(np.float32([[0, 0], [w, 0], [w, h], [0, h]]), quad)
    warped = cv2.warpPerspective(page, m, (2000, 2400))
    mask = cv2.warpPerspective(np.full((h, w), 255, np.uint8), m, (2000, 2400))
    photo = np.where(mask[..., None] > 0, warped, bg)
    return photo, quad


class PipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.photo, cls.true_quad = make_photo(make_page())
        cls.page = process_image(cls.photo, PipelineConfig(mode="gray"))

    def test_detects_page_corners(self):
        self.assertIsNotNone(self.page.quad)
        err = np.linalg.norm(self.page.quad - self.true_quad, axis=1).max()
        self.assertLess(err, 15, f"corner error {err:.1f}px")

    def test_output_is_upright_and_page_sized(self):
        h, w = self.page.image.shape[:2]
        self.assertAlmostEqual(h / w, 1754 / 1240, delta=0.08)

    def test_shadow_is_flattened(self):
        img = self.page.image
        h, w = img.shape[:2]
        left = np.percentile(img[:, : w // 10], 90)
        right = np.percentile(img[:, -w // 10 :], 90)
        self.assertLess(abs(float(left) - float(right)), 15)

    def test_ocr_reads_most_words(self):
        found = {w.text.strip(".,").lower() for w in self.page.words}
        hits = sum(word.lower() in found for word in set(WORDS))
        self.assertGreaterEqual(hits / len(set(WORDS)), 0.9, f"OCR text: {self.page.text!r}")

    def test_exports(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            export_pdf([self.page], d / "out.pdf")
            export_txt([self.page], d / "out.txt")
            export_docx([self.page], d / "out.docx")
            pdf = (d / "out.pdf").read_bytes()
            self.assertTrue(pdf.startswith(b"%PDF"))
            self.assertIn("quick", (d / "out.txt").read_text(encoding="utf-8").lower())
            self.assertGreater((d / "out.docx").stat().st_size, 1000)

    @unittest.skipUnless(GS.exists(), "Ghostscript not installed")
    def test_pdf_text_layer_is_searchable(self):
        with tempfile.TemporaryDirectory() as d:
            pdf = Path(d) / "out.pdf"
            export_pdf([self.page], pdf)
            res = subprocess.run([str(GS), "-q", "-dNOPAUSE", "-dBATCH", "-sDEVICE=txtwrite", "-sOutputFile=-", str(pdf)],
                                 capture_output=True, check=True)
            text = res.stdout.decode("utf-8", errors="replace").lower()
            self.assertIn("wizards", text)
            self.assertIn("river", text)


if __name__ == "__main__":
    unittest.main()
