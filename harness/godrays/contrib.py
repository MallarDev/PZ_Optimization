#!/usr/bin/env python3
"""What the god rays add to the picture in a run with devGodRaysAlternate + devGodRaysAlternateAll + devCapture: the mean of
the frames with them on minus the mean of the frames with them off (interleaved, so a slow drift of the fog or the light
cancels), as a heat picture (brighter: red, darker: blue, x gain) over the dimmed off frame, plus per-box numbers.

    contrib.py <run> [<run> ...] [--out /tmp/gr-contrib.png] [--gain 6] [--box name=x0,y0,x1,y1 ...]

With several runs the panels stack (same crop): an old and a new build of the same scene side by side. Boxes are in
capture pixels; each prints the mean brightening / darkening (0..255 sum over RGB / 3) and the share of its pixels changed
by more than 6.
"""
import argparse
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ppl"))
import capture  # noqa: E402


def split(run):
    fr, st = capture.load(run)
    per = t0 = None
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        m = re.search(r"god rays: alternating every (\d+) ms from epoch_ms (\d+)", line)
        if m:
            per, t0 = int(m.group(1)), int(m.group(2))
    if per is None:
        sys.exit(f"{run}: no alternation in console.txt")
    on, off = [], []
    for i, s in enumerate(st):
        ph = (s - t0) % per
        if ph < 150 or ph > per - 150:
            continue
        (on if ((s - t0) // per) % 2 == 0 else off).append(i)
    if not on or not off:
        sys.exit(f"{run}: need on and off frames")
    mean = lambda idx: sum(fr[i].astype(np.float32) for i in idx) / len(idx)
    return mean(on), mean(off), len(on), len(off)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--out", default="/tmp/gr-contrib.png")
    ap.add_argument("--gain", type=float, default=6.0)
    ap.add_argument("--box", action="append", default=[])
    a = ap.parse_args()
    boxes = []
    for b in a.box:
        n, v = b.split("=")
        boxes.append((n, [int(t) for t in v.split(",")]))
    panels = []
    for run in a.runs:
        on, off, non, noff = split(run)
        d = (on - off).mean(2)
        base = off.mean(2) * 0.45
        rgb = np.stack([base + np.clip(d, 0, None) * a.gain, base + np.abs(d) * 0.0, base + np.clip(-d, 0, None) * a.gain], 2)
        img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))
        dr = ImageDraw.Draw(img)
        print(f"{os.path.basename(run.rstrip('/'))}: {non} on / {noff} off frames; whole frame +{np.clip(d, 0, None).mean():.2f} -{np.clip(-d, 0, None).mean():.2f}")
        for n, (x0, y0, x1, y1) in boxes:
            r = d[y0:y1, x0:x1]
            print(f"  {n}: brighter {np.clip(r, 0, None).mean():.2f}  darker {np.clip(-r, 0, None).mean():.2f}  changed>6 {(np.abs(r) > 6).mean() * 100:.1f} %")
            dr.rectangle((x0, y0, x1, y1), outline=(255, 255, 0))
            dr.text((x0 + 4, y0 + 4), n, fill=(255, 255, 0))
        dr.text((8, 8), os.path.basename(run.rstrip("/")), fill=(255, 255, 255))
        panels.append(img)
    W, H = panels[0].size
    out = Image.new("RGB", (W, H * len(panels)))
    for i, p in enumerate(panels):
        out.paste(p, (0, i * H))
    out.save(a.out)
    print("wrote", a.out)


if __name__ == "__main__":
    main()
