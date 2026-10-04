#!/usr/bin/env python3
"""Render docs/workshop/images/59-settings-in-one-place.gif: the Workshop's "New!" card of the release that turned the
Optimizations, Enhancements and Profiler tabs into one "PZ Optimization" tab (2026-10-04, harness/newcard.py format).
Left half: the game's own screenshots of the options window, the old Optimizations tab first, then the new tab's home
page, a category, the Visuals cards, Fix a problem and the search, each held with a slow pan towards its detail and a
caption, held still with a crossfade to the next. Right half: before vs this release.

Sources (runs of harness/run.sh --mode verify, the options rig): settings-card-before (options_tab=Optimizations on
origin/master 69d02d9, Screenshots/pzopt-options.png -> shots/old-tab.png) and settings-card-after
(options_shots=home;cat:weather:0:simple;cat:light:0:simple;problems:1;search:shadow -> shots/pzopt-options-<n>.png).
The options window is 3584 x 1728 at (768, 216) on the 5120 x 2160 desktop.

    harness/queue.sh submit media --label settings-card-gif --out docs/workshop/images/59-settings-in-one-place.gif \\
        -- python3 harness/settings-card-gif.py
    (--still <png> [--scene N] [--at S]: one frame, the layout check)
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
from newcard import BG, INK, MUTED, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/59-settings-in-one-place.gif"
FPS = 10
HOLD = 2.6       # seconds per scene
FADE = 0.4       # crossfade into the next scene
WIN = (768, 216, 3584, 1728)  # the options window in the 5120 x 2160 screenshots

INTRO = ("Options > PZ Optimization: one tab for every setting. The home page has the presets, the three master "
         "switches and a tile per category; a category opens on its overview with subcategory tabs. Simple, Advanced "
         "or Everything decides how much you see, and Fix a problem starts from what you notice.")
ROWS = [
    ("Settings tabs", "Optimizations, Enhancements, Profiler",
     (3, "3"), (1, "1"), "one place"),
    ("First screen", "what opening the tab shows",
     (None, "230 rows"), (None, "13 tiles"), "overview"),
    ("Simple view", "the settings worth knowing first",
     (382, "all 382"), (80, "80"), "-79 %"),
    ("Help by symptom", "stutter, low fps, slow loading...",
     (None, "none"), (None, "9 problems"), "new"),
    ("Search", "type a name, a resource, a class",
     (None, "per tab"), (None, "everything"), "with its place"),
]
FOOTER = [
    "Same controls, Apply, presets and defaults as before; your saved choices stay. Visuals show as cards with their "
    "GPU / VRAM cost; every setting shows its first sentence and tags (advanced, changed, next launch).",
    "Works with a mouse or a controller. Windows, Linux and macOS.",
    "github.com/xD3I/PZ_Optimization, src/lua/client/pzopt/pzopt_optimizations_layout.lua (the categories and problems).",
]
# shot, caption, a wider crop (unused since the pan was dropped), the crop shown (x, y, width in options-window pixels;
# the height follows the slot)
SCENES = [
    ("before/old-tab.png", ("BEFORE", "the Optimizations tab: one long list"), (0, 0, 1100), (0, 40, 900)),
    ("after/pzopt-options-1.png", ("NOW", "home: presets, master switches, tiles"), (0, 0, 1200), (0, 100, 950)),
    ("after/pzopt-options-2.png", ("NOW", "a category: tabs and its main settings"), (0, 0, 1200), (330, 120, 1000)),
    ("after/pzopt-options-3.png", ("NOW", "Visuals: one card per feature"), (330, 100, 1200), (330, 250, 950)),
    ("after/pzopt-options-4.png", ("NOW", "Fix a problem"), (330, 100, 1200), (330, 100, 950)),
    ("after/pzopt-options-5.png", ("NOW", "one search over everything"), (330, 40, 1200), (330, 100, 950)),
]


def run_dir(roots, label):
    for root in roots:
        runs = sorted(glob.glob(os.path.join(root, label + "-2*")))
        if runs:
            return Path(runs[-1])
    sys.exit(f"no {label} run under {roots}")


def window(path):
    """The options window of a screenshot (or a crop of it already)."""
    im = Image.open(path).convert("RGB")
    if im.size == (5120, 2160):
        x, y, w, h = WIN
        im = im.crop((x, y, x + w, y + h))
    return im


def view(im, crop, mw, mh):
    """The part of the window at crop (x, y, width), scaled into mw x mh."""
    x, y, w = crop
    h = w * mh / mw
    if y + h > im.height:
        y = max(0, im.height - h)
    return im.crop((int(x), int(y), int(x + w), int(y + h))).resize((mw, mh), Image.LANCZOS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", default=None)
    ap.add_argument("--after", default=None)
    ap.add_argument("--still")
    ap.add_argument("--scene", type=int, default=1)
    ap.add_argument("--at", type=float, default=1.5)
    a = ap.parse_args()
    roots = [os.path.join(HERE, "runs"), os.path.expanduser("~/pzopt-wt/settings-before/harness/runs"),
             os.path.expanduser("~/pzopt-wt/settings-proto/harness/runs")]
    dirs = {"before": Path(a.before) if a.before else run_dir(roots, "settings-card-before") / "shots",
            "after": Path(a.after) if a.after else run_dir(roots, "settings-card-after") / "shots"}
    card = Card("New! Settings in one place", "2026-10-04", INTRO, ROWS, FOOTER, cols=("BEFORE", "THIS RELEASE"))
    x, y, mw, mh = card.media
    cap_h = 64
    ih = mh - cap_h
    print(f"card {card.W} x {card.H}, picture {mw} x {ih}")
    tag_f, cap_f = font(22, "bold"), font(22)
    shots = []
    for path, caption, c0, c1 in SCENES:
        kind, name = path.split("/")
        shots.append((window(dirs[kind] / name), caption, c0, c1))
    n_hold, n_fade = int(HOLD * FPS), int(FADE * FPS)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-settings-"))
    out = work / "card"
    out.mkdir()

    # each page held still on its detail (a pan made every frame differ: 17.5 MB), crossfades between them
    views = [view(im, c1, mw, ih) for im, _, _, c1 in shots]

    def frame(i, t):
        return views[i], shots[i][1]

    k = 0
    for i in range(len(shots)):
        for f in range(n_hold):
            if a.still and (i != a.scene or f != min(n_hold - 1, int(a.at * FPS))):
                continue
            pic, caption = frame(i, f / (n_hold - 1))
            if f >= n_hold - n_fade and i + 1 < len(shots):   # crossfade into the next scene's first frame
                nxt, _ = frame(i + 1, 0.0)
                pic = Image.blend(pic, nxt, (f - (n_hold - n_fade) + 1) / (n_fade + 1))
            im = card.base()
            im.paste(pic, (x, y))
            d = ImageDraw.Draw(im)
            d.rectangle((x, y, x + mw - 1, y + ih - 1), outline="#505058", width=1)
            tag, text = caption
            colour = STOCK if tag == "BEFORE" else OPT
            cy = y + ih + cap_h // 2
            d.text((x, cy), tag, font=tag_f, fill=colour, anchor="lm")
            d.text((x + tag_f.getlength(tag) + 14, cy), text, font=cap_f, fill=INK, anchor="lm")
            if a.still:
                im.save(a.still)
                print(f"wrote {a.still}")
                return
            k += 1
            im.save(out / f"{k:04d}.png")
    write_gif(str(out), FPS, OUT, colours=int(os.environ.get("CARD_COLOURS", "128")))
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
