"""Features that tell text from texture/noise on OCR inputs (build/eval*/<series>/ocrin/*.png)."""
import cv2, glob, sys, numpy as np, os
rows = []
for f in sorted(glob.glob(sys.argv[1] + '/*/ocrin/*.png')):
    g = cv2.imread(f, 0)
    _, ink = cv2.threshold(g, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    n, lab, st, cen = cv2.connectedComponentsWithStats(ink, 8)
    h = st[1:, 3]; w = st[1:, 2]
    letters = (h >= 6) & (h <= 150) & (w >= 2) & (w <= 3 * h)
    hl = h[letters]
    med = np.median(hl) if len(hl) else 0
    iqr = (np.percentile(hl, 75) - np.percentile(hl, 25)) / med if len(hl) > 10 else 9
    area = g.shape[0] * g.shape[1] / 1e6
    # line periodicity: strongest autocorrelation rise of the row profile in vertical strips
    rowsum = (ink > 0).sum(1).astype(float)
    p = rowsum - rowsum.mean(); ac = np.correlate(p, p, 'full')[len(p)-1:]; ac = ac / (ac[0] + 1e-9)
    lowest = 1; best = 0
    for v in ac[1:len(ac)//8]: lowest = min(lowest, v); best = max(best, v - lowest)
    tiny = ((h < 6) & (w < 6)).sum()
    rows.append((os.path.basename(f), n, len(hl), int(len(hl) / area), round(iqr, 2), round(best, 2), int(tiny / area), round((ink > 0).mean(), 3)))
print('file comps letters letters/MP iqr period tiny/MP inkshare')
for r in rows: print(*r)
