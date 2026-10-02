#!/usr/bin/env python3
"""Render docs/workshop/images/50-glass-car-windows.gif: the Workshop's "New! Glass car windows" card in the animated New!
format (harness/newcard.py). Right half: what the car glass does and its cost (docs/findings-car-glass-2026-10-01.md).
Left half: the same frame of parked cars (the sheriff car, the CarLuxury coupe, a sedan; run cg-card, the released build,
10:30) with the stock windows and with the glass, a divider sweeping across (stock left of it, glass right of it),
holding on each whole picture. The two frames come from one devCapture run alternating the glass on and off
(devCarGlassAlternate) over a still camera, so they line up exactly.

    python3 harness/carglass-card-gif.py            (--still <png>: one frame with the divider in the middle)
"""
import glob
import math
import os
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/50-glass-car-windows.gif"
RUNS = os.environ.get("GLASS_RUNS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs"))
RUN = sorted(glob.glob(f"{RUNS}/cg-card-*"))[-1] if glob.glob(f"{RUNS}/cg-card-*") else ""
SHOT_OFF = os.environ.get("GLASS_OFF", f"{RUN}/card-off.png")
SHOT_ON = os.environ.get("GLASS_ON", f"{RUN}/card-on.png")
CROP_CX, CROP_CY, CROP_W = 2880, 1190, 1000   # of the 5120x2160 captures: the sheriff car, the coupe, the sedan
FPS = 12
SWEEP, HOLD = 2.0, 1.2

INTRO = ("Car windows become real glass instead of the stock opaque blue: they mirror the sky and the buildings, trees "
         "and road around each car and glint with the sun, the moon and the lamps; through them you see the seats, the "
         "people inside and the street behind. Rain beads on them, mirrors are silvered.")
ROWS = [
    ("GPU time a frame, 120 km/h drive", "the glass draws + the reflection probes",
     (0, "0"), (15, "15 us"), ("+15 us", "worse")),
    ("Frame time, same drive", "Rosewood, at the 157 fps cap",
     (6.33, "6.33 ms"), (6.30, "6.30 ms"), ("=", "same")),
    ("GPU time a frame, 33 parked cars", "zoom 1, desktop busy with other apps",
     (0, "0"), (57, "57 us"), ("+57 us", "worse")),
    ("Render thread, per car on screen", "the glass draw's set-up",
     (0, "0"), (3, "~3 us"), ("+3 us", "worse")),
    ("No car on screen", "nothing is drawn or computed",
     (0, "0"), (0, "0"), ("0", "same")),
]
FOOTER = [
    "Left: the same frame with the stock windows and with glass (10:30, zoom 1). Desktop, Linux, RTX 4090, 5120x2160.",
    "Off by default (it changes the picture): Options > Enhancements > Car glass, next launch. The game's vehicle shaders stay "
    "untouched: a separate glass program redraws only the windows. Windows and Linux; not on macOS.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-car-glass-2026-10-01.md.",
]


def ease(t):
    return 0.5 - 0.5 * math.cos(math.pi * t)


def divider_positions():
    seq = [0.0] * round(HOLD * FPS)
    n = round(SWEEP * FPS)
    seq += [ease((i + 1) / n) for i in range(n)]
    seq += [1.0] * round(HOLD * FPS)
    seq += [1 - ease((i + 1) / n) for i in range(n)]
    return seq


def main():
    card = Card("New! Glass car windows", "2026-10-02", INTRO, ROWS, FOOTER, cols=("STOCK", "GLASS"))
    x, y, w, h = card.media
    ch = round(CROP_W * h / w)
    box = (CROP_CX - CROP_W // 2, CROP_CY - ch // 2, CROP_CX - CROP_W // 2 + CROP_W, CROP_CY - ch // 2 + ch)
    off = Image.open(SHOT_OFF).convert("RGB").crop(box).resize((w, h), Image.LANCZOS)
    on = Image.open(SHOT_ON).convert("RGB").crop(box).resize((w, h), Image.LANCZOS)
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    frames = [0.5] if still else divider_positions()
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-glass-"))
    lab = font(24, "bold")
    for k, f in enumerate(frames):
        im = card.base()
        pane = on.copy()
        split = round(w * f)
        if split:
            pane.paste(off.crop((0, 0, split, h)), (0, 0))
        im.paste(pane, (x, y))
        d = ImageDraw.Draw(im)
        if 0 < split < w:
            d.line((x + split, y, x + split, y + h - 1), fill=INK, width=3)
        for text, colour, left, shown in (("STOCK", STOCK, True, split > 0), ("GLASS", OPT, False, split < w)):
            if not shown:
                continue
            tw = lab.getlength(text)
            tx = x + 14 if left else x + w - 14 - tw
            d.rounded_rectangle((tx - 10, y + 12, tx + tw + 10, y + 50), radius=6, fill=BG)
            d.text((tx, y + 31), text, font=lab, fill=colour, anchor="lm")
        if still:
            im.save(still)
            print(f"wrote {still}")
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT)


if __name__ == "__main__":
    main()
