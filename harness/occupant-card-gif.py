#!/usr/bin/env python3
"""Render docs/workshop/images/54-people-in-cars.gif: the Workshop's "New! People in cars" card in the animated New!
format (harness/newcard.py). Right half: what changed and its cost (docs/findings-car-occupant-2026-10-03.md). Left half:
the same 60 km/h drive twice (runs occ-card-off / occ-card-impostor, car glass on, zoom 0.5, 11:00, in-game devCapture at
30 fps), the car glass's empty cabin above, this release's driver at the wheel below, cut at the same capture frame.

    python3 harness/occupant-card-gif.py            (--still <png>: one frame)
"""
import glob
import os
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/54-people-in-cars.gif"
RUNS = os.environ.get("OCC_RUNS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs"))
FPS = 12
SECONDS = 3.5
FIRST = 30                       # capture frame of the clip's start (1 s in)
CAR_CX, CAR_TOP, CAR_BOTTOM = 470, 205, 600   # of the 1200x600 captures: the car (centre x, rows)

INTRO = ("Stock never draws whoever sits in a car, so with Car glass the cabin looked empty. Now the driver and "
         "passengers show through the windows: the game's own model of each, in their clothes, hands on the wheel, "
         "under the glass's tint and reflections.")
ROWS = [
    ("GPU time a frame, parked with a driver", "the occupant's picture + the glass reading it",
     (0, "0"), (1.2, "1.2 us"), ("+1.2 us", "worse")),
    ("GPU time a frame, 120 km/h drive", "Rosewood, the same occupant",
     (0, "0"), (2, "~2 us"), ("+2 us", "worse")),
    ("Frame rate, the same drive", "240 cap, two runs each",
     (154.0, "154.0 fps"), (154.5, "154.5 fps"), ("=", "same")),
    ("Drawing them every frame instead", "what the reuse saves",
     (None, ""), (27, "22-33 us"), ("avoided", "same")),
]
FOOTER = [
    "Left: the same drive with the empty cabin (above) and with this release (below), 60 km/h, zoom 0.5, 11:00. "
    "Desktop, Linux, RTX 4090, 5120x2160.",
    "With Car glass on (Options > Enhancements > Car glass > the people inside, next launch). Driver and passengers, split screen, "
    "other players' cars: four cars a view get the full model, more get a simpler body.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-car-occupant-2026-10-03.md.",
]


def frames(label):
    run = sorted(glob.glob(f"{RUNS}/occ-card-{label}-*"))[-1]
    cap = os.path.join(run, "capture")
    head = open(os.path.join(cap, "index.txt")).read().split()
    w, h = (int(x.split("=")[1]) for x in head[:2])
    d = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r")
    n = d.size // (w * h * 4)

    def get(i):
        i = min(i, n - 1)
        return Image.fromarray(np.array(d[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[::-1, :, :3])
    return get


def main():
    card = Card("New! People in cars", "2026-10-03", INTRO, ROWS, FOOTER, cols=("EMPTY CAR", "THIS RELEASE"))
    x, y, w, h = card.media
    ph = (h - 8) // 2
    off, on = frames("off"), frames("impostor")
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    count = 1 if still else round(SECONDS * FPS)
    lab = font(22, "bold")
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-occupant-"))
    for k in range(count):
        src = FIRST + round(k * 30 / FPS)
        im = card.base()
        d = ImageDraw.Draw(im)
        for row, (get, text, colour) in enumerate(((off, "BEFORE: AN EMPTY CABIN", STOCK), (on, "THIS RELEASE", OPT))):
            # the car's rows, as wide as the pane's aspect needs
            ch = CAR_BOTTOM - CAR_TOP
            cw = round(ch * w / ph)
            pane = get(src).crop((CAR_CX - cw // 2, CAR_TOP, CAR_CX - cw // 2 + cw, CAR_BOTTOM)).resize((w, ph), Image.LANCZOS)
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
