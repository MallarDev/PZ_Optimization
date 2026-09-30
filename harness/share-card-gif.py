#!/usr/bin/env python3
"""Render docs/workshop/images/47-share-settings.gif: the Workshop's "New!" card of the settings export / import
(harness/newcard.py). Left half: the game's own screenshots of the options_io rig (run settings-io-check, 2026-10-01:
the tab's buttons, the Export message, the Import box, the result), stepped through with a crossfade; the button
pressed is ringed in green. The Export message's path line named the rig's test folder under the maintainer's home:
it is painted over with the generic ".../Zomboid/pzopt/settings-export.ini" (same place, same font size, dialog colour).
Right half: what it does.

    harness/queue.sh submit media --label share-card-gif --out docs/workshop/images/47-share-settings.gif \\
        -- python3 harness/share-card-gif.py
    (--still <png> [--step N]: one frame, the layout check; --run <dir> holds the three pzopt-io-*.png)
"""
import argparse
import glob
import os
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from newcard import INK, OPT, PANEL, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/47-share-settings.gif"
FPS = 10
FADE = 0.3

INTRO = ("Every setting of the Optimizations, Enhancements and Profiler tabs as a few lines of text: export your setup, "
         "paste it on another PC or send it to a friend. Only what differs from the defaults travels.")
ROWS = [
    ("Copy your setup", "330+ settings on three tabs", (None, "one by one"), (None, "1 click"), "Export"),
    ("Load a shared setup", "paste it, OK, then Apply", (None, "one by one"), (None, "1 paste"), "Import"),
    ("Keep a backup", "Zomboid/pzopt/settings-export.ini", (None, "none"), (None, "every export"), "new"),
]
FOOTER = [
    "Settings the text does not list go back to the defaults; settings pinned by pzopt.properties stay; unknown or "
    "misspelt keys are named and skipped. A pasted options.ini works too. Windows, Linux and macOS.",
    "Options > Optimizations, Enhancements or Profiler > Export settings / Import settings...",
    "Checked in game on the desktop (run settings-io-check): github.com/xD3I/PZ_Optimization.",
]
# crops of the 4096x1728 screenshots (x, y, w, h); buttons ringed in the button crop's own pixels
BUTTONS = (1344, 18, 363, 185)
EXPORT_BTN, IMPORT_BTN = (10, 79, 132, 32), (10, 115, 148, 32)
DIALOG_SMALL = (2303, 564, 514, 224)
DIALOG_IMPORT = (2183, 428, 751, 537)
# the path line of the Export message, in DIALOG_SMALL's pixels: painted over, redrawn generic
PATH_BOX = (24, 131, 468, 22)  # rows 134-150, x 29-484 in the shot
PATH_TEXT = ".../Zomboid/pzopt/settings-export.ini."
STEPS = [  # (shot, crop, ring, caption, seconds)
    ("1-export", BUTTONS, EXPORT_BTN, "1  Export settings", 1.6),
    ("1-export", DIALOG_SMALL, None, "Copied, and saved to a file", 2.6),
    ("2-import", BUTTONS, IMPORT_BTN, "2  Any PC: Import settings...", 1.6),
    ("2-import", DIALOG_IMPORT, None, "The box starts with the clipboard", 3.0),
    ("3-result", DIALOG_SMALL, None, "Set on all three tabs; Apply saves", 2.8),
]


def game_font(size):
    """The game's UI face is Noto Sans Medium; used only for the repainted path line."""
    return ImageFont.truetype("/usr/share/fonts/noto/NotoSans-Medium.ttf", size)


def shot_image(run, shot, crop, ring, mw, mh):
    im = Image.open(run / f"pzopt-io-{shot}.png").convert("RGB")
    x, y, w, h = crop
    im = im.crop((x, y, x + w, y + h))
    d = ImageDraw.Draw(im)
    if crop == DIALOG_SMALL and shot == "1-export":
        px, py, pw, ph = PATH_BOX
        bg = im.getpixel((px + pw // 2, py - 3))  # the blank row above the line
        d.rectangle((px, py, px + pw, py + ph), fill=bg)
        d.text((29, 147), PATH_TEXT, font=game_font(18), fill=(255, 255, 255), anchor="ls")
    if ring:
        rx, ry, rw, rh = ring
        d.rounded_rectangle((rx - 4, ry - 4, rx + rw + 4, ry + rh + 4), radius=5, outline=OPT, width=4)
    scale = min(mw / w, mh / h)
    return im.resize((round(w * scale), round(h * scale)), Image.LANCZOS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default=None)
    ap.add_argument("--still")
    ap.add_argument("--step", type=int, default=1)
    a = ap.parse_args()
    run = Path(a.run) if a.run else Path(sorted(glob.glob(os.path.join(HERE, "runs", "settings-io-check-2*")))[-1])
    card = Card("New! Share settings", "2026-10-01", INTRO, ROWS, FOOTER, cols=("BEFORE", "THIS RELEASE"))
    x, y, mw, mh = card.media
    cap_f = font(24, "bold")
    img_top = y + 44
    img_h = mh - 44

    def frame(step):
        shot, crop, ring, caption, _ = STEPS[step]
        im = card.base()
        d = ImageDraw.Draw(im)
        d.rectangle((x, y, x + mw, y + mh), fill=PANEL)
        d.text((x, y + 16), caption, font=cap_f, fill=INK, anchor="lm")
        pic = shot_image(run, shot, crop, ring, mw, img_h)
        im.paste(pic, (x + (mw - pic.width) // 2, img_top + (img_h - pic.height) // 2))
        return im

    stills = [frame(i) for i in range(len(STEPS))]
    if a.still:
        stills[a.step].save(a.still)
        print(f"wrote {a.still}")
        return
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-share-"))
    k = 0
    fade_n = round(FADE * FPS)
    for i, (_, _, _, _, secs) in enumerate(STEPS):
        nxt = stills[(i + 1) % len(STEPS)]
        hold = round(secs * FPS) - fade_n
        for _ in range(hold):
            k += 1
            stills[i].save(work / f"{k:04d}.png")
        for j in range(1, fade_n + 1):
            k += 1
            Image.blend(stills[i], nxt, j / (fade_n + 1)).save(work / f"{k:04d}.png")
    write_gif(str(work), FPS, OUT, colours=int(os.environ.get("CARD_COLOURS", "128")))
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
