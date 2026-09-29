#!/usr/bin/env python3
"""Render docs/workshop/images/44-real-blood.gif: the Workshop's "New!" card of Real Blood in the animated New! format
(harness/newcard.py). Left half, two panes playing together: a zombie crowd round the bench car at 15:00 on the 5K
desktop at 1:1, 400 fresh splats thrown at +1 s, the game as it is (run rb-card-stock: --prop enabled=false) and this
release with Wet blood and Reflections on (rb-card-new); each captured in-game
(`--prop devCapture=2,6,20,100,crop=2090:800:1024:640`). Right half: what it does and what it costs
(docs/findings-blood-decals-2026-09-29.md).

    harness/queue.sh submit media --label blood-card-gif --out docs/workshop/images/44-real-blood.gif \\
        -- python3 harness/blood-card-gif.py
    (--still <png> [--at S]: one frame, the layout check; --runs <dir> holds the rb-card-* runs)
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
from newcard import BG, OPT, STOCK, Card, font, write_gif  # noqa: E402
from capture import load  # noqa: E402

OUT = "docs/workshop/images/44-real-blood.gif"
FPS = 20
FOCUS = (600, 300)  # the capture pixel each pane is centred on (the pools beside the car, its reflection)

INTRO = ("Fresh blood is wet: pools catch the sun and the lamps on their rims, mirror what stands in them, "
         "and dry from the edges in. Underneath, floor blood costs a fraction of what it did: drawn in one batch per "
         "chunk, new splats painted straight in, under the grass where it grows, pixel for pixel as before.")
ROWS = [
    ("Blood per chunk picture", "1,000 splats per chunk, RTX 4090 at 5K",
     (0.1305, "131 µs"), (0.0334, "33 µs"), "-74 %"),
    ("Floor re-drawn for new blood", "a fight by the road, grass and all, per second",
     (29.4, "29"), (2.5, "2.5"), "-91 %"),
    ("Game thread on blood, big fight", "ms per second",
     (22.1, "22 ms"), (5.3, "5.3 ms"), "-76 %"),
    ("Wet blood", "reflections, sheen, HDR glints",
     (None, "none"), (None, "+9 µs GPU"), "new"),
]
FOOTER = [
    "Left: fresh blood round a car at noon, 1.3x on a 5K screen, the game as it is (top) and this release with Wet blood and Reflections (bottom).",
    "The faster blood is on for everyone. Wet blood: Options > Enhancements > Wet blood (off by default; Reflections for the "
    "mirroring, HDR output for the glints). Windows and Linux; macOS gets the faster blood only.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-blood-decals-2026-09-29.md.",
]
PANES = [("rb5-card-stock", "THE GAME AS IT IS", STOCK), ("rb5-card-new", "REAL BLOOD (THIS RELEASE)", OPT)]


def run_dir(root, label):
    runs = sorted(glob.glob(os.path.join(root, label + "-2*")))
    if not runs:
        sys.exit(f"no {label} run under {root}")
    return Path(runs[-1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default=os.path.join(HERE, "runs"))
    ap.add_argument("--still")
    ap.add_argument("--at", type=float, default=2.0)
    a = ap.parse_args()
    card = Card("New! Real Blood", "2026-09-29", INTRO, ROWS, FOOTER)
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
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-blood-"))
    for k in range(n):
        im = card.base()
        d = ImageDraw.Draw(im)
        g = a.at if a.still else k / FPS
        for p, (frames, t, text, colour) in enumerate(sources):
            i = int(np.clip(np.searchsorted(t, g), 0, len(t) - 1))
            src = Image.fromarray(frames[i])
            sw, sh = src.size
            # 1:1 (the page shows the card at about half size: a scaled-down crop hid the rims and the reflections)
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
