"""Orchestrates the stages for one or more photos."""
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np

from .deblur import BLUR_SIGMA, deblur
from .detect import detect_document
from .dewarp import dewarp_page, straighten_text_lines
from .enhance import color_page, finish, flatten_illumination, to_gray
from .fingers import remove_fingers
from .handwriting import remove_handwriting
from .ocr import TesseractEngine
from .spread import find_fold, split_spread
from .types import Page
from .warp import DEFAULT_FOCAL, focal_from_35mm, warp_quad

SPREAD_MARGIN = 0.04  # keep bulging page edges inside the rectified spread
_ROTATE = {90: cv2.ROTATE_90_CLOCKWISE, 180: cv2.ROTATE_180, 270: cv2.ROTATE_90_COUNTERCLOCKWISE}


@dataclass
class PipelineConfig:
    detect: bool = True
    split: str = "auto"  # "auto": split two-page spreads at the fold, "off": always one page per photo
    dewarp: bool = True
    fingers: bool = True  # remove fingers reaching in from the page border
    deblur: bool = True  # deconvolve pages whose estimated blur exceeds deblur.BLUR_SIGMA
    handwriting: bool = False  # remove coloured pen and pencil marks (also removes coloured print)
    orient: bool = True  # rotate pages upright using Tesseract OSD
    mode: str = "color"  # see enhance.MODES
    color_pages: set[int] = field(default_factory=set)  # 1-based page numbers forced to colour
    ocr: bool = True
    lang: str = "eng"
    debug_dir: Path | None = None


def load_image(path: Path) -> np.ndarray:
    data = np.fromfile(str(path), dtype=np.uint8)  # works with non-ASCII Windows paths
    image = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError(f"cannot read image {path}")
    return image


def photo_focal(path: Path) -> float:
    """Focal length / image diagonal from the photo's EXIF (35 mm equivalent), else the phone default."""
    try:
        from PIL import Image
        return focal_from_35mm(Image.open(path).getexif().get_ifd(0x8769).get(0xA405))
    except Exception:
        return DEFAULT_FOCAL


def _finish_page(page_img: np.ndarray, cfg: PipelineConfig, engine: TesseractEngine | None,
                 source: Path | None, quad: np.ndarray | None) -> Page:
    """Cleaning, orientation, enhancement and OCR for an already rectified page."""
    notes = []
    if cfg.fingers:
        page_img, share = remove_fingers(page_img)
        if share:
            notes.append(f"fingers removed ({share:.1%})")
    if cfg.deblur:
        page_img, sigma = deblur(page_img)
        if sigma > BLUR_SIGMA:
            notes.append(f"deblurred (blur {sigma:.1f}px)")
    if cfg.orient and engine is not None:
        rotate = engine.orientation(to_gray(flatten_illumination(page_img)))
        if rotate in _ROTATE:
            page_img = cv2.rotate(page_img, _ROTATE[rotate])

    flat = flatten_illumination(page_img)
    if cfg.handwriting:
        flat, share = remove_handwriting(flat)
        notes.append(f"handwriting removed ({share:.2%})")
    page = Page(source=source, image=finish(flat, cfg.mode, page_img) if cfg.mode != "none" else page_img,
                quad=quad, rectified=page_img, notes=notes)
    if cfg.ocr and engine is not None:
        # OCR always sees the flattened (and cleaned) grayscale, independent of the output mode
        page.words = engine.recognize(to_gray(flat))
    return page


def process_image(image: np.ndarray, cfg: PipelineConfig, engine: TesseractEngine | None = None,
                  source: Path | None = None, focal: float = DEFAULT_FOCAL) -> Page:
    """Single-page processing (no spread splitting)."""
    if engine is None and (cfg.ocr or cfg.orient):
        engine = TesseractEngine(cfg.lang)
    quad, _ = detect_document(image) if cfg.detect else (None, None)
    page_img = warp_quad(image, quad, focal=focal) if quad is not None else image
    if cfg.debug_dir:
        _write_debug(cfg.debug_dir, source, image, quad)
    return _finish_page(page_img, cfg, engine, source, quad)


def process_photo(image: np.ndarray, cfg: PipelineConfig, engine: TesseractEngine | None = None,
                  source: Path | None = None, focal: float = DEFAULT_FOCAL) -> list[Page]:
    """One photo -> one page, or two pages if it shows an open book (cfg.split == "auto")."""
    if engine is None and (cfg.ocr or cfg.orient):
        engine = TesseractEngine(cfg.lang)
    quad, mask = detect_document(image) if cfg.detect else (None, None)
    if quad is None or cfg.split == "off":
        return [process_image(image, cfg, engine, source, focal)]

    spread, spread_mask = warp_quad(image, quad, mask if mask is not None else _quad_mask(image, quad), SPREAD_MARGIN, focal)
    fold = find_fold(to_gray(spread), spread_mask)
    if cfg.debug_dir:
        _write_debug(cfg.debug_dir, source, image, quad, spread, fold)
    if not fold.confident:
        return [_finish_page(warp_quad(image, quad, focal=focal), cfg, engine, source, quad)]

    pages = []
    for part, part_mask in split_spread(spread, spread_mask, fold):
        if cfg.dewarp:  # edge-based dewarp, then text-line refinement (measured: 88.9% -> 95.6% words with conf >= 90)
            part = straighten_text_lines(dewarp_page(part, part_mask))
        pages.append(_finish_page(part, cfg, engine, source, quad))
    return pages


def process_images(paths: list[Path], cfg: PipelineConfig) -> list[Page]:
    engine = TesseractEngine(cfg.lang) if (cfg.ocr or cfg.orient) else None
    pages = [page for p in paths for page in process_photo(load_image(p), cfg, engine, source=p, focal=photo_focal(p))]
    for number in cfg.color_pages:
        if 1 <= number <= len(pages) and pages[number - 1].rectified is not None:
            pages[number - 1].image = color_page(pages[number - 1].rectified)
    for page in pages:
        page.rectified = None  # only needed for the colour override; free the memory
    return pages


def _quad_mask(image: np.ndarray, quad: np.ndarray) -> np.ndarray:
    mask = np.zeros(image.shape[:2], np.uint8)
    cv2.fillConvexPoly(mask, quad.astype(np.int32), 255)
    return mask


def _write_debug(debug_dir: Path, source: Path | None, image: np.ndarray, quad: np.ndarray | None,
                 spread: np.ndarray | None = None, fold=None) -> None:
    debug_dir.mkdir(parents=True, exist_ok=True)
    stem = source.stem if source else "page"
    overlay = image.copy()
    if quad is not None:
        thickness = max(2, max(image.shape[:2]) // 300)
        cv2.polylines(overlay, [quad.astype(np.int32)], True, (0, 200, 0), thickness)
    cv2.imencode(".jpg", overlay)[1].tofile(str(debug_dir / f"{stem}_detect.jpg"))
    if spread is not None and fold is not None:
        vis = spread.copy()
        color = (0, 0, 255) if fold.confident else (0, 200, 255)
        t = max(2, max(vis.shape[:2]) // 300)
        if fold.axis == 0:
            cv2.line(vis, (0, fold.pos), (vis.shape[1], fold.pos), color, t)
        else:
            cv2.line(vis, (fold.pos, 0), (fold.pos, vis.shape[0]), color, t)
        cv2.imencode(".jpg", vis)[1].tofile(str(debug_dir / f"{stem}_fold.jpg"))
