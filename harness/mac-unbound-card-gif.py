#!/usr/bin/env python3
"""Render docs/workshop/images/49-mac-unbound.gif: the Workshop's "New!" card of the macOS OpenGL 4.1 release
(macGlCore, harness/newcard.py, docs/findings-mac-gl41-2026-10-01.md). Left half: three Mac scenes (day, night with a
torch, a thunderstorm), each the game on OpenGL 2.1 (as before: no Enhancement can run) wiped over to OpenGL 4.1 with
the Mac's own Enhancements settings, from the harness screenshots of runs macgl-card-* / macgl-final-* / macgl-z1-*
(MacBook Pro M1 Pro, same route second). Right half: the release's numbers.

    python3 harness/mac-unbound-card-gif.py [--still <png> --at <seconds>]
"""
import argparse
import glob
import os
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from newcard import INK, INK2, MUTED, OPT, PANEL, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/49-mac-unbound.gif"
FPS = 12
SCENE_S, HOLD_BEFORE, WIPE_S = 3.6, 0.7, 1.1
CROP = (430, 30, 975, 575)  # of the 1920x1200 screenshots: the road, the player bottom right, above the overlay panel

SCENES = [  # (caption, before run glob, after run glob)
    ("Night: the torch's light and its shadows", "mac-macgl-card-night-legacy-*", "mac-macgl-final-night-*"),
    ("Thunderstorm: rain, puddles, wet light", "mac-macgl-card-storm-legacy-*", "mac-macgl-card-storm-core-*"),
    ("Day: AO, per-pixel light, shadows, FSR", "mac-macgl-z1-corefalse-*", "mac-macgl-final-day-*"),
]

INTRO = ("On a Mac the game ran on Apple's old OpenGL 2.1, so none of the Enhancements could run there. It now runs on "
         "OpenGL 4.1, with PZ Optimization standing in for the old drawing calls: shadows, ambient occlusion, per-pixel "
         "light, reflections, god rays and the rest work on a Mac. MacBook Pro M1 Pro:")
ROWS = [
    ("Enhancements that run on a Mac", "Options > Enhancements", (1, "1 (HDR)"), (15, "15"), "+14"),
    ("Spin route, stock settings", "fps at the 120 cap, 2 runs each", (107, "107 fps"), (106, "106 fps"), ("-1 %", "same")),
    ("60 km/h drive, stock settings", "fps at the 120 cap", (112, "112 fps"), (109, "109 fps"), ("-3 %", "worse")),
    ("Day, the Mac's Enhancements on", "AO, per-pixel light, shadows, FSR", (None, "not possible"), (None, "118 fps"), "new"),
]
FOOTER = [
    "Options > Optimizations > \"macOS: OpenGL 4.1\" (on by default, next launch). On a Mac reflections march up the "
    "water's pixel column (OpenGL 4.1 has no image atomics) and the overlay shows no GPU load: GPU timer queries cost a "
    "third of the frame rate there, so they are off.",
    "Runs macgl-* on a MacBook Pro M1 Pro, 2026-10-01: github.com/xD3I/PZ_Optimization (docs/findings-mac-gl41-2026-10-01.md).",
]


def shot(pattern):
    runs = sorted(glob.glob(os.path.join(HERE, "runs", pattern)))
    if not runs:
        raise SystemExit("no run " + pattern)
    return Image.open(os.path.join(runs[-1], "pzopt-shot.png")).convert("RGB").crop(CROP)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--still")
    ap.add_argument("--at", type=float, default=2.5)
    a = ap.parse_args()
    card = Card("New! Mac Unbound", "2026-10-01", INTRO, ROWS, FOOTER, cols=("OPENGL 2.1", "THIS RELEASE"))
    x, y, mw, mh = card.media
    cap_f, tag_f = font(21, "semibold"), font(20, "bold")
    pics = []
    for cap, before, after in SCENES:
        b, f = shot(before), shot(after)
        h = int(mw * b.height / b.width)
        pics.append((cap, b.resize((mw, h), Image.LANCZOS), f.resize((mw, h), Image.LANCZOS)))
    ph = pics[0][1].height
    py = y + 44

    def frame(t):
        im = card.base()
        d = ImageDraw.Draw(im)
        d.rectangle((x, y, x + mw, y + mh), fill=PANEL)
        i = int(t // SCENE_S) % len(pics)
        s = t - (t // SCENE_S) * SCENE_S
        cap, b, f = pics[i]
        k = min(1.0, max(0.0, (s - HOLD_BEFORE) / WIPE_S))
        k = k * k * (3 - 2 * k)
        split = int(mw * (1.0 - k))  # the 4.1 picture comes in from the right
        im.paste(b.crop((0, 0, split, ph)), (x, py)) if split > 0 else None
        if split < mw:
            im.paste(f.crop((split, 0, mw, ph)), (x + split, py))
        if 0 < split < mw:
            d.line((x + split, py, x + split, py + ph), fill=INK, width=3)
        d.text((x, y + 18), cap, font=cap_f, fill=INK2, anchor="lm")
        ty = py + ph + 22
        d.text((x, ty), "OpenGL 2.1", font=tag_f, fill=STOCK if k < 0.5 else MUTED, anchor="lm")
        d.text((x + mw, ty), "OpenGL 4.1 + Enhancements", font=tag_f, fill=OPT if k >= 0.5 else MUTED, anchor="rm")
        for j in range(len(pics)):  # scene dots
            cx = x + mw // 2 - (len(pics) - 1) * 11 + j * 22
            d.ellipse((cx - 5, ty + 30, cx + 5, ty + 40), fill=INK if j == i else MUTED)
        return im

    if a.still:
        frame(a.at).save(a.still)
        print(f"wrote {a.still} ({card.W}x{card.H}, media {mw}x{mh}, pictures {mw}x{ph})")
        return
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-mac-"))
    n = int(SCENE_S * len(pics) * FPS)
    for k in range(n):
        frame(k / FPS).save(work / f"{k:04d}.png")
    write_gif(str(work), FPS, OUT)
    shutil.rmtree(work)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB, {n} frames)")


if __name__ == "__main__":
    main()
