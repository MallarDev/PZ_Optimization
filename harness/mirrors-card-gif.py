#!/usr/bin/env python3
"""Render docs/workshop/images/57-mirrors-and-windows.gif: the Workshop's "New! Mirrors and windows" card in the animated
New! format (harness/newcard.py). Right half: what it does and its cost (docs/findings-mirrors-2026-10-03.md). Left half:
the player turning on the spot in front of a bedroom wall mirror, the stock mirror above and this release below
(MacBook runs mir-card6-off / mir-card6-on, two wide mirrors placed: in-game 30 fps devCapture crops, zoom 0.5, 12:00), both cut
LEAD s after the route start (the teleport to the mirror, where the turn starts), frames picked by their timestamps.

    python3 harness/mirrors-card-gif.py [--still <png>]
Captures: $MIR_CAPS/{card6-off,card6-on}/{index.txt,frames.rgba} (copied from the Mac's ~/Zomboid/pzopt-capture) and the
run's pzopt-schedule.out (route_start_epoch_ms).
"""
import os
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/57-mirrors-and-windows.gif"
CAPS = os.environ.get("MIR_CAPS", os.path.expanduser("~/pzopt-wt/mirrors-data"))
FPS = 12
SECONDS = 4.0
LEAD = 0.5  # seconds after the teleport (the route start, where the turn starts)

INTRO = ("Wall mirrors, mirrored cabinets and dressers, and window panes now reflect what stands in front of them: the "
         "floor and the room, the street, and you, the zombies and the cars as their real other side (the game's own "
         "models drawn once more through the mirror), so a mirror shows your face, not your back.")
ROWS = [
    ("GPU time a frame, 29 windows on screen", "5120x2160, uncapped (GPU-bound)",
     (0, "0"), (13, "+13 us"), ("+13 us", "worse")),
    ("Frame time, the same scene", "what the GPU-bound frame rate pays",
     (1.095, "1.095 ms"), (1.129, "1.129 ms"), ("+0.03 ms", "worse")),
    ("GPU time, 28 zombies at shop windows", "their reflections redrawn when they move",
     (0, "0"), (25, "+25 us"), ("+25 us", "worse")),
    ("Frame time on a MacBook (M1 Pro)", "bedroom: a mirror, 19 windows, you",
     (9.95, "9.95 ms"), (9.80, "9.80 ms"), ("=", "same")),
]
FOOTER = [
    "Left: the same moment with the stock mirror (above) and this release (below), the player turning on the spot. "
    "MacBook Pro M1 Pro, OpenGL 4.1; the numbers: desktop, Linux, RTX 4090.",
    "Options > Enhancements > Mirrors and windows (next launch). The room part is worked out once per pane and kept "
    "while nothing changes; panes hidden under roofs are skipped.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-mirrors-2026-10-03.md.",
]


def frames(name):
    cap = os.path.join(CAPS, name)
    head = open(os.path.join(cap, "index.txt")).read().split()
    w, h = (int(x.split("=")[1]) for x in head[:2])
    ts = np.array([int(x) for x in head[2:]], dtype=np.int64)
    d = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(d.size // (w * h * 4), len(ts))
    run = dict(l.split("=", 1) for l in open(os.path.join(cap, "pzopt-schedule.out")).read().split() if "=" in l)
    t0 = int(run["route_start_epoch_ms"]) + LEAD * 1000

    def get(sec):
        i = min(int(np.argmin(np.abs(ts[:n] - (t0 + sec * 1000)))), n - 1)
        return Image.fromarray(np.array(d[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[::-1, :, :3])
    print(f"{name}: {n} frames over {(ts[n - 1] - ts[0]) / 1000:.2f} s, clip from +{(t0 - ts[0]) / 1000:.2f} s")
    return get, w, h


def main():
    card = Card("New! Mirrors and windows", "2026-10-04", INTRO, ROWS, FOOTER, cols=("STOCK", "THIS RELEASE"))
    x, y, w, h = card.media
    ph = (h - 8) // 2
    (off, cw, chh), (on, _, _) = frames("card6-off"), frames("card6-on")
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    count = 1 if still else round(SECONDS * FPS)
    lab = font(22, "bold")
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-mirrors-"))
    # the pane's aspect: a centred window of the capture as wide as it needs
    sw = min(cw, round(chh * w / ph))
    sx = (cw - sw) // 2
    for k in range(count):
        src = k / FPS
        im = card.base()
        d = ImageDraw.Draw(im)
        for row, (get, text, colour) in enumerate(((off, "STOCK MIRROR", STOCK), (on, "THIS RELEASE", OPT))):
            pane = get(src).crop((sx, 0, sx + sw, chh)).resize((w, ph), Image.LANCZOS)
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
