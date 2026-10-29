"""Perspective correction: map the detected quad onto an upright rectangle."""
import cv2
import numpy as np


DEFAULT_FOCAL = 0.55  # focal length / image diagonal of a typical phone main camera (23-26 mm equivalent)


def focal_from_35mm(f35: float | None) -> float:
    """35 mm-equivalent focal length (EXIF FocalLengthIn35mmFilm) -> focal length / image diagonal."""
    return f35 / 43.27 if f35 and f35 > 0 else DEFAULT_FOCAL


def page_aspect_ratio(quad: np.ndarray, image_size: tuple[int, int], focal: float = DEFAULT_FOCAL) -> float | None:
    """True width/height ratio of the photographed rectangle.

    Zhang & He, "Whiteboard scanning and image enhancement" (2007), square pixels, principal
    point at the image centre. The focal length is given (`focal` = f / image diagonal), not
    estimated: estimating it from the four corners is ill-conditioned for the usual near
    top-down shots (measured: f came out 3-7x the diagonal or negative, ratios off by 20 %),
    while with weak perspective an approximate focal length barely affects the result.
    """
    tl, tr, br, bl = quad.astype(np.float64)
    m1, m2, m3, m4 = (np.append(p, 1.0) for p in (tl, tr, bl, br))
    k2 = np.dot(np.cross(m1, m4), m3) / np.dot(np.cross(m2, m4), m3)
    k3 = np.dot(np.cross(m1, m4), m2) / np.dot(np.cross(m3, m4), m2)
    n2 = k2 * m2 - m1
    n3 = k3 * m3 - m1
    w, h = image_size
    u0, v0 = w / 2.0, h / 2.0

    if abs(n2[2]) < 1e-9 or abs(n3[2]) < 1e-9:  # fronto-parallel: no perspective to undo
        return float(np.hypot(n2[0], n2[1]) / np.hypot(n3[0], n3[1]))
    f = focal * np.hypot(w, h)
    a_inv = np.linalg.inv(np.array([[f, 0, u0], [0, f, v0], [0, 0, 1.0]]))
    b = a_inv.T @ a_inv
    ratio2 = (n2 @ b @ n2) / (n3 @ b @ n3)
    return float(np.sqrt(ratio2)) if ratio2 > 0 else None


def perspective_transform(quad: np.ndarray, image_size: tuple[int, int],
                          margin: float = 0.0, focal: float = DEFAULT_FOCAL) -> tuple[np.ndarray, tuple[int, int]]:
    """Homography from the quad to an upright rectangle, and the output (width, height).

    `margin` (fraction of each side) pads the output so that edges bulging out of the
    quad – curved book pages – stay inside the image."""
    tl, tr, br, bl = quad.astype(np.float32)
    width = max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl))
    height = max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr))
    ratio = page_aspect_ratio(quad, image_size, focal)
    if ratio is not None and 0.2 < ratio < 5:
        height = width / ratio  # keep the resolution of the wider edge, fix the proportions
    width, height = round(width), round(height)
    mx, my = round(width * margin), round(height * margin)
    dst = np.array([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]], dtype=np.float32) + [mx, my]
    return cv2.getPerspectiveTransform(quad.astype(np.float32), dst.astype(np.float32)), (width + 2 * mx, height + 2 * my)


def warp_quad(image: np.ndarray, quad: np.ndarray, mask: np.ndarray | None = None, margin: float = 0.0,
              focal: float = DEFAULT_FOCAL):
    """Rectify the quad. With a mask, returns (image, mask) warped by the same homography."""
    m, size = perspective_transform(quad, (image.shape[1], image.shape[0]), margin, focal)
    out = cv2.warpPerspective(image, m, size, flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    if mask is None:
        return out
    return out, cv2.warpPerspective(mask, m, size, flags=cv2.INTER_NEAREST, borderValue=0)
