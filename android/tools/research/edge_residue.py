"""Border residue of finished pages: share of dark pixels in a thin band along each side
(remains of table, neighbouring page or book edges show up as dark bands)."""
import cv2, glob, sys, numpy as np, os
rows = []
for f in sorted(glob.glob(sys.argv[1] + '/*/pages/*.jpg')):
    g = cv2.imread(f, 0)
    h, w = g.shape
    b = max(4, int(0.012 * min(h, w)))
    paper = np.percentile(g, 75)
    dark = g < 0.6 * paper
    sides = {'top': dark[:b].mean(), 'bottom': dark[-b:].mean(), 'left': dark[:, :b].mean(), 'right': dark[:, -b:].mean()}
    inner = dark[h//10:-h//10, w//10:-w//10].mean()
    worst = max(sides, key=sides.get)
    rows.append((sides[worst] - inner, os.path.basename(f), worst, round(sides[worst], 3), round(inner, 3)))
rows.sort(reverse=True)
for r in rows[:int(sys.argv[2]) if len(sys.argv) > 2 else 30]: print(round(r[0], 3), *r[1:])
