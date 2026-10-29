"""Page detection: segment the document from the background and find its four corners."""
import cv2
import numpy as np

WORK_SIZE = 600  # longer side of the downscaled working image
MIN_AREA_RATIO = 0.1
MAX_AREA_RATIO = 0.97
GRABCUT_SEED = 1234  # same value in the Kotlin port


def order_quad(pts: np.ndarray) -> np.ndarray:
    """Return corners ordered tl, tr, br, bl."""
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    s = pts.sum(axis=1)
    d = np.diff(pts, axis=1).ravel()
    return np.array([pts[np.argmin(s)], pts[np.argmin(d)], pts[np.argmax(s)], pts[np.argmax(d)]], dtype=np.float32)


def segment_document(small: np.ndarray, iterations: int = 4) -> np.ndarray:
    """GrabCut foreground mask. The background model is learned from a thin frame at the
    image border, which copes with textured, paper-coloured tables where thresholds fail."""
    h, w = small.shape[:2]
    gc = np.zeros((h, w), np.uint8)
    bgd, fgd = np.zeros((1, 65)), np.zeros((1, 65))
    rect = (int(w * 0.03), int(h * 0.03), int(w * 0.94), int(h * 0.94))
    cv2.setRNGSeed(GRABCUT_SEED)  # GrabCut's k-means is random: same photo -> same mask, regardless of earlier photos
    cv2.grabCut(small, gc, rect, bgd, fgd, iterations, cv2.GC_INIT_WITH_RECT)
    mask = np.where((gc == cv2.GC_FGD) | (gc == cv2.GC_PR_FGD), 255, 0).astype(np.uint8)
    k = max(3, (min(h, w) // 60) | 1)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((k, k), np.uint8))
    return cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((k, k), np.uint8))


def _largest_component(mask: np.ndarray) -> np.ndarray | None:
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return None
    cnt = max(contours, key=cv2.contourArea)
    out = np.zeros_like(mask)
    cv2.drawContours(out, [cnt], -1, 255, cv2.FILLED)
    return out


def quad_from_mask(mask: np.ndarray) -> np.ndarray | None:
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return None
    hull = cv2.convexHull(max(contours, key=cv2.contourArea))
    peri = cv2.arcLength(hull, True)
    for eps in (0.02, 0.03, 0.05):
        approx = cv2.approxPolyDP(hull, eps * peri, True)
        if len(approx) == 4:
            return order_quad(approx)
    # bulging edges (open books): take the points extremal along both diagonals
    pts = hull.reshape(-1, 2).astype(np.float32)
    s, d = pts.sum(axis=1), np.diff(pts, axis=1).ravel()
    return np.array([pts[np.argmin(s)], pts[np.argmin(d)], pts[np.argmax(s)], pts[np.argmax(d)]], dtype=np.float32)


def page_component(small: np.ndarray, seg: np.ndarray) -> np.ndarray | None:
    """The page as the largest foreground component, after removing skin- and wood-coloured
    pixels: a hand touching the page or a lit wooden table otherwise merges with it and drags a
    corner away (measured: paper Cr ~128 with <= 1 % 'skin', table/hand Cr 142-147 with 52-81 %).
    If that removes too much (e.g. an orange cover), the unfiltered component is used."""
    from .fingers import skin_mask

    full = _largest_component(seg)
    if full is None:
        return None
    fg = cv2.bitwise_and(seg, cv2.bitwise_not(skin_mask(small)))
    k = max(3, (min(small.shape[:2]) // 60) | 1)
    fg = cv2.morphologyEx(fg, cv2.MORPH_OPEN, np.ones((k, k), np.uint8))  # cut thin bridges left between page and hand
    filtered = _largest_component(fg)
    if filtered is not None and cv2.countNonZero(filtered) >= 0.5 * cv2.countNonZero(full):
        return filtered
    return full


PROTRUSION_SIZE = 0.3  # opening kernel as share of sqrt(page area): narrower attachments are cut off
CLIP_MARGIN = 0.04  # the mask is kept within the quad grown by this share (room for bulging book edges)


def without_protrusions(mask: np.ndarray) -> np.ndarray:
    """For corner finding only: an opening with a kernel of ~30 % of the page size removes
    attachments narrower than that (lit table patches, fingers) while the page survives. Its
    rounded corners do not matter because refine_quad refits the sides on the full mask."""
    k = max(3, int(PROTRUSION_SIZE * np.sqrt(cv2.countNonZero(mask))) | 1)
    main = _largest_component(cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((k, k), np.uint8)))
    if main is None or cv2.countNonZero(main) < 0.5 * cv2.countNonZero(mask):
        return mask
    return main


def clip_to_quad(mask: np.ndarray, quad: np.ndarray) -> np.ndarray:
    """Mask restricted to the quad grown by CLIP_MARGIN around its centre."""
    c = quad.mean(axis=0)
    grown = c + (quad - c) * (1 + 2 * CLIP_MARGIN)
    poly = np.zeros_like(mask)
    cv2.fillConvexPoly(poly, np.round(grown).astype(np.int32), 255)
    return cv2.bitwise_and(mask, poly)


RANSAC_TOL = 0.006  # inlier distance as share of the working image size (~3.6 px at 600 px)


def _ransac_lines(pts: np.ndarray, tol: float, n_lines: int = 6, iterations: int = 400) -> list:
    """Sequential RANSAC: the n longest straight runs of contour points as (x0, y0, dx, dy, inliers)."""
    rng = np.random.default_rng(GRABCUT_SEED)
    remaining = pts.copy()
    lines = []
    for _ in range(n_lines):
        if len(remaining) < 20:
            break
        best, best_count = None, 0
        for _ in range(iterations):
            a, b = remaining[rng.choice(len(remaining), 2, replace=False)]
            d = b - a
            length = np.hypot(*d)
            if length < 5 * tol:
                continue
            d = d / length
            dist = np.abs((remaining[:, 0] - a[0]) * d[1] - (remaining[:, 1] - a[1]) * d[0])
            count = int((dist < tol).sum())
            if count > best_count:
                best, best_count = (a, d), count
        if best is None or best_count < 20:
            break
        a, d = best
        dist = np.abs((remaining[:, 0] - a[0]) * d[1] - (remaining[:, 1] - a[1]) * d[0])
        inl = remaining[dist < tol]
        vx, vy, x0, y0 = cv2.fitLine(inl.astype(np.float32), cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
        lines.append((float(x0), float(y0), float(vx), float(vy), len(inl)))
        dist = np.abs((remaining[:, 0] - x0) * vy - (remaining[:, 1] - y0) * vx)
        remaining = remaining[dist >= 2 * tol]
    return lines


def ransac_quad(mask: np.ndarray) -> np.ndarray | None:
    """Corners from the four dominant straight edges of the mask outline: page edges are the
    longest straight runs, while attachments (hands, lit table patches) are short and irregular.
    Picks two roughly parallel pairs of lines with the most inliers whose quad covers the mask."""
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return None
    pts = max(contours, key=cv2.contourArea).reshape(-1, 2).astype(np.float64)
    lines = _ransac_lines(pts, RANSAC_TOL * max(mask.shape))
    if len(lines) < 4:
        return None
    area = cv2.countNonZero(mask)

    def angle(l):
        return np.degrees(np.arctan2(l[3], l[2])) % 180

    def angle_diff(a, b):
        d = abs(a - b) % 180
        return min(d, 180 - d)

    best, best_score = None, 0
    import itertools
    for combo in itertools.combinations(range(len(lines)), 4):
        ls = [lines[i] for i in combo]
        # split into two pairs: try the three pairings
        for (i, j), (k, m) in (((0, 1), (2, 3)), ((0, 2), (1, 3)), ((0, 3), (1, 2))):
            p1, p2 = (ls[i], ls[j]), (ls[k], ls[m])
            if angle_diff(angle(p1[0]), angle(p1[1])) > 25 or angle_diff(angle(p2[0]), angle(p2[1])) > 25:
                continue
            if angle_diff(angle(p1[0]), angle(p2[0])) < 45:
                continue
            corners = [_intersect(a, b) for a in p1 for b in p2]
            if any(c is None for c in corners):
                continue
            quad = order_quad(np.array(corners))
            if not cv2.isContourConvex(quad.astype(np.float32).reshape(-1, 1, 2)):
                continue
            qarea = cv2.contourArea(quad)
            if not 0.6 * area < qarea < 1.6 * area:
                continue
            score = sum(l[4] for l in ls)
            if score > best_score:
                best, best_score = quad, score
    return best


def _intersect(l1, l2):
    """Intersection of two lines given as (x0, y0, dx, dy); None if (nearly) parallel."""
    den = l1[2] * l2[3] - l1[3] * l2[2]
    if abs(den) < 1e-9:
        return None
    t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / den
    return np.array([l1[0] + t * l1[2], l1[1] + t * l1[3]], np.float32)


def refine_quad(quad: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Refit each side as a robust (Huber) line through the contour points near it, ignoring the
    corner regions, so protrusions further than the tolerance do not pull the edge. Corners are
    the intersections of adjacent lines; falls back to `quad` if a corner would move implausibly far."""
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not contours:
        return quad
    pts = max(contours, key=cv2.contourArea).reshape(-1, 2).astype(np.float64)
    perimeter = sum(np.linalg.norm(quad[(k + 1) % 4] - quad[k]) for k in range(4))
    tol = 0.02 * perimeter
    lines = []
    for k in range(4):
        a, b = quad[k].astype(np.float64), quad[(k + 1) % 4].astype(np.float64)
        length = np.linalg.norm(b - a)
        d = (b - a) / length
        t = ((pts - a) @ d) / length
        dist = np.abs((pts[:, 0] - a[0]) * d[1] - (pts[:, 1] - a[1]) * d[0])
        near = pts[(t >= 0.1) & (t <= 0.9) & (dist < tol)]
        if len(near) < 10:
            lines.append((a[0], a[1], d[0], d[1]))
        else:
            vx, vy, x0, y0 = cv2.fitLine(near.astype(np.float32), cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
            lines.append((x0, y0, vx, vy))
    refined = []
    for k in range(4):
        p = _intersect(lines[(k + 3) % 4], lines[k])
        if p is None:
            return quad
        refined.append(p)
    refined = np.array(refined, np.float32)
    if np.any(np.linalg.norm(refined - quad, axis=1) > 0.1 * perimeter):
        return quad
    return refined


def _edge_quad(gray: np.ndarray) -> np.ndarray | None:
    """Fallback for plain backgrounds: Canny edges, biggest 4-corner contour."""
    edges = cv2.Canny(cv2.GaussianBlur(gray, (5, 5), 0), 50, 150)
    edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8), iterations=2)
    min_area = MIN_AREA_RATIO * gray.size
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for cnt in sorted(contours, key=cv2.contourArea, reverse=True)[:5]:
        hull = cv2.convexHull(cnt)
        if cv2.contourArea(hull) < min_area:
            break
        approx = cv2.approxPolyDP(hull, 0.02 * cv2.arcLength(hull, True), True)
        if len(approx) == 4:
            return order_quad(approx)
    return None


def detect_document(image: np.ndarray) -> tuple[np.ndarray | None, np.ndarray | None]:
    """Return (corners tl,tr,br,bl in image coordinates, full-resolution foreground mask)."""
    h, w = image.shape[:2]
    scale = min(1.0, WORK_SIZE / max(h, w))
    small = cv2.resize(image, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    small = cv2.GaussianBlur(small, (5, 5), 0)

    mask = page_component(small, segment_document(small))
    if mask is not None and MIN_AREA_RATIO < cv2.countNonZero(mask) / mask.size < MAX_AREA_RATIO:
        quad = ransac_quad(mask)
        if quad is None:
            quad = quad_from_mask(without_protrusions(mask))
        quad = refine_quad(quad, mask)
        full_mask = cv2.resize(clip_to_quad(mask, quad), (w, h), interpolation=cv2.INTER_LINEAR)
        return quad / scale, (full_mask > 127).astype(np.uint8) * 255
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    quad = _edge_quad(gray)
    return (quad / scale if quad is not None else None), None


def detect_page(image: np.ndarray) -> np.ndarray | None:
    """Return the page corners (tl, tr, br, bl) in image coordinates, or None."""
    return detect_document(image)[0]
