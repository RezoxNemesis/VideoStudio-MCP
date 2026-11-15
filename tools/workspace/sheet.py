#!/usr/bin/env python3
"""sheet.py <video> <out.jpg> <t0> <t1> [--n 6] [--cols 3] [--w 640] [--crop x,y,w,h (in source pixels)]
Frames of a stretch of a video at even moments, side by side, each labelled with its time."""
import sys, subprocess, io
from PIL import Image, ImageDraw
a = [x for x in sys.argv[1:] if not x.startswith('--')]
def opt(k, d):
    return sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
n, cols, w = int(opt('--n', 6)), int(opt('--cols', 3)), int(opt('--w', 640)); crop = opt('--crop', None)
for k in ('--n', '--cols', '--w', '--crop'):
    if k in sys.argv: a = [x for x in a if x != sys.argv[sys.argv.index(k) + 1]] if False else a
vid, out, t0, t1 = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4])
ims = []
for i in range(n):
    t = t0 + (t1 - t0) * (i + 0.5) / n if n > 1 else (t0 + t1) / 2
    vf = (f'crop={crop.replace(",", ":").split(":")[2]}:{crop.split(",")[3]}:{crop.split(",")[0]}:{crop.split(",")[1]},' if crop else '') + f'scale={w}:-2'
    p = subprocess.run(['ffmpeg', '-v', 'error', '-ss', f'{t:.3f}', '-i', vid, '-frames:v', '1', '-vf', vf, '-f', 'image2pipe', '-vcodec', 'png', '-'], capture_output=True)
    im = Image.open(io.BytesIO(p.stdout)).convert('RGB'); d = ImageDraw.Draw(im); d.rectangle([0, 0, 74, 18], fill=(0, 0, 0)); d.text((4, 3), f'{t:6.2f}s', fill=(255, 255, 0)); ims.append(im)
W, H = ims[0].size; rows = (len(ims) + cols - 1) // cols
sh = Image.new('RGB', (W * min(cols, len(ims)), H * rows))
for i, im in enumerate(ims): sh.paste(im, ((i % cols) * W, (i // cols) * H))
sh.save(out, quality=88); print(out, sh.size)
