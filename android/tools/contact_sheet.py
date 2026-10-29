"""Contact sheet of all images in a folder: python -I contact_sheet.py <folder> <out.jpg> [cols] [thumb]"""
import os, sys
from PIL import Image, ImageDraw

folder, out = sys.argv[1], sys.argv[2]
cols = int(sys.argv[3]) if len(sys.argv) > 3 else 6
size = int(sys.argv[4]) if len(sys.argv) > 4 else 220
files = sorted(f for f in os.listdir(folder) if f.lower().endswith('.jpg'))
rows = (len(files) + cols - 1) // cols
sheet = Image.new('RGB', (cols * size, rows * (size + 18)), 'white')
draw = ImageDraw.Draw(sheet)
for i, f in enumerate(files):
    im = Image.open(os.path.join(folder, f)).convert('RGB')
    im.thumbnail((size - 6, size - 6))
    x, y = (i % cols) * size, (i // cols) * (size + 18)
    sheet.paste(im, (x + 3, y + 3))
    draw.text((x + 4, y + size), f'{i + 1}', fill='black')
sheet.save(out, quality=85)
print(len(files), 'images')
