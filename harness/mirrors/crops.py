#!/usr/bin/env python3
"""Crops of a run's --shot-at captures round its reflectors (devMirrorsLog's 'mirrors: dev rects' line).

    crops.py <run> [--kind mirror|window|all] [--scale 3] [--margin 40] [--out /tmp/mir-crops.png]

One row per reflector (up to 6): shot-game | shot2-game, nearest-neighbour scaled."""
import re, sys, argparse
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument("run"); ap.add_argument("--kind", default="mirror"); ap.add_argument("--scale", type=float, default=3)
ap.add_argument("--margin", type=int, default=40); ap.add_argument("--out", default="/tmp/mir-crops.png")
a = ap.parse_args()
con = open(f"{a.run}/console.txt", errors="replace").read()
rects = []
vw = None
for line in con.splitlines():
    if "mirrors: dev rects" in line:
        mv = re.search(r"viewport (\d+)x(\d+)", line)
        if mv:
            vw = (int(mv.group(1)), int(mv.group(2)))
        for k, x, y, w, h in re.findall(r"\[(mirror|window) (-?\d+),(-?\d+) (\d+)x(\d+)\]", line):
            if a.kind in ("all", k) and (k, x, y) not in [(r[0], r[1], r[2]) for r in rects]:
                rects.append((k, x, y, w, h))
rects = rects[-6:]
shots = [Image.open(f"{a.run}/{n}.png").convert("RGB") for n in ("shot-game", "shot2-game")]
rows = []
sx = shots[0].width / vw[0] if vw else 1.0  # (the upscaler renders the world smaller than the screenshot)
sy = shots[0].height / vw[1] if vw else 1.0
for k, x, y, w, h in rects:
    x, y, w, h = int(int(x) * sx), int(int(y) * sy), int(int(w) * sx), int(int(h) * sy)
    box = (x - a.margin, y - a.margin, x + w + a.margin, y + h + a.margin)
    cs = [s.crop(box).resize((int((box[2] - box[0]) * a.scale), int((box[3] - box[1]) * a.scale)), Image.NEAREST) for s in shots]
    rows.append(cs)
if not rows:
    sys.exit("no dev rects in the console")
W = max(r[0].width for r in rows) * 2 + 10; H = sum(r[0].height for r in rows) + 10 * len(rows)
out = Image.new("RGB", (W, H), "white"); yy = 0
for r in rows:
    out.paste(r[0], (0, yy)); out.paste(r[1], (r[0].width + 10, yy)); yy += r[0].height + 10
out.save(a.out); print(a.out, len(rows), "reflectors")
