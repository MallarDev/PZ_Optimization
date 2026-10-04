#!/usr/bin/env python3
"""Render docs/workshop/images/58-soft-god-rays.gif: the Workshop's "New! Soft god rays" card in the animated New! format
(harness/newcard.py). Right half: what changed and what it costs (docs/findings-god-rays-2026-09-27.md, 2026-10-04
sections). Left half: the maintainer's save at 20:00, the living room and the closet room with the evening sun through
their windows and the glass door, the previous release's hard shafts above and this release below (desktop runs
grsoft-off = godRaysSoftPct=0 godRaysGlintPct=0, grsoft-on5-b11 = the defaults; in-game devCapture 1:1 crops, 20 fps, the
same seconds after the world came up).

    python3 harness/godrays-card-gif.py [--still <png>]
Captures: $GR_RUNS/grsoft-off-*/capture and $GR_RUNS/grsoft-on5-b11-*/capture (default harness/runs).
"""
import glob
import os
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "ppl"))
import capture  # noqa: E402
from newcard import BG, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/58-soft-god-rays.gif"
RUNS = os.environ.get("GR_RUNS", os.path.join(HERE, "runs"))
FPS = 10
CENTRE = (560, 430)  # the shafts' middle in the 1700 x 960 capture

INTRO = ("The shafts of sunlight through windows now have soft edges that blur the farther they get from the window, "
         "the patches where they land fade out instead of ending in a line, and dust specks drifting in them catch the "
         "sun now and then. A low evening sun no longer sends a shaft through a house and out of a window onto the "
         "street; windows and doors under a roof or a porch stay out of the sun; closed doors with glass let it in.")
ROWS = [
    ("GPU time of the shafts a frame", "5120x2160, 20:00, four shafts on screen",
     (12.3, "12.3 us"), (14.3, "14.3 us"), ("+2 us", "worse")),
    ("Light leaking out of a house", "share of the yard east of it, 20:45",
     (11.2, "11.2 %"), (2.9, "2.9 %"), ("haze only", "better")),
    ("Door types that let light through glass", "a window in the door, glass doors",
     (0, "0"), (42, "42"), ("+42", "better")),
]
FOOTER = [
    "Left: the same seconds in the maintainer's save, the previous release's hard shafts (above) and this release (below). "
    "Desktop, Linux, RTX 4090.",
    "Options > Enhancements > God rays: \"soft edges\" and \"dust glints\" (0 = the old look), applied at once.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-god-rays-2026-09-27.md.",
]


def frames(label):
    run = sorted(glob.glob(os.path.join(RUNS, label + "-2*")))[-1]
    fr, st = capture.load(run)
    print(f"{label}: {len(fr)} frames from {run}")
    return fr


def main():
    card = Card("New! Soft god rays", "2026-10-04", INTRO, ROWS, FOOTER, cols=("LAST RELEASE", "THIS RELEASE"))
    x, y, w, h = card.media
    ph = (h - 8) // 2
    off, on = frames("grsoft-off"), frames("grsoft-on5-b11")
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    n = min(len(off), len(on))
    picks = list(range(0, n, max(1, round(20 / FPS))))
    if still:
        picks = picks[len(picks) // 2:len(picks) // 2 + 1]
    lab = font(22, "bold")
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-godrays-"))
    ch, cw = off.shape[1:3]
    sh = min(ch, 420)
    sw = round(sh * w / ph)
    sx = max(0, min(cw - sw, CENTRE[0] - sw // 2))
    sy = max(0, min(ch - sh, CENTRE[1] - sh // 2))
    for k, i in enumerate(picks):
        im = card.base()
        d = ImageDraw.Draw(im)
        for row, (fr, text, colour) in enumerate(((off, "LAST RELEASE", STOCK), (on, "THIS RELEASE", OPT))):
            pane = Image.fromarray(fr[i]).crop((sx, sy, sx + sw, sy + sh)).resize((w, ph), Image.LANCZOS)
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
