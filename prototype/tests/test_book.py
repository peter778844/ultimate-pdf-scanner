"""Synthetic tests for the open-book stages: fold detection and text-line straightening."""
import unittest

import cv2
import numpy as np

from ultimatepdfscanner.dewarp import _shift, straighten_text_lines
from ultimatepdfscanner.enhance import flatten_illumination
from ultimatepdfscanner.spread import find_fold, split_spread

from .test_pipeline import make_page


def text_page(full: bool = False) -> np.ndarray:
    page = cv2.cvtColor(make_page(), cv2.COLOR_BGR2GRAY)
    if full:  # repeat the text block so that the whole page is covered
        block = page[120:760]
        page = np.vstack([page[:120]] + [block] * 2 + [page[1400:]])
    return cv2.rotate(page, cv2.ROTATE_90_CLOCKWISE)  # text lines vertical, as next to a horizontal fold


def make_spread(gutter: bool) -> np.ndarray:
    top, bottom = text_page(), text_page()
    spread = np.vstack([top, bottom]).astype(np.float32)
    if gutter:
        y = np.arange(spread.shape[0], dtype=np.float32)
        shade = 1 - 0.45 * np.exp(-((y - top.shape[0]) / 60.0) ** 2)
        spread *= shade[:, None]
    return spread.astype(np.uint8)


def column_offsets(gray: np.ndarray) -> np.ndarray:
    """Shift of each horizontal band's ink profile against the middle band: ~0 if lines are straight."""
    ink = (flatten_illumination(gray) < 128).astype(np.float32)
    rows = np.flatnonzero(ink.sum(axis=1))
    bands = [b.sum(axis=0) for b in np.array_split(ink[rows[0]:rows[-1] + 1], 10, axis=0)]
    ref = bands[len(bands) // 2]
    return np.array([_shift(ref, b, 35)[0] for b in bands if b.std() > 0])


class FoldTest(unittest.TestCase):
    def test_finds_gutter_in_spread(self):
        spread = make_spread(gutter=True)
        fold = find_fold(spread)
        self.assertTrue(fold.confident, fold)
        self.assertEqual(fold.axis, 0)
        self.assertLess(abs(fold.pos - spread.shape[0] // 2), spread.shape[0] * 0.02)
        top, bottom = split_spread(spread, None, fold)
        self.assertEqual(top[0].shape[0] + bottom[0].shape[0], spread.shape[0])

    def test_single_page_is_not_split(self):
        self.assertFalse(find_fold(make_spread(gutter=False)[: text_page().shape[0]]).confident)


class StraightenTest(unittest.TestCase):
    def test_removes_bend(self):
        page = text_page(full=True)
        h, w = page.shape
        y = np.arange(h, dtype=np.float32)
        bend = 40 * np.clip((y / h - 0.5) / 0.5, 0, None) ** 2  # lines curl near the fold (bottom)
        map_x = np.arange(w, dtype=np.float32)[None, :] - bend[:, None]
        map_y = np.repeat(y[:, None], w, axis=1)
        bent = cv2.remap(page, map_x, map_y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)

        before = np.ptp(column_offsets(bent))
        after = np.ptp(column_offsets(straighten_text_lines(bent)))
        self.assertGreater(before, 10)
        self.assertLess(after, before * 0.4, f"bend {before:.1f}px -> {after:.1f}px")


if __name__ == "__main__":
    unittest.main()
