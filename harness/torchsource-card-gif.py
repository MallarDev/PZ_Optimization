#!/usr/bin/env python3
"""Render docs/workshop/images/56-torch-source.gif: the Workshop's "New! Light from the torch itself" card in the animated
New! format (harness/newcard.py). Right half: what changed and its cost (docs/findings-torch-source-2026-10-03.md). Left
half: the same slow turn twice at 01:00 with a hand torch and a lantern, per-pixel lighting on (runs ts-card-off /
ts-card-on, the released build 3d88827, in-game devCapture at 12 fps), stock above, this release below, cut at the same
capture frame.

    python3 harness/torchsource-card-gif.py            (--still <png>: one frame)
"""
import glob
import os
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/56-torch-source.gif"
RUNS = os.environ.get("TS_RUNS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs"))
FPS = 12
SECONDS = 5.5
FIRST = 6                        # capture frame of the clip's start
CX, TOP, BOTTOM = 600, 210, 690  # of the 1200x900 captures: the player (centre x) and the rows shown

INTRO = ("Stock lights every flashlight, lantern and gun light from the middle of your feet. Now each shines from the "
         "item you carry, at its height: the beam starts at the torch in your hand, a lantern lights the side it hangs "
         "on, and two lights are two lights.")
ROWS = [
    ("Where the light starts", "a hand torch, a lantern, a gun light",
     (None, "your feet"), (None, "the item"), "moved"),
    ("Game-thread time a frame", "placing the light on the drawn item",
     (0, "0"), (1.6, "~1.6 us"), ("+1.6 us", "worse")),
    ("Frame time, standing, 240 cap", "torch on, 5K, per-pixel lighting",
     (6.369, "6.369 ms"), (6.370, "6.370 ms"), ("=", "same")),
    ("GPU time a frame", "the same scene",
     (1.507, "1.51 ms"), (1.509, "1.51 ms"), ("=", "same")),
    ("Headlight beam off the drawn car", "per-pixel lighting, 120 km/h, worst frame",
     (0.41, "0.41 squares"), (0, "0"), "on the car"),
]
FOOTER = [
    "Left: the same slow turn at 01:00 with a hand torch and a lantern, stock (above) and this release (below), per-pixel "
    "lighting on. Desktop, Linux, RTX 4090, 5120x2160.",
    "Options > Enhancements > Light from the torch itself (off by default, applies at once). Also there: the beam "
    "follows where the item points, and your body shades a lantern you carry.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-torch-source-2026-10-03.md.",
]


def frames(label):
    run = sorted(glob.glob(f"{RUNS}/{label}-*"))[-1]
    cap = os.path.join(run, "capture")
    head = open(os.path.join(cap, "index.txt")).read().split()
    w, h = (int(x.split("=")[1]) for x in head[:2])
    d = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r")
    n = d.size // (w * h * 4)

    def get(i):
        i = min(i, n - 1)
        return Image.fromarray(np.array(d[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[::-1, :, :3])
    return get, n


def main():
    card = Card("New! Light from the torch itself", "2026-10-03", INTRO, ROWS, FOOTER)
    x, y, w, h = card.media
    ph = (h - 8) // 2
    (off, n_off), (on, n_on) = frames("ts-card-off"), frames("ts-card-on")
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    count = 1 if still else min(round(SECONDS * FPS), min(n_off, n_on) - FIRST)
    lab = font(22, "bold")
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-torch-"))
    for k in range(count):
        src = FIRST + k
        im = card.base()
        d = ImageDraw.Draw(im)
        for row, (get, text, colour) in enumerate(((off, "STOCK: FROM YOUR FEET", STOCK), (on, "THIS RELEASE: FROM THE ITEM", OPT))):
            ch = BOTTOM - TOP
            cw = round(ch * w / ph)
            pane = get(src).crop((CX - cw // 2, TOP, CX - cw // 2 + cw, BOTTOM)).resize((w, ph), Image.LANCZOS)
            py = y + row * (ph + 8)
            im.paste(pane, (x, py))
            tw = lab.getlength(text)
            d.rounded_rectangle((x + 8, py + 8, x + 28 + tw, py + 42), radius=6, fill=BG)
            d.text((x + 18, py + 25), text, font=lab, fill=colour, anchor="lm")
        if still:
            im.save(still)
            print(f"wrote {still}")
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT)


if __name__ == "__main__":
    main()
