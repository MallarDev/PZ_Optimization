#!/usr/bin/env python3
"""Car glass rig frames: a devCapture run's frames as PNGs named by the car glass state they show.

usage: harness/carglass/frames.py <run dir> [--out /tmp/cg] [--scale 0.5] [--every N]
The console's 'car glass: dev view cycle every P ms from epoch_ms T0' or 'car glass: alternating every P ms from
epoch_ms T0' line labels each frame (view<k> / on / off); frames within 15 % of a switch are skipped (the capture
clock is the presented frame's, the state the game thread's)."""
import re, sys, os
import numpy as np
from PIL import Image

run = sys.argv[1]
arg = lambda k, d: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
out = arg("--out", "/tmp/cg")
scale = float(arg("--scale", "1"))
every = int(arg("--every", "1"))
con = open(os.path.join(run, "console.txt"), errors="replace").read()
cyc = re.search(r"car glass: dev view cycle every (\d+) ms from epoch_ms (\d+)", con)
alt = re.search(r"car glass: alternating every (\d+) ms from epoch_ms (\d+)", con)
cap = os.path.join(run, "capture")
lines = open(os.path.join(cap, "index.txt")).read().split()
w, h = (int(x.split("=")[1]) for x in lines[:2])
ts = [int(x) for x in lines[2:]]
d = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r")
n = d.size // (w * h * 4)
os.makedirs(out, exist_ok=True)
for i in range(0, n, every):
    t = ts[i] if i < len(ts) else 0
    tag = "f"
    for m, kind in ((cyc, "view"), (alt, "alt")):
        if m:
            P, T0 = int(m.group(1)), int(m.group(2))
            ph = (t - T0) % P
            if ph < 0.15 * P or ph > 0.85 * P:
                tag = None
                break
            k = (t - T0) // P
            tag = f"view{k % 10}" if kind == "view" else ("on" if k % 2 == 0 else "off")
    if tag is None:
        continue
    f = np.array(d[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[::-1, :, :3]
    im = Image.fromarray(f)
    if scale != 1:
        im = im.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
    im.save(os.path.join(out, f"{i:03d}-{tag}.png"))
    print(os.path.join(out, f"{i:03d}-{tag}.png"))
