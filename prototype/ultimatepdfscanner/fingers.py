"""Finger removal: skin-coloured regions that reach into the page from its border are
replaced by the surrounding paper (fingers holding a magazine flat)."""
import cv2
import numpy as np

MIN_AREA = 0.0005  # share of the page; smaller skin-coloured specks are ignored
MAX_AREA = 0.15  # larger regions are more likely a coloured page than a finger
# Shape: a finger reaches into the page and is compact. Table edges and the brownish gutter
# shadow are thin strips along the border (measured length/depth 10-63) or sparse specks.
MIN_DEPTH = 0.03  # reach into the page, as share of the page size perpendicular to the border
MAX_ELONGATION = 3.0  # extent along the border / depth into the page
MIN_FILL = 0.35  # area / bounding-box area


def skin_mask(image: np.ndarray) -> np.ndarray:
    """Skin colour in YCrCb (robust to brightness). Aged, yellowish paper has Cr <= ~135."""
    ycc = cv2.cvtColor(image, cv2.COLOR_BGR2YCrCb)
    y, cr, cb = cv2.split(ycc)
    mask = ((cr >= 140) & (cr <= 180) & (cb >= 77) & (cb <= 127) & (y > 50)).astype(np.uint8) * 255
    k = max(3, (min(image.shape[:2]) // 200) | 1)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((k, k), np.uint8))
    return cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3 * k, 3 * k), np.uint8))


def _looks_like_finger(region: np.ndarray, stats: np.ndarray, border: int) -> bool:
    """region: boolean mask of one component. Judged from the border it rests on most."""
    h, w = region.shape
    x, y, bw, bh, area = stats
    if not MIN_AREA * h * w <= area <= MAX_AREA * h * w or area < MIN_FILL * bw * bh:
        return False
    # contact length with each border -> (contact, depth into the page, extent along it, page size across)
    sides = [(region[:, :border].any(axis=1).sum(), bw, bh, w), (region[:, -border:].any(axis=1).sum(), bw, bh, w),
             (region[:border].any(axis=0).sum(), bh, bw, h), (region[-border:].any(axis=0).sum(), bh, bw, h)]
    contact, depth, along, size = max(sides, key=lambda s: s[0])
    return contact > 0 and depth >= MIN_DEPTH * size and along <= MAX_ELONGATION * depth


def finger_mask(image: np.ndarray) -> np.ndarray:
    """Skin regions of plausible size and shape that reach in from the page border."""
    h, w = image.shape[:2]
    n, labels, stats, _ = cv2.connectedComponentsWithStats(skin_mask(image))
    out = np.zeros((h, w), np.uint8)
    border = max(2, min(h, w) // 100)
    for i in range(1, n):
        if stats[i][4] < MIN_AREA * h * w:
            continue  # cheap reject before building the component mask
        region = labels == i
        if _looks_like_finger(region, stats[i], border):
            out[region] = 255
    return out


def remove_fingers(image: np.ndarray) -> tuple[np.ndarray, float]:
    """Returns (cleaned image, removed share of the page)."""
    if image.ndim != 3:
        return image, 0.0
    mask = finger_mask(image)
    share = float(np.count_nonzero(mask)) / mask.size
    if share == 0:
        return image, 0.0
    k = max(5, (min(image.shape[:2]) // 150) | 1)
    mask = cv2.dilate(mask, np.ones((k, k), np.uint8))  # include the finger's shadow/outline
    # inpaint on a reduced copy (fast for large regions), then paste only the masked pixels
    scale = min(1.0, 800 / max(image.shape[:2]))
    small = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    small_mask = cv2.resize(mask, (small.shape[1], small.shape[0]), interpolation=cv2.INTER_NEAREST)
    filled = cv2.inpaint(small, small_mask, 5, cv2.INPAINT_TELEA)
    filled = cv2.resize(filled, (image.shape[1], image.shape[0]), interpolation=cv2.INTER_LINEAR)
    out = image.copy()
    out[mask > 0] = filled[mask > 0]
    return out, share
