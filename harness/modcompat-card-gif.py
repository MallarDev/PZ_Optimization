#!/usr/bin/env python3
"""Render docs/workshop/images/48-mod-compatibility.gif: the Workshop's "New!" card of the mod compatibility release
(harness/newcard.py, docs/findings-mod-compat-2026-10-01.md). Left half: CleanUI's inventory with PZ Optimization
(run mc-cleanui-rec: NeatUI Framework + CleanUI, the harness UI scenes with the inventory open), tone-mapped from
the recording, and under it the mods of the test matrix ticking in one by one. Right half: the matrix's
before / after numbers.

    harness/queue.sh submit media --label modcompat-card-gif --out docs/workshop/images/48-mod-compatibility.gif \\
        -- python3 harness/modcompat-card-gif.py
    (--still <png> [--at <seconds into the loop>]: one frame, the layout check; --run <dir>, --start, --crop)
"""
import argparse
import glob
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from newcard import INK, INK2, MUTED, OPT, PANEL, RULE, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/48-mod-compatibility.gif"
FPS = 12
SECONDS = 6.0
TONEMAP = "zscale=t=linear:npl=203,tonemap=hable,zscale=t=bt709:m=bt709:p=bt709,format=rgb24,eq=gamma=1.35"  # the UI scene is dark

INTRO = ("Your mods now run the way their authors wrote them. Java mods are read at every launch: where one patches "
         "code PZ Optimization changed, only the settings inside it switch off. Mod Lua and windows a mod draws are "
         "left to the mod. Matrix runs on the desktop:")
ROWS = [
    ("Lua errors, zombies on crops", "zombie updates on all cores, 60 s",
     (13, "13"), (0, "0"), "-100 %"),
    ("Late frames of a mod's HUD", "outdated UI redraws in 20 s",
     (18, "18"), (0, "0"), "-100 %"),
    ("A mod's inventory window", "mod replacing ISInventoryPage.lua",
     (None, "overwritten"), (None, "kept"), "fixed"),
    ("Java mod patching our code", "Lugli Optimizations",
     (None, "not checked"), (None, "3 settings off"), "auto"),
]
FOOTER = [
    "Options > Optimizations > Mod compatibility (auto / report / off); Zomboid/pzopt/mod-compat.txt names every patch "
    "and setting. Your own choice on the tab still wins. ZombieBuddy 2.3.3 cannot load Java mods on 42.21 yet, with or "
    "without PZ Optimization: ZBetterFPS and Lugli were checked by the launch scan only.",
    "Runs mc-* on the desktop, 2026-10-01: github.com/xD3I/PZ_Optimization (docs/findings-mod-compat-2026-10-01.md).",
]
MODS = [  # (name, what was tested)
    ("CleanUI + NeatUI Framework", "inventory, HUD"),
    ("Inventory Tetris", "inventory"),
    ("TwisTonFire Proximity Inventory", "inventory"),
    ("Item Condition Overlay", "inventory, hotbar"),
    ("Bandits", "zombie Lua, all cores"),
    ("Project A-Life NPCs", "zombie Lua, all cores"),
    ("PZMulticore", "Java agent"),
    ("Lugli Optimizations", "Java, 3 settings off"),
    ("Ultimate ZBetterFPS", "Java, tested, all kept"),
]
TICK_EVERY = 0.45  # seconds between two mods appearing; the clip loops meanwhile


def clip_frames(run, start, crop, width, height, work):
    """SECONDS of the recording from start, cropped, fitted into width x height, tone-mapped (SDR GIF)."""
    x, y, w, h = crop
    vf = (f"crop={w}:{h}:{x}:{y},scale={width}:{height}:force_original_aspect_ratio=decrease:force_divisible_by=2,"
          f"{TONEMAP},fps={FPS}")
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-ss", f"{start:.2f}", "-t", f"{SECONDS:.2f}",
                    "-i", str(run / "recording.mp4"), "-vf", vf, str(work / "%04d.png")], check=True)
    return [Image.open(p).convert("RGB") for p in sorted(work.glob("*.png"))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default=None)
    ap.add_argument("--start", type=float, default=27.0, help="seconds into the recording (the inventory scene)")
    ap.add_argument("--crop", default="852,0,2185,1440", help="x,y,w,h of the 5120x2160 recording (CleanUI's inventory + the road)")
    ap.add_argument("--still")
    ap.add_argument("--at", type=float, default=6.0)
    a = ap.parse_args()
    run = Path(a.run) if a.run else Path(sorted(glob.glob(os.path.join(HERE, "runs", "mc-cleanui-rec-2*")))[-1])
    card = Card("New! Mod compatibility", "2026-10-01", INTRO, ROWS, FOOTER, cols=("BEFORE", "THIS RELEASE"))
    x, y, mw, mh = card.media
    cap_f, name_f, note_f = font(22, "bold"), font(21, "semibold"), font(19)
    line_h = 30
    list_h = 40 + line_h * len(MODS)
    clip_h = mh - list_h - 8
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-modcompat-"))
    clip_dir = work / "clip"
    clip_dir.mkdir()
    clip = clip_frames(run, a.start, tuple(int(v) for v in a.crop.split(",")), mw, clip_h - 34, clip_dir)

    def frame(t):
        im = card.base()
        d = ImageDraw.Draw(im)
        d.rectangle((x, y, x + mw, y + mh), fill=PANEL)
        d.text((x, y + 14), "CleanUI with PZ Optimization", font=cap_f, fill=INK, anchor="lm")
        pic = clip[int(t * FPS) % len(clip)]
        im.paste(pic, (x + (mw - pic.width) // 2, y + 34))
        ly = y + clip_h + 8
        d.line((x, ly, x + mw, ly), fill=RULE, width=1)
        d.text((x, ly + 20), "Tested with ours", font=cap_f, fill=INK, anchor="lm")
        shown = min(len(MODS), int(t / TICK_EVERY) + 1)
        for i, (name, note) in enumerate(MODS[:shown]):
            cy = ly + 40 + line_h * i + line_h // 2
            d.line([(x + 3, cy), (x + 9, cy + 6), (x + 20, cy - 7)], fill=OPT, width=4, joint="curve")  # a tick: Noto Sans has no check glyph
            d.text((x + 30, cy), name, font=name_f, fill=INK2, anchor="lm")
            d.text((x + mw, cy), note, font=note_f, fill=MUTED, anchor="rm")
        return im

    total = len(MODS) * TICK_EVERY + 4.0  # every mod listed, then a hold before the loop
    if a.still:
        frame(a.at).save(a.still)
        print(f"wrote {a.still} ({card.W}x{card.H}, media {mw}x{mh}, clip {clip[0].size})")
        shutil.rmtree(work)
        return
    out_dir = work / "frames"
    out_dir.mkdir()
    for k in range(int(total * FPS)):
        frame(k / FPS).save(out_dir / f"{k + 1:04d}.png")
    write_gif(str(out_dir), FPS, OUT, colours=int(os.environ.get("CARD_COLOURS", "128")))
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
