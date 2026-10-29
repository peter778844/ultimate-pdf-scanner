"""CLI:  python -m ultimatepdfscanner photo1.jpg photo2.jpg -o book.pdf [--txt] [--docx] [--mode bw] [--lang deu+eng]"""
import argparse
from pathlib import Path

from .enhance import MODES
from .export import export_docx, export_pdf, export_txt
from .pipeline import PipelineConfig, process_images


IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp", ".webp"}


def expand_inputs(items: list[Path]) -> list[Path]:
    """Accept files, folders and glob patterns (Windows shells do not expand wildcards)."""
    out = []
    for item in items:
        if item.is_dir():
            out += sorted(p for p in item.iterdir() if p.suffix.lower() in IMAGE_EXTS)
        elif any(ch in str(item) for ch in "*?["):
            out += sorted(p for p in Path(item.anchor or ".").glob(str(item.relative_to(item.anchor)) if item.anchor else str(item))
                          if p.suffix.lower() in IMAGE_EXTS)
        else:
            out.append(item)
    if not out:
        raise SystemExit("no input images found")
    return out


def parse_pages(spec: str) -> set[int]:
    """'1,3-5' -> {1, 3, 4, 5}"""
    pages = set()
    for part in filter(None, (s.strip() for s in spec.split(","))):
        lo, _, hi = part.partition("-")
        pages.update(range(int(lo), int(hi or lo) + 1))
    return pages


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="ultimatepdfscanner", description="Scan photos into a searchable PDF, fully offline.")
    ap.add_argument("images", nargs="+", type=Path, help="image files, folders or wildcards")
    ap.add_argument("-o", "--output", type=Path, default=Path("scan.pdf"))
    ap.add_argument("--mode", choices=MODES, default="color")
    ap.add_argument("--lang", default="eng", help="Tesseract language(s), e.g. deu+eng")
    ap.add_argument("--no-detect", action="store_true", help="skip page detection/perspective correction")
    ap.add_argument("--split", choices=("auto", "off"), default="auto", help="split open-book spreads into two pages")
    ap.add_argument("--no-dewarp", action="store_true", help="do not straighten curved book pages")
    ap.add_argument("--no-orient", action="store_true", help="do not auto-rotate pages (Tesseract OSD)")
    ap.add_argument("--no-fingers", action="store_true", help="do not remove fingers at the page border")
    ap.add_argument("--no-deblur", action="store_true", help="do not sharpen blurry pages")
    ap.add_argument("--remove-handwriting", action="store_true",
                    help="remove blue/red pen and pencil marks (also removes print in those colours)")
    ap.add_argument("--color-pages", default="", help="page numbers kept in colour, e.g. 1 or 1,5-7")
    ap.add_argument("--no-ocr", action="store_true")
    ap.add_argument("--txt", action="store_true", help="also write OCR text as .txt")
    ap.add_argument("--docx", action="store_true", help="also write OCR text as .docx")
    ap.add_argument("--debug-dir", type=Path, help="write detection overlays here")
    args = ap.parse_args(argv)

    cfg = PipelineConfig(detect=not args.no_detect, split=args.split, dewarp=not args.no_dewarp,
                         fingers=not args.no_fingers, deblur=not args.no_deblur,
                         handwriting=args.remove_handwriting, orient=not args.no_orient, mode=args.mode,
                         color_pages=parse_pages(args.color_pages), ocr=not args.no_ocr,
                         lang=args.lang, debug_dir=args.debug_dir)
    pages = process_images(expand_inputs(args.images), cfg)
    for i, p in enumerate(pages, 1):
        state = "page found" if p.quad is not None else "no page found, using full image"
        extra = "".join(f", {n}" for n in p.notes)
        print(f"page {i} ({p.source.name}): {state}, {len(p.words)} words{extra}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    export_pdf(pages, args.output)
    print(f"wrote {args.output}")
    if args.txt:
        export_txt(pages, args.output.with_suffix(".txt"))
        print(f"wrote {args.output.with_suffix('.txt')}")
    if args.docx:
        export_docx(pages, args.output.with_suffix(".docx"))
        print(f"wrote {args.output.with_suffix('.docx')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
