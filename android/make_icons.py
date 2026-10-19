# Draws the launcher icon (a zig-zag line through three nodes) at every density.
import os
from PIL import Image, ImageDraw
S = 512
img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([0, 0, S - 1, S - 1], radius=112, fill=(22, 27, 46, 255))
pts = [(140, 150), (372, 150), (140, 362), (372, 362)]
a, b = (255, 138, 31), (232, 51, 109)
segs = len(pts) - 1
for i in range(segs):
    t = i / max(1, segs - 1)
    col = tuple(int(a[k] + (b[k] - a[k]) * t) for k in range(3))
    d.line([pts[i], pts[i + 1]], fill=col, width=56)
    for p in (pts[i], pts[i + 1]):
        d.ellipse([p[0] - 28, p[1] - 28, p[0] + 28, p[1] + 28], fill=col)
for p, col in ((pts[0], a), (pts[-1], b)):
    d.ellipse([p[0] - 46, p[1] - 46, p[0] + 46, p[1] + 46], fill=(255, 255, 255, 255))
    d.ellipse([p[0] - 30, p[1] - 30, p[0] + 30, p[1] + 30], fill=col)
here = os.path.dirname(os.path.abspath(__file__))
for name, px in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
    out = os.path.join(here, 'res', 'mipmap-' + name)
    os.makedirs(out, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(out, 'ic_launcher.png'))
