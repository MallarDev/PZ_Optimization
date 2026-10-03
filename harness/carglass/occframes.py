#!/usr/bin/env python3
"""Car occupant rig frames: a devCapture run's frames labelled by the dev view (devCarGlassViewList) or the alternation
cycle entry (devCarGlassCycle) each shows, optionally cropped round the player's car.

usage: harness/carglass/occframes.py <run dir> [--out /tmp/occ] [--crop x,y,w,h] [--zoom 2]
Frames within 15 % of a switch are skipped (the capture clock is the presented frame's, the state the game thread's)."""
import re, sys, os
import numpy as np
from PIL import Image

run = sys.argv[1]
arg = lambda k, d: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
out = arg("--out", "/tmp/occ")
crop = arg("--crop", "")
zoom = float(arg("--zoom", "1"))
con = open(os.path.join(run, "console.txt"), errors="replace").read()
props = open(os.path.join(run, "pzopt.properties"), errors="replace").read() if os.path.exists(os.path.join(run, "pzopt.properties")) else ""
cyc = re.search(r"car glass: dev view cycle every (\d+) ms from epoch_ms (\d+)", con)
alt = re.search(r"car glass: alternating every (\d+) ms from epoch_ms (\d+)(?: cycle (\S+))?", con)
views = re.search(r"devCarGlassViewList=(\S+)", con + props)
views = views.group(1).split(",") if views else [str(i) for i in range(10)]
cap = os.path.join(run, "capture")
lines = open(os.path.join(cap, "index.txt")).read().split()
w, h = (int(x.split("=")[1]) for x in lines[:2])
ts = [int(x) for x in lines[2:]]
d = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r")
n = d.size // (w * h * 4)
os.makedirs(out, exist_ok=True)
for i in range(n):
    t = ts[i] if i < len(ts) else 0
    tags = []
    for m, kind in ((cyc, "view"), (alt, "alt")):
        if not m:
            continue
        P, T0 = int(m.group(1)), int(m.group(2))
        ph = (t - T0) % P
        if ph < 0.15 * P or ph > 0.85 * P:
            tags = None
            break
        k = (t - T0) // P
        if kind == "view":
            tags.append("v" + views[k % len(views)])
        else:
            entries = (m.group(3) or "on,off").split(",")
            tags.append("c" + entries[k % len(entries)])
    if tags is None:
        continue
    im = Image.fromarray(np.array(d[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[::-1, :, :3])
    if crop:
        x, y, cw, ch = (int(v) for v in crop.split(","))
        im = im.crop((x, y, x + cw, y + ch))
    if zoom != 1:
        im = im.resize((int(im.width * zoom), int(im.height * zoom)), Image.NEAREST)
    name = f"{i:03d}-{'-'.join(tags) or 'f'}.png"
    im.save(os.path.join(out, name))
    print(name)
