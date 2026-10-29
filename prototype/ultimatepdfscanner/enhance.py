"""Image enhancement: shadow/illumination removal, white/black point and output colour modes."""
import cv2
import numpy as np

MODES = ("auto", "color", "gray", "bw", "none")
PAPER_WHITE_PERCENTILE = 30  # this share of the paper pixels (incl. the grey halo next to text) still ends up pure white
COLORFUL_MIN_SHARE = 0.04  # "auto": pages with at least this share of clearly coloured pixels stay in colour


def _background(plane: np.ndarray) -> np.ndarray:
    """Estimate the paper illumination: a closing removes text strokes but, unlike a plain
    dilation, keeps the edge of large dark areas such as the phone's shadow in place; the
    median then smooths without blurring that edge (a dilation left a grey band of ~224)."""
    k = max(7, (min(plane.shape[:2]) // 100) | 1)
    bg = cv2.morphologyEx(plane, cv2.MORPH_CLOSE, np.ones((k, k), np.uint8))
    return cv2.medianBlur(bg, min(255, k * 3 | 1))


def flatten_illumination(image: np.ndarray) -> np.ndarray:
    """Divide each channel by its estimated background so the paper becomes uniformly white."""
    planes = cv2.split(image) if image.ndim == 3 else [image]
    out = [cv2.divide(p, _background(p), scale=255) for p in planes]
    return cv2.merge(out) if len(out) > 1 else out[0]


def to_gray(image: np.ndarray) -> np.ndarray:
    return cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image


def levels(gray: np.ndarray) -> tuple[float, float]:
    """Black and white point for a flattened page.

    Paper next to text stays slightly grey after flattening (ink spread, blur, paper grain);
    the white point is put below most paper pixels so the background becomes clean white."""
    t, _ = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    paper, ink = gray[gray > t], gray[gray <= t]
    if paper.size == 0 or ink.size == 0:
        return 0.0, 255.0
    white = float(np.percentile(paper, PAPER_WHITE_PERCENTILE))
    black = float(np.percentile(ink, 5))
    if white - black < 30:
        return 0.0, 255.0
    return black, white


def apply_levels(image: np.ndarray, black: float, white: float) -> np.ndarray:
    lut = np.clip((np.arange(256, dtype=np.float32) - black) / (white - black) * 255, 0, 255).astype(np.uint8)
    return cv2.LUT(image, lut)


def is_colorful(image: np.ndarray) -> bool:
    """True if a noticeable part of the (flattened) page is clearly coloured, e.g. a cover."""
    if image.ndim == 2:
        return False
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    colored = (hsv[..., 1] > 80) & (hsv[..., 2] > 60)
    return float(colored.mean()) >= COLORFUL_MIN_SHARE


def color_page(image: np.ndarray) -> np.ndarray:
    """Colour pages such as covers: global white balance + contrast stretch per channel.

    Local flattening would turn large coloured areas white (they are their own
    'background'), so colourful pages only get this global correction."""
    out = []
    for plane in cv2.split(image):
        lo, hi = np.percentile(plane, (0.5, 99.5))
        out.append(apply_levels(plane, float(lo), float(max(hi, lo + 30))))
    return cv2.merge(out)


def enhance(image: np.ndarray, mode: str = "color") -> np.ndarray:
    if mode not in MODES:
        raise ValueError(f"unknown enhance mode {mode!r}, expected one of {MODES}")
    if mode == "none":
        return image
    return finish(flatten_illumination(image), mode, image)


def finish(flat: np.ndarray, mode: str, original: np.ndarray | None = None) -> np.ndarray:
    """Output mode for an already flattened page (lets cleaning steps run in between).
    `original` is the unflattened page; colourful pages are rendered from it."""
    gray = to_gray(flat)
    if mode == "bw":
        _, bw = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        return bw
    if mode in ("color", "auto") and original is not None and original.ndim == 3 and is_colorful(original):
        return color_page(original)
    black, white = levels(gray)
    if mode == "color":
        return apply_levels(flat, black, white)
    return apply_levels(gray, black, white)
