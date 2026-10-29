"""Two-page spreads: find the book fold (gutter) and split into single pages."""
from dataclasses import dataclass

import cv2
import numpy as np

BAND = (0.4, 0.6)  # both pages have the same size, so the fold lies near the middle (measured 0.47-0.49)
NEAR = 0.08  # both flanks of the gutter are compared within this fraction of the length
MIN_DIP = 0.10  # relative darkening of the paper at the gutter (measured: covers <= 0.065, spreads 0.15-0.93; flat spreads are the weak ones)


@dataclass
class Fold:
    axis: int  # 0: fold is a horizontal line (pages stacked vertically), 1: vertical line
    pos: int  # row (axis 0) or column (axis 1) of the fold
    dip: float  # paper darkening at the fold relative to the brighter-than-fold flanks on BOTH sides

    @property
    def confident(self) -> bool:
        return self.dip >= MIN_DIP


def _paper_brightness(gray: np.ndarray) -> np.ndarray:
    """Paper brightness without the text: dilation removes dark strokes."""
    k = max(9, (min(gray.shape) // 40) | 1)
    return cv2.medianBlur(cv2.dilate(gray, np.ones((k, k), np.uint8)), 5)


def _smooth(profile: np.ndarray) -> np.ndarray:
    k = max(5, (len(profile) // 100) | 1)
    return cv2.GaussianBlur(profile.astype(np.float32).reshape(-1, 1), (1, k), 0).ravel()


def _two_sided_dip(profile: np.ndarray) -> tuple[int, float]:
    """Deepest valley in the central band; a step edge has one low flank and scores ~0."""
    n = len(profile)
    a, b, w = int(n * BAND[0]), int(n * BAND[1]), max(1, int(n * NEAR))
    best = (a, 0.0)
    for pos in range(a, b):
        flank = min(profile[pos - w:pos].max(), profile[pos + 1:pos + 1 + w].max())
        dip = (flank - profile[pos]) / max(flank, 1e-6)
        if dip > best[1]:
            best = (pos, float(dip))
    return best


def _row_means(p: np.ndarray, m: np.ndarray) -> np.ndarray:
    counts = m.sum(axis=1)
    return _smooth(np.where(counts > 0, (p * m).sum(axis=1) / np.maximum(counts, 1), 0))


def _dip_at(profile: np.ndarray, pos: int) -> float:
    n = len(profile)
    w, r = max(1, int(n * NEAR)), max(1, n // 100)
    lo = profile[max(0, pos - r):pos + r + 1].min()
    flank = min(profile[max(0, pos - w):pos].max(), profile[pos + 1:pos + 1 + w].max())
    return float((flank - lo) / max(flank, 1e-6))


def find_fold(gray: np.ndarray, mask: np.ndarray | None = None, strips: int = 5) -> Fold:
    """Best fold candidate over both axes (check .confident before splitting).

    A gutter darkens the paper along its whole length, so the dip is re-measured in
    `strips` parallel strips and the median is kept; printed bands only cover a part."""
    paper = _paper_brightness(gray).astype(np.float32)
    inside = (mask > 0) if mask is not None else np.ones(gray.shape, bool)
    candidates = []
    for axis in (0, 1):
        p, m = (paper, inside) if axis == 0 else (paper.T, inside.T)
        pos, _ = _two_sided_dip(_row_means(p, m))
        edges = np.linspace(0, p.shape[1], strips + 1).astype(int)
        dips = [_dip_at(_row_means(p[:, a:b], m[:, a:b]), pos) for a, b in zip(edges[:-1], edges[1:])]
        candidates.append(Fold(axis, pos, float(np.median(dips))))
    return max(candidates, key=lambda f: f.dip)


def split_spread(image: np.ndarray, mask: np.ndarray | None, fold: Fold):
    """Cut at the fold. Returns [(page_image, page_mask), ...] for the two halves, rotated so
    that the fold is horizontal; the final reading orientation is fixed later by OSD."""
    if fold.axis == 1:
        image = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
        mask = cv2.rotate(mask, cv2.ROTATE_90_CLOCKWISE) if mask is not None else None
    p = fold.pos
    return [(image[:p], mask[:p] if mask is not None else None),
            (image[p:], mask[p:] if mask is not None else None)]
