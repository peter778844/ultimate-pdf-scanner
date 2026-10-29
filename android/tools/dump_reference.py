"""Dump the Python prototype's detection results as a reference for the Kotlin port.

usage: python dump_reference.py <photo folder> <out.json>
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "prototype"))
from ultimatepdfscanner.detect import detect_document  # noqa: E402
from ultimatepdfscanner.enhance import to_gray  # noqa: E402
from ultimatepdfscanner.pipeline import SPREAD_MARGIN, load_image  # noqa: E402
from ultimatepdfscanner.spread import find_fold  # noqa: E402
from ultimatepdfscanner.warp import warp_quad  # noqa: E402

src, out = Path(sys.argv[1]), Path(sys.argv[2])
ref = {}
for f in sorted(src.glob("*.jpg")):
    im = load_image(f)
    quad, mask = detect_document(im)
    spread, smask = warp_quad(im, quad, mask, SPREAD_MARGIN)
    fold = find_fold(to_gray(spread), smask)
    ref[f.name] = {
        "quad": quad.tolist(),
        "spread_size": [spread.shape[1], spread.shape[0]],
        "fold": {"axis": fold.axis, "pos": fold.pos, "dip": fold.dip, "confident": fold.confident},
    }
    print(f.name, ref[f.name]["fold"])
out.write_text(json.dumps(ref, indent=1))
