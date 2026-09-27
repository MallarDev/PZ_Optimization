#!/usr/bin/env python3
"""Render docs/workshop/images/42-pixel-perfect-shadows.gif: the Workshop's "New!" card of the detailed shadows (tree
silhouette cards, per-object shadow maps of characters, animals and cars from the sun and from torches / headlights) in
the animated New! format (harness/newcard.py). Left half, two panes: the church lot at 17:00 with planted trees, zombies
and animals (run flip-card-day2) and a torch beam at 23:00 with zombies in it (flip-card-night2), each one run captured
in-game on the flip (`--prop devCapture=6,17,12,100,crop=...,ram`: the game's own frames, 900 x 700 at 1:1) with the
detailed shadows and the previous release's (capsules, crown ovals, the stock blob) taking turns every 4 s
(`--prop devDetailTogglePeriod=4000`); each pane's label follows its run's "detailed shadows: dev toggle" lines. Right
half: what changed and what it costs (docs/findings-detailed-shadows-2026-09-27.md).

    harness/queue.sh submit media --label shadows-card-gif --out docs/workshop/images/42-pixel-perfect-shadows.gif \\
        -- python3 harness/shadows-card-gif.py
    (--still <png> [--at S]: one frame, the layout check; --runs <dir> holds the flip-card-* runs)
    Each pane is cut into segments from SETTLE s after a switch to the next switch (on, off, on ...), so the label always
    matches the picture and both panes switch at the same moment.
"""
import argparse
import glob
import os
import re
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

OUT = "docs/workshop/images/42-pixel-perfect-shadows.gif"
FPS = int(os.environ.get("CARD_FPS", "10"))
SETTLE = 1.6  # s a switch takes to show everywhere on the flip (every chunk picture bakes its shadows again)
SEG = 2.4  # s of each segment in the GIF: both panes switch together

INTRO = ("Shadows drawn from the shape of whatever casts them. A tree throws one shadow of its own leaves and branches, "
         "joined to its trunk, not a trunk line and an oval. People, zombies, animals and cars cast the shadow of their "
         "own model, arms, legs, hair and bags included, in the sun and in the beam of a torch or headlights. Long evening "
         "shadows no longer stop at chunk edges.")
ROWS = [
    ("Trees", "leaves, branches and trunk",
     (None, "trunk + oval"), (None, "their own shape"), "new"),
    ("People, zombies, animals", "in the sun, torches, headlights",
     (None, "capsules"), (None, "their own model"), "new"),
    ("Cars", "body, wheels, doors",
     (None, "three capsules"), (None, "their own model"), "new"),
    ("Torch shadows, 8 zombies", "GPU a frame, Radeon 890M laptop",
     (0.46, "0.46 ms"), (0.26, "0.26 ms"), "-43 %"),
    ("Frame time, 40 casters", "in the sun, RTX 4090 at 5K",
     (0.017, "17 µs"), (0.046, "46 µs"), ("+29 µs", "worse")),
]
FOOTER = [
    "Left: the game's own frames on a Radeon 890M laptop, the previous release's shadows and this release's taking turns "
    "every 4 s: the church lot at 17:00, a hand torch at 23:00.",
    "Part of sun shadows (off by default): Options > Enhancements > Sun shadows; tree shapes, character models and animals "
    "each have a switch. Windows and Linux; macOS stays without (OpenGL 2.1).",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-detailed-shadows-2026-09-27.md.",
]
PANES = [("flip-card-day2", "Church lot, 17:00"), ("flip-card-night2", "Hand torch, 23:00")]


def run_dir(root, label):
    runs = sorted(glob.glob(os.path.join(root, label + "-2*")))
    if not runs:
        sys.exit(f"no {label} run under {root}")
    return Path(runs[-1])


def toggles(run):
    """[(epoch s, on)] from the console's dev toggle lines."""
    text = (run / "console.txt").read_text(errors="replace")
    return [(int(m.group(2)) / 1000.0, m.group(1) == "on")
            for m in re.finditer(r"detailed shadows: dev toggle (on|off) at epoch_ms (\d+)", text)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default=os.path.join(HERE, "runs"))
    ap.add_argument("--still")
    ap.add_argument("--at", type=float, default=1.0)
    a = ap.parse_args()
    card = Card("New! Pixel perfect shadows", "2026-09-27", INTRO, ROWS, FOOTER, cols=("BEFORE", "THIS RELEASE"))
    x, y, mw, mh = card.media
    gap = 12
    ph = (mh - gap) // 2
    lab, cap = font(22, "bold"), font(20)
    sources = []
    for label, where in PANES:
        run = run_dir(a.runs, label)
        frames, stamps = load(str(run))
        tog = toggles(run)
        if not tog:
            sys.exit(f"no dev toggle lines in {run}/console.txt")
        t = stamps / 1000.0
        # segments: from the first switch to "on" at least SETTLE s into the capture, each switch + SETTLE to the next
        segs = [(s + SETTLE, v) for s, v in tog if s + SETTLE >= t[0] and s + SETTLE + SEG <= t[-1]]
        while segs and not segs[0][1]:
            segs.pop(0)
        sources.append((frames, t, segs, where))
        print(f"{run.name}: {len(frames)} frames {frames.shape[2]}x{frames.shape[1]}, {t[-1] - t[0]:.1f} s, segments "
              + " ".join(f"{s - t[0]:.1f}{'+' if v else '-'}" for s, v in segs))
    nseg = min(len(src[2]) for src in sources)
    if nseg == 0:
        sys.exit("no full segment in a capture")
    n = 1 if a.still else int(nseg * SEG * FPS)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-shadows-"))
    for k in range(n):
        im = card.base()
        d = ImageDraw.Draw(im)
        for p, (frames, t, segs, where) in enumerate(sources):
            g = a.at if a.still else k / FPS
            j = min(int(g // SEG), nseg - 1)
            seg_start, on = segs[j]
            when = seg_start + (g - j * SEG)
            i = int(np.clip(np.searchsorted(t, when), 0, len(t) - 1))
            src = Image.fromarray(frames[i])
            sw, sh = src.size
            cw = min(sw, round(sh * mw / ph))  # the pane's aspect out of the capture's middle
            src = src.crop(((sw - cw) // 2, 0, (sw - cw) // 2 + cw, sh)).resize((mw, ph), Image.LANCZOS)
            py = y + p * (ph + gap)
            im.paste(src, (x, py))
            text, colour = ("PIXEL PERFECT (THIS RELEASE)", OPT) if on else ("BEFORE", STOCK)
            tw = lab.getlength(text)
            d.rounded_rectangle((x + 4, py + 10, x + 24 + tw, py + 44), radius=6, fill=BG)
            d.text((x + 14, py + 27), text, font=lab, fill=colour, anchor="lm")
            cwid = cap.getlength(where)
            d.rounded_rectangle((x + 4, py + ph - 42, x + 24 + cwid, py + ph - 10), radius=6, fill=BG)
            d.text((x + 14, py + ph - 26), where, font=cap, fill=INK2, anchor="lm")
        if a.still:
            im.save(a.still)
            print(f"wrote {a.still}")
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT, colours=int(os.environ.get("CARD_COLOURS", "96")))
    shutil.rmtree(work)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB, {n} frames)")


if __name__ == "__main__":
    main()
