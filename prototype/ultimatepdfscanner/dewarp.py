"""Curved book pages: straighten a page between its two curved side edges.

Works on a page whose fold is horizontal (as produced by spread.split_spread). The
side edges, which run from the outer edge to the fold, bend like the text lines do.
Every row is resampled between the two fitted edge curves, and rows are spaced by
the edges' arc length so the compressed area near the fold is stretched back.
Pure OpenCV/numpy, so it ports directly to Android.
"""
import cv2
import numpy as np

from .enhance import flatten_illumination

POLY_DEGREE = 4
MIN_ROW_FILL = 0.5  # rows with less mask than this fraction of the median width are outside the page


def _robust_polyfit(y: np.ndarray, x: np.ndarray, degree: int, rounds: int = 3) -> np.poly1d:
    keep = np.ones(len(y), bool)
    poly = np.poly1d(np.polyfit(y, x, degree))
    for _ in range(rounds):
        res = np.abs(x - poly(y))
        mad = np.median(res[keep]) + 1e-6
        keep = res < 3 * 1.4826 * mad + 2
        poly = np.poly1d(np.polyfit(y[keep], x[keep], degree))
    return poly


def page_edges(mask: np.ndarray) -> tuple[int, int, np.poly1d, np.poly1d] | None:
    """Row range of the page and polynomials x = left(y), x = right(y) of its side edges."""
    m = mask > 0
    fill = m.sum(axis=1)
    rows = np.flatnonzero(fill >= MIN_ROW_FILL * np.median(fill[fill > 0])) if fill.any() else []
    if len(rows) < 20:
        return None
    y0, y1 = int(rows[0]), int(rows[-1])
    ys = np.arange(y0, y1 + 1)
    sub = m[y0:y1 + 1]
    has = sub.any(axis=1)
    left = np.argmax(sub, axis=1)
    right = sub.shape[1] - 1 - np.argmax(sub[:, ::-1], axis=1)
    ys, left, right = ys[has], left[has], right[has]
    deg = min(POLY_DEGREE, max(1, len(ys) // 50))
    return y0, y1, _robust_polyfit(ys, left.astype(float), deg), _robust_polyfit(ys, right.astype(float), deg)


def dewarp_page(image: np.ndarray, mask: np.ndarray | None) -> np.ndarray:
    """Return the straightened page; without a usable mask the image is returned unchanged."""
    if mask is None or (edges := page_edges(mask)) is None:
        return image
    y0, y1, left, right = edges
    ys = np.arange(y0, y1 + 1, dtype=np.float64)
    lx, rx = left(ys), right(ys)
    width = int(round(np.median(rx - lx)))
    if width < 20:
        return image

    # arc length along both side edges -> output row spacing
    dl, dr = np.gradient(lx), np.gradient(rx)
    step = 0.5 * (np.sqrt(1 + dl ** 2) + np.sqrt(1 + dr ** 2))
    s = np.concatenate([[0.0], np.cumsum(step[1:])])
    height = int(round(s[-1])) + 1
    src_y = np.interp(np.arange(height), s, ys)
    src_l, src_r = left(src_y), right(src_y)

    u = np.linspace(0.0, 1.0, width)
    map_x = (src_l[:, None] + (src_r - src_l)[:, None] * u[None, :]).astype(np.float32)
    map_y = np.repeat(src_y[:, None], width, axis=1).astype(np.float32)
    return cv2.remap(image, map_x, map_y, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


# --- second stage: straighten the text lines themselves -------------------------------
# Text lines run perpendicular to the fold, i.e. vertically in this frame. The page is cut
# into tiles; each tile's ink profile across x is aligned to its neighbour in y, the shifts
# are chained outwards from the middle row and a smooth surface d(x, y) is fitted to them.

GRID = (6, 28)  # tiles across x, strips along y
SHIFT_DEG = (2, 5)  # polynomial degree of the correction surface in x and y


def _ink(gray: np.ndarray) -> np.ndarray:
    # flatten first: a global threshold would count gutter shadow as ink
    _, ink = cv2.threshold(flatten_illumination(gray), 0, 1, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    return ink.astype(np.float32)


def _line_period(profile: np.ndarray) -> float | None:
    p = profile - profile.mean()
    ac = np.correlate(p, p, "full")[len(p) - 1:]
    if ac[0] <= 0:
        return None
    ac /= ac[0]
    lag = np.flatnonzero((ac[1:-1] > ac[:-2]) & (ac[1:-1] >= ac[2:]) & (ac[1:-1] > 0.2)) + 1
    return float(lag[0]) if len(lag) else None


def _shift(a: np.ndarray, b: np.ndarray, max_shift: int) -> tuple[float, float]:
    """Sub-pixel shift s with b(x) ~ a(x - s), and the normalised correlation at s."""
    a = (a - a.mean()) / (a.std() + 1e-6)
    b = (b - b.mean()) / (b.std() + 1e-6)
    n = len(a)
    scores = np.array([np.dot(a[max(0, -s):n - max(0, s)], b[max(0, s):n - max(0, -s)]) / (n - abs(s))
                       for s in range(-max_shift, max_shift + 1)])
    i = int(np.argmax(scores))
    off = 0.0
    if 0 < i < len(scores) - 1:
        den = scores[i - 1] - 2 * scores[i] + scores[i + 1]
        off = 0.5 * (scores[i - 1] - scores[i + 1]) / den if den else 0.0
    return i - max_shift + off, float(scores[i])


def straighten_text_lines(image: np.ndarray) -> np.ndarray:
    """Remove the remaining bend of (vertical) text lines; returns the image unchanged if
    there is not enough regular text to measure."""
    gray = image if image.ndim == 2 else cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    ink = _ink(gray)
    h, w = ink.shape
    nx, ny = GRID
    xs = np.linspace(0, w, nx + 1).astype(int)
    ys = np.linspace(0, h, ny + 1).astype(int)
    profiles = [[ink[ys[j]:ys[j + 1], xs[i]:xs[i + 1]].sum(axis=0) for i in range(nx)] for j in range(ny)]

    period = _line_period(ink[h // 3:2 * h // 3].sum(axis=0))
    if period is None or period < 4:
        return image
    max_step = max(2, int(0.4 * period))

    pts, vals, weights = [], [], []
    mid = ny // 2
    for i in range(nx):
        cx = 0.5 * (xs[i] + xs[i + 1])
        for direction in (-1, 1):
            acc = 0.0
            j = mid
            while 0 <= j + direction < ny:
                nxt = j + direction
                a, b = profiles[j][i], profiles[nxt][i]
                if a.std() < 1e-3 or b.std() < 1e-3:
                    break  # blank strip: stop chaining, the error would accumulate
                s, corr = _shift(a, b, max_step)
                if corr < 0.3:
                    break
                acc += s
                pts.append((cx, 0.5 * (ys[nxt] + ys[nxt + 1])))
                vals.append(acc)
                weights.append(corr)
                j = nxt
        pts.append((cx, 0.5 * (ys[mid] + ys[mid + 1])))
        vals.append(0.0)
        weights.append(1.0)

    pts = np.array(pts)
    measured = len(vals) - nx  # minus the zero anchors of the middle row
    # pages with little text: lower the degrees to what the measured tiles can support
    deg_x = min(SHIFT_DEG[0], len({x for (x, _), v in zip(pts, vals) if v != 0.0}) - 1)
    deg_y = min(SHIFT_DEG[1], measured // (2 * max(1, deg_x + 1)) - 1)
    if deg_x < 0 or deg_y < 1:
        return image
    X, Y = pts[:, 0] / w, pts[:, 1] / h
    terms = [(p, q) for p in range(deg_x + 1) for q in range(deg_y + 1)]
    A = np.stack([X ** p * Y ** q for p, q in terms], axis=1)
    sw = np.sqrt(np.array(weights))
    coef, *_ = np.linalg.lstsq(A * sw[:, None], np.array(vals) * sw, rcond=None)

    gx, gy = np.meshgrid(np.arange(w, dtype=np.float32) / w, np.arange(h, dtype=np.float32) / h)
    d = sum(c * gx ** p * gy ** q for c, (p, q) in zip(coef, terms))
    map_x = (np.arange(w, dtype=np.float32)[None, :] + d).astype(np.float32)
    map_y = np.repeat(np.arange(h, dtype=np.float32)[:, None], w, axis=1)
    return cv2.remap(image, map_x, map_y, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
