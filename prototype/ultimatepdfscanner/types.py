from dataclasses import dataclass, field
from pathlib import Path

import numpy as np


@dataclass
class Word:
    text: str
    conf: float
    box: tuple[int, int, int, int]  # x0, y0, x1, y1 in page-image pixels
    line_id: tuple[int, int, int]  # (block, paragraph, line) from the OCR engine


@dataclass
class Page:
    source: Path | None
    image: np.ndarray  # final page image (BGR or single-channel)
    quad: np.ndarray | None = None  # detected corners in the source image (tl, tr, br, bl)
    words: list[Word] = field(default_factory=list)
    rectified: np.ndarray | None = None  # cleaned, upright colour page before enhancement; kept for colour overrides
    notes: list[str] = field(default_factory=list)  # what the cleaning steps did

    @property
    def text(self) -> str:
        lines: dict[tuple[int, int, int], list[str]] = {}
        for w in self.words:
            lines.setdefault(w.line_id, []).append(w.text)
        out, prev_par = [], None
        for key in sorted(lines):
            if prev_par is not None and key[:2] != prev_par:
                out.append("")
            out.append(" ".join(lines[key]))
            prev_par = key[:2]
        return "\n".join(out)
