"""Synthetic tests for the cleaning stages: fingers, deblur, handwriting, background levels."""
import unittest

import cv2
import numpy as np

from ultimatepdfscanner.deblur import BLUR_SIGMA, deblur, estimate_blur
from ultimatepdfscanner.enhance import enhance, flatten_illumination, to_gray
from ultimatepdfscanner.fingers import remove_fingers
from ultimatepdfscanner.handwriting import remove_handwriting
from ultimatepdfscanner.ocr import TesseractEngine

from .test_pipeline import WORDS, make_page


def small_page() -> np.ndarray:
    """Page at ~23 px font size, close to a 12 MP photo of a magazine spread."""
    return cv2.resize(make_page(), None, fx=0.5, fy=0.5, interpolation=cv2.INTER_AREA)


def recall(engine: TesseractEngine, image: np.ndarray) -> float:
    found = {w.text.strip(".,").lower() for w in engine.recognize(to_gray(flatten_illumination(image)))}
    return sum(w.lower() in found for w in set(WORDS)) / len(set(WORDS))


class FingerTest(unittest.TestCase):
    def setUp(self):
        page = make_page().astype(np.int16)
        page += np.array([-25, -5, 0], np.int16)  # aged, yellowish paper must not count as skin
        self.page = np.clip(page, 0, 255).astype(np.uint8)

    def test_removes_finger_at_border(self):
        img = self.page.copy()
        h, w = img.shape[:2]
        rng = np.random.default_rng(1)
        finger = np.zeros((h, w), np.uint8)
        cv2.ellipse(finger, (w - 40, h // 2), (160, 55), 0, 0, 360, 255, -1)
        skin = np.clip(np.array([115, 145, 205]) + rng.normal(0, 8, (h, w, 3)), 0, 255).astype(np.uint8)
        img[finger > 0] = skin[finger > 0]

        out, share = remove_fingers(img)
        self.assertGreater(share, 0.005)
        diff = np.abs(out[finger > 0].astype(int) - self.page[finger > 0].astype(int)).mean()
        self.assertLess(diff, 25, "finger area should look like paper again")

    def test_leaves_page_without_fingers_alone(self):
        out, share = remove_fingers(self.page)
        self.assertEqual(share, 0.0)
        self.assertTrue(np.array_equal(out, self.page))


class DeblurTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = TesseractEngine("eng")

    def test_sharp_page_is_not_touched(self):
        page = small_page()
        self.assertLess(estimate_blur(page), BLUR_SIGMA)
        self.assertIs(deblur(page)[0], page)

    def test_blurry_page_becomes_readable(self):
        blurred = cv2.GaussianBlur(small_page(), (0, 0), 3.0)
        out, sigma = deblur(blurred)
        self.assertGreater(sigma, BLUR_SIGMA + 0.2)  # clearly classified as blurry
        self.assertLess(estimate_blur(out), sigma)
        self.assertGreater(recall(self.engine, out), recall(self.engine, blurred) + 0.2)


class HandwritingTest(unittest.TestCase):
    def test_removes_pen_and_pencil_keeps_print(self):
        page = make_page()
        marked = page.copy()
        h, w = page.shape[:2]
        cv2.line(marked, (100, 1000), (1100, 1100), (200, 60, 20), 5)  # blue ballpoint
        cv2.putText(marked, "ok!", (900, 1300), cv2.FONT_HERSHEY_SCRIPT_SIMPLEX, 3, (30, 30, 210), 6)  # red pen
        cv2.line(marked, (150, 1500), (1000, 1450), (165, 165, 165), 4)  # pencil
        cv2.line(marked, (120, 300), (1100, 330), (170, 170, 170), 3)  # pencil underline through the text
        flat = flatten_illumination(marked)
        clean, share = remove_handwriting(flat)
        self.assertGreater(share, 0.002)

        marks = np.any(marked != page, axis=2)
        self.assertGreater(to_gray(clean)[marks].mean(), 235, "marks should be gone")
        print_ink = to_gray(flatten_illumination(page)) < 100
        kept = (to_gray(clean)[print_ink] < 128).mean()
        self.assertGreater(kept, 0.97, "black print must survive")


class LevelsTest(unittest.TestCase):
    def test_paper_next_to_text_is_white(self):
        page = small_page()
        page = cv2.GaussianBlur(page, (0, 0), 1.2)  # soft edges leave a grey halo around text
        out = enhance(page, "gray")
        ink = (out < 128).astype(np.uint8)
        rim = cv2.dilate(ink, np.ones((5, 5), np.uint8)) > 0  # anti-aliased glyph edges are legitimately grey
        near = cv2.dilate(ink, np.ones((15, 15), np.uint8)) > 0
        self.assertGreaterEqual(np.median(out[near & ~rim]), 250)


if __name__ == "__main__":
    unittest.main()
