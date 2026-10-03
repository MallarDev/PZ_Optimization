#!/usr/bin/env python3
"""Render docs/workshop/images/55-occluded-outlines.gif: the Workshop's "New! Occluded zombie outlines" card in the
animated New! format (harness/newcard.py). Right half: what it does and its cost (docs/findings-occluded-outlines-2026-10-03.md).
Left half: run outl-video4 (200 idle zombies round the church lot, zoom 1, width 2, the setting switching every 2.5 s,
in-game 1:1 capture at 10 fps), each OFF frame above the ON frame at the same moment of the next period.

    python3 harness/outline-card-gif.py [run]          (--still <png>: one frame)
"""
import glob
import importlib.util
import os
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "ppl"))
from capture import load  # noqa: E402
from newcard import BG, OPT, STOCK, Card, font, write_gif  # noqa: E402

_spec = importlib.util.spec_from_file_location("stitch", os.path.join(HERE, "stitch-outlines.py"))
stitch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(stitch)

OUT = "docs/workshop/images/55-occluded-outlines.gif"
FPS = 10
PAIRS = 40                          # 4 s of the capture
CROP_CX, CROP_TOP, CROP_BOTTOM = 1010, 200, 800   # of the 2560x1440 capture: the church's roof and wall, the maple

INTRO = ("Zombies your character sees keep a thin contour where a wall, a roof, a tree or a car hides them, so you "
         "know where they went. Only the zombies you see right now; the contour dims in the dark. Contributed by "
         "novakovicdavid (PR #48), reworked to cost next to nothing.")
ROWS = [
    ("Frame time, 60 zombies on screen", "switched off and on every second in one run",
     (1.33, "1.33 ms"), (1.35, "1.35 ms"), ("+0.02 ms", "same")),
    ("Frame time, 300 zombies", "a crowd round the church",
     (3.03, "3.03 ms"), (3.11, "3.11 ms"), ("+0.08 ms", "worse")),
    ("The first version, 60 zombies", "PR #48 as submitted",
     (1.4, "1.4 ms"), (6.1, "6.1 ms"), ("+4.7 ms", "worse")),
]
FOOTER = [
    "Left: the same run with the setting off (above) and on (below), 200 zombies round a church, zoom 1, width 2. "
    "Desktop, Linux, RTX 4090, 5120x2160, uncapped.",
    "Options > Enhancements > Occluded zombie outlines (off by default, applies at once): width, colour, opacity, "
    "ignore grass and bushes.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-occluded-outlines-2026-10-03.md.",
]


def main():
    args = [a for a in sys.argv[1:]]
    still = None
    if "--still" in args:
        i = args.index("--still")
        still = args[i + 1]
        del args[i:i + 2]
    run = args[0] if args else sorted(glob.glob(os.path.join(HERE, "runs", "outl-video4-*")))[-1]
    frames, stamps = load(run)
    pairs = stitch.pairs(stamps, stitch.periods(run), 120)[:PAIRS]
    card = Card("New! Occluded zombie outlines", "2026-10-03", INTRO, ROWS, FOOTER, cols=("OFF", "ON"))
    x, y, w, h = card.media
    ph = (h - 8) // 2
    ch = CROP_BOTTOM - CROP_TOP
    cw = round(ch * w / ph)
    box = (CROP_CX - cw // 2, CROP_TOP, CROP_CX - cw // 2 + cw, CROP_BOTTOM)
    lab = font(22, "bold")
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-outlines-"))
    for k, (i, j) in enumerate(pairs[:1] if still else pairs):
        im = card.base()
        d = ImageDraw.Draw(im)
        for row, (idx, text, colour) in enumerate(((i, "OFF", STOCK), (j, "ON", OPT))):
            pane = Image.fromarray(np.ascontiguousarray(frames[idx])).crop(box).resize((w, ph), Image.LANCZOS)
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
