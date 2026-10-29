"""Handwriting removal on a flattened page (paper ~white).

Removes what can be told apart from black print without a learned model:
  * blue / violet / red pen strokes (by hue),
  * pencil: grey strokes whose darkest pixels are clearly lighter than print ink.
Black or dark ballpoint on black print needs a segmentation model and is not handled.
Off by default, because coloured print (headlines, covers) in these hues is removed too.
"""
import cv2
import numpy as np

from .enhance import levels, to_gray

PEN_HUES = ((100, 160), (160, 180), (0, 8))  # OpenCV hue (0-180): blue/violet, red (wraps)
MIN_SATURATION = 70
PENCIL_CORE = 0.45  # a component is pencil if even its darkest pixel is lighter than this (0 = ink, 1 = paper)


def pen_mask(flat: np.ndarray) -> np.ndarray:
    if flat.ndim != 3:
        return np.zeros(flat.shape, np.uint8)
    hsv = cv2.cvtColor(flat, cv2.COLOR_BGR2HSV)
    hue, sat, _ = cv2.split(hsv)
    hue_ok = np.zeros(hue.shape, bool)
    for lo, hi in PEN_HUES:
        hue_ok |= (hue >= lo) & (hue <= hi)
    # no brightness limit: paper is unsaturated anyway, and light ink must still count
    return (hue_ok & (sat >= MIN_SATURATION)).astype(np.uint8) * 255


def pencil_mask(flat: np.ndarray) -> np.ndarray:
    """Mid-grey pixels that are not the anti-aliased rim of dark print.

    Works per pixel, so a pencil line crossing printed text is removed between the letters
    (a component-based test would see the line merged with the dark glyphs)."""
    gray = to_gray(flat)
    black, white = levels(gray)
    span = max(white - black, 1.0)
    rel = (gray.astype(np.float32) - black) / span
    core = (rel <= PENCIL_CORE).astype(np.uint8)
    k = max(3, (min(gray.shape) // 400) | 1)  # rim width of printed glyphs
    print_zone = cv2.dilate(core, np.ones((2 * k + 1, 2 * k + 1), np.uint8)) > 0
    mid = (rel > PENCIL_CORE) & (rel < 0.85) & ~print_zone
    mask = mid.astype(np.uint8)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    keep = stats[:, cv2.CC_STAT_AREA] >= 15  # ignore paper grain specks
    keep[0] = False
    return keep[labels].astype(np.uint8) * 255


def remove_handwriting(flat: np.ndarray) -> tuple[np.ndarray, float]:
    """Returns (cleaned flattened page, removed share of the page)."""
    mask = cv2.bitwise_or(pen_mask(flat), pencil_mask(flat))
    mask = cv2.dilate(mask, np.ones((3, 3), np.uint8))
    share = float(np.count_nonzero(mask)) / mask.size
    out = flat.copy()
    out[mask > 0] = 255
    return out, share
