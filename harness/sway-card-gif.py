#!/usr/bin/env python3
"""Render docs/workshop/images/43-foliage-sway.gif: the Workshop's "New!" card of foliage sway in the animated New! format
(harness/newcard.py). Left half, two panes playing together: the church lot's graveyard in a strong wind at 1:1 on the 5K
desktop, the game as it is (run sway-card-false: foliageSway off, the plants stand still) and with foliage sway
(sway-card-true); each run captured in-game (`--prop devCapture=10,5,20,100,crop=2100:200:1024:640`, default settings,
`--flag wind=1`). Right half: what it does and what it costs (docs/findings-foliage-sway-2026-09-27.md).

    harness/queue.sh submit media --label sway-card-gif --out docs/workshop/images/43-foliage-sway.gif \\
        -- python3 harness/sway-card-gif.py
    (--still <png> [--at S]: one frame, the layout check; --runs <dir> holds the sway-card-* runs)
"""
import argparse
import glob
import os
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "ppl"))
from newcard import BG, INK2, OPT, STOCK, Card, font, write_gif  # noqa: E402
from capture import load  # noqa: E402

OUT = "docs/workshop/images/43-foliage-sway.gif"
FPS = 20
FOCUS = (470, 330)  # the capture pixel each pane is centred on (the graveyard's bushes)

INTRO = ("Grass, bushes and trees move in the wind: they lean with it, gusts roll across the field, leaves flutter, and "
         "every plant keeps its own rhythm. People and zombies walking through bushes push them aside. The plants stay in "
         "the game's pre-drawn chunk pictures and only their pixels move, so it costs next to nothing.")
ROWS = [
    ("Plants in the wind", "grass, bushes, trees",
     (None, "still"), (None, "sway"), "new"),
    ("Walking through bushes", "people, zombies",
     (None, "a shake"), (None, "bend aside"), "new"),
    ("Frame time, plants moving", "the game's wind option vs this, RTX 4090 at 5K",
     (0.348, "+348 µs"), (0.020, "+20 µs"), "-94 %"),
]
FOOTER = [
    "Left: the church lot in a strong wind at 1:1 on a 5K screen, the game as it is (top) and with foliage sway (bottom).",
    "Off by default: Options > Enhancements > Foliage sway (strength, edge detail), applies at once. Windows and Linux; "
    "macOS stays without (OpenGL 2.1).",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-foliage-sway-2026-09-27.md.",
]
PANES = [("sway-card-false", "THE GAME AS IT IS", STOCK), ("sway-card-true", "FOLIAGE SWAY (THIS RELEASE)", OPT)]


def run_dir(root, label):
    runs = sorted(glob.glob(os.path.join(root, label + "-2*")))
    if not runs:
        sys.exit(f"no {label} run under {root}")
    return Path(runs[-1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default=os.path.join(HERE, "runs"))
    ap.add_argument("--still")
    ap.add_argument("--at", type=float, default=1.0)
    a = ap.parse_args()
    card = Card("New! Foliage sway", "2026-09-28", INTRO, ROWS, FOOTER)
    x, y, mw, mh = card.media
    gap = 12
    ph = (mh - gap) // 2
    lab = font(22, "bold")
    sources = []
    for label, text, colour in PANES:
        frames, stamps = load(str(run_dir(a.runs, label)))
        sources.append((frames, (stamps - stamps[0]) / 1000.0, text, colour))
        print(f"{label}: {len(frames)} frames {frames.shape[2]}x{frames.shape[1]}, {(stamps[-1] - stamps[0]) / 1000.0:.1f} s")
    length = min(src[1][-1] for src in sources)
    n = 1 if a.still else int(length * FPS)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-sway-"))
    for k in range(n):
        im = card.base()
        d = ImageDraw.Draw(im)
        g = a.at if a.still else k / FPS
        for p, (frames, t, text, colour) in enumerate(sources):
            i = int(np.clip(np.searchsorted(t, g), 0, len(t) - 1))
            src = Image.fromarray(frames[i])
            sw, sh = src.size
            # the bush cluster at 1:1 (the page shows the card at about half size: a scaled-down crop hid the movement)
            cx, cy = FOCUS
            x0 = int(np.clip(cx - mw // 2, 0, max(0, sw - mw)))
            y0 = int(np.clip(cy - ph // 2, 0, max(0, sh - ph)))
            src = src.crop((x0, y0, x0 + mw, y0 + ph))
            py = y + p * (ph + gap)
            im.paste(src, (x, py))
            tw = lab.getlength(text)
            d.rounded_rectangle((x + 4, py + 10, x + 24 + tw, py + 44), radius=6, fill=BG)
            d.text((x + 14, py + 27), text, font=lab, fill=colour, anchor="lm")
        if a.still:
            im.save(a.still)
            print(f"wrote {a.still}")
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT, colours=int(os.environ.get("CARD_COLOURS", "128")))
    shutil.rmtree(work)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB, {n} frames)")


if __name__ == "__main__":
    main()
