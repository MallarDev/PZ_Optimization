#!/usr/bin/env python3
"""Render docs/workshop/images/80-reflective-props.gif: the Workshop's "New! Glass, screens and steel reflect" card in the
animated New! format (harness/newcard.py). Right half: what it does and its cost (docs/findings-prop-reflections-2026-10-08.md).
Left half: the player walking past the showroom row of props (glass counters, glass table, TV, steel counter, gym mirror,
glass-door fridge, glass door, toilet; the sheriff car parked in front), mirrors off above and this release below (desktop
runs card-props-false / card-props-true: in-game 30 fps devCapture of the whole frame at 25 %, zoom 0.5, 12:00), both cut
LEAD s after the route start, frames picked by their timestamps.

    python3 harness/props-card-gif.py [--still <png>]       (env PROPS_RUNS: the runs' parent dir, default harness/runs)
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

OUT = "docs/workshop/images/80-reflective-props.gif"
RUNS = os.environ.get("PROPS_RUNS", "harness/runs")
FPS = 12
SECONDS = 5.0
LEAD = 1.0
CROP = (0.50, 0.30, 0.78, 0.68)  # the props row and the car, as fractions of the frame (x0, y0, x1, y1)

INTRO = ("With Mirror and window reflections on, the props that are not windows or mirrors reflect too: glass doors, "
         "store fronts, display counters and cases, glass-door fridges, the glass table and the gym's mirrors show the "
         "scene, the sky and you; steel, ceramic and dark screens keep their look and catch whoever passes in front.")
ROWS = [
    ("GPU time a frame, 9 props and a car", "desktop, 5120x2160, still camera",
     (0, "0"), (None, None), (None, None)),
    ("GPU time, walking past store fronts", "Ayaneo Flip (Radeon 890M), 60 fps cap",
     (0, "0"), (115, "+115 us"), ("+0.1 ms", "worse")),
    ("Frame time p99, the same walk", "the slowest 1 % of frames",
     (17.5, "17.5 ms"), (17.5, "17.5 ms"), ("=", "same")),
]
FOOTER = [
    "Left: the same walk with reflections off (above) and this release (below). 434 props; each one's shape comes from "
    "the game's own depth maps, so every face reflects its own way.",
    "Options > PZ Optimization > Visuals > Mirrors and windows: \"glass, screens and steel reflect too\" (next launch).",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-prop-reflections-2026-10-08.md.",
]


def run(label):
    return sorted(glob.glob(os.path.join(RUNS, f"{label}-2*")))[-1]


def frames(label):
    d = run(label)
    head = open(os.path.join(d, "capture", "index.txt")).read().split()
    w, h = (int(x.split("=")[1]) for x in head[:2])
    ts = np.array([int(x) for x in head[2:]], dtype=np.int64)
    data = np.memmap(os.path.join(d, "capture", "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(data.size // (w * h * 4), len(ts))
    sched = dict(l.split("=", 1) for l in open(os.path.join(d, "pzopt-schedule.out")).read().split() if "=" in l)
    t0 = int(sched["route_start_epoch_ms"]) + LEAD * 1000

    def get(sec):
        i = min(int(np.argmin(np.abs(ts[:n] - (t0 + sec * 1000)))), n - 1)
        return Image.fromarray(np.array(data[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[::-1, :, :3])
    print(f"{label}: {n} frames, clip from +{(t0 - ts[0]) / 1000:.2f} s")
    return get, w, h


def main():
    gpu = float(os.environ.get("PROPS_DESKTOP_US", "0"))
    rows = list(ROWS)
    rows[0] = (ROWS[0][0], ROWS[0][1], (0, "0"), (gpu, f"+{gpu:.0f} us"), (f"+{gpu / 1000:.2f} ms", "worse"))
    card = Card("New! Glass, screens and steel reflect", "2026-10-09", INTRO, rows, FOOTER, cols=("STOCK", "THIS RELEASE"))
    x, y, w, h = card.media
    ph = (h - 8) // 2
    (off, cw, chh), (on, _, _) = frames("card-props-false"), frames("card-props-true")
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    count = 1 if still else round(SECONDS * FPS)
    lab = font(22, "bold")
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-props-"))
    # the crop at the pane's aspect, centred on the props row
    cx0, cy0, cx1, cy1 = (round(CROP[0] * cw), round(CROP[1] * chh), round(CROP[2] * cw), round(CROP[3] * chh))
    bw, bh = cx1 - cx0, cy1 - cy0
    if bw / bh > w / ph:
        nw = round(bh * w / ph)
        cx0 += (bw - nw) // 2
        bw = nw
    else:
        nh = round(bw * ph / w)
        cy0 += (bh - nh) // 2
        bh = nh
    for k in range(count):
        src = (k / FPS) if not still else 2.0
        im = card.base()
        dr = ImageDraw.Draw(im)
        for row, (get, text, colour) in enumerate(((off, "REFLECTIONS OFF", STOCK), (on, "THIS RELEASE", OPT))):
            pane = get(src).crop((cx0, cy0, cx0 + bw, cy0 + bh)).resize((w, ph), Image.LANCZOS)
            py = y + row * (ph + 8)
            im.paste(pane, (x, py))
            tw = lab.getlength(text)
            dr.rounded_rectangle((x + 8, py + 8, x + 28 + tw, py + 42), radius=6, fill=BG)
            dr.text((x + 18, py + 25), text, font=lab, fill=colour, anchor="lm")
        if still:
            im.save(still)
            print(f"wrote {still}")
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT)
    print(OUT)


if __name__ == "__main__":
    main()
