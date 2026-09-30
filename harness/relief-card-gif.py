#!/usr/bin/env python3
"""Render docs/workshop/images/46-relief.gif: the Workshop's "New! Relief" card in the animated New! format
(harness/newcard.py). Right half: what relief is, its cost (docs/findings-relief-2026-09-30.md). Left half: the same frame
of the Rosewood stone house at 11:00 with relief off and on, a divider sweeping across it (off left of the divider, on
right of it), holding on each whole picture. The two `--shot-at` captures (runs px30-final-off / -on, the released build,
pixelLight, depth 150 %) differ by a few pixels of camera: the on shot is shifted onto the off one (phase correlation)
before cropping. The crop is magnified 1.6x: relief is fine detail and Steam shows the card at about half size.

    python3 harness/relief-card-gif.py            (--still <png>: one frame with the divider in the middle)
"""
import math
import os
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/46-relief.gif"
RUNS = os.environ.get("RELIEF_RUNS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs"))
SHOT_OFF = os.environ.get("RELIEF_OFF", f"{RUNS}/px30-final-off-20260930-080449/shot-game.png")
SHOT_ON = os.environ.get("RELIEF_ON", f"{RUNS}/px30-final-on-20260930-080354/shot-game.png")
CROP_CX, CROP_CY = 4650, 590    # of the 5120x2160 captures: the stone house's wall, windows and shingled roof
ZOOM = 1.6
FPS = 12
SWEEP, HOLD = 2.0, 1.0

INTRO = ("Parallax textures for a camera that never turns: the fine relief painted into the art (mortar between stones "
         "and bricks, gaps between planks, roof shingles, cobbles) now catches the light that moves. A low sun or the "
         "moon rakes it and the grooves fall into shade; with Per-pixel lighting a torch or headlight brings it out. "
         "The sun is baked into the chunk pictures when they are drawn, so a frame pays nothing for it.")
ROWS = [
    ("GPU time a frame, sun and moon", "baked into the chunk pictures",
     (0, "0"), (3, "3 us"), ("~0", "same")),
    ("GPU time, each new chunk picture", "the relief's two passes",
     (0, "0"), (27, "27 us"), ("+27 us", "worse")),
    ("Torch relief (Per-pixel lighting)", "composite GPU, torch sweeping a wall",
     (898, "898 us"), (875, "875 us"), "-23 us"),
    ("120 km/h drive", "fps, game-thread bound",
     (154.0, "154.0 fps"), (154.2, "154.2 fps"), ("=", "same")),
    ("Video memory", "2 bytes a chunk-picture pixel",
     (0, "0"), (512, "up to 512 MB"), ("+", "worse")),
]
FOOTER = [
    "Left: the same frame with relief off and on (Rosewood, 11:00, zoom 1, depth 150 %, shown at 1.6x). Desktop, Linux, RTX 4090, 5120x2160.",
    "Off by default (it changes the picture): Options > Enhancements > Relief, next launch. Sun and moon need Sun shadows; torches "
    "need Per-pixel lighting. Windows and Linux; not on macOS (OpenGL 2.1).",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-relief-2026-09-30.md.",
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


def offset(a, b):
    """The integer shift (dx, dy) that moves b onto a (phase correlation of the luminance)."""
    la = np.asarray(a.convert("L"), np.float32)
    lb = np.asarray(b.convert("L"), np.float32)
    fa, fb = np.fft.fft2(la - la.mean()), np.fft.fft2(lb - lb.mean())
    r = fa * np.conj(fb)
    c = np.fft.ifft2(r / np.maximum(np.abs(r), 1e-9)).real
    dy, dx = np.unravel_index(np.argmax(c), c.shape)
    h, w = c.shape
    return (dx - w if dx > w // 2 else dx), (dy - h if dy > h // 2 else dy)


def main():
    card = Card("New! Relief", "2026-09-30", INTRO, ROWS, FOOTER, cols=("RELIEF OFF", "RELIEF ON"))
    x, y, w, h = card.media
    off_full = Image.open(SHOT_OFF).convert("RGB")
    on_full = Image.open(SHOT_ON).convert("RGB")
    win = (CROP_CX - 700, CROP_CY - 400, CROP_CX + 700, CROP_CY + 400)
    dx, dy = offset(off_full.crop(win), on_full.crop(win))
    print(f"on shot shifted by {dx},{dy} px onto the off shot")
    cw, ch = round(w / ZOOM), round(h / ZOOM)
    box = (CROP_CX - cw // 2, CROP_CY - ch // 2, CROP_CX - cw // 2 + cw, CROP_CY - ch // 2 + ch)
    off = off_full.crop(box).resize((w, h), Image.LANCZOS)
    on = on_full.crop((box[0] - dx, box[1] - dy, box[2] - dx, box[3] - dy)).resize((w, h), Image.LANCZOS)
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    frames = [0.5] if still else divider_positions()
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-relief-"))
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
        for text, colour, left, shown in (("RELIEF OFF", STOCK, True, split > 0), ("RELIEF ON", OPT, False, split < w)):
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
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
