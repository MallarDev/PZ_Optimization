#!/usr/bin/env python3
"""Render docs/workshop/images/45-zero-lag-ui.gif: the Workshop's "New!" card of the Zero Lag UI release in the animated
New! format (harness/newcard.py). Left half: the scenes of the UI rig (harness/mod/.../pzopt_harness_ui.lua,
ui_script=scenes) from this release's recording (run final-rec: HUD, inventory open, crafting window, world map; stock
draws the same pictures), each with two bars filling in: the UI's share of every frame in the game as it is and in this
release (runs final-stock / final-scenes, harness/uiprof.py, docs/findings-ui-snappy-2026-09-30.md). Right half: the
same numbers and the reaction to a click or a key.

    harness/queue.sh submit media --label ui-card-gif --out docs/workshop/images/45-zero-lag-ui.gif \\
        -- python3 harness/ui-card-gif.py
    (--still <png> [--scene N] [--at S]: one frame, the layout check; --runs <dir> holds the final-rec run)
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
from newcard import BG, INK, MUTED, OPT, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/45-zero-lag-ui.gif"
FPS = 15
SECONDS = 3.0
TONEMAP = "zscale=t=linear:npl=203,tonemap=hable,zscale=t=bt709:m=bt709:p=bt709,format=rgb24"

INTRO = ("Windows, menus, the inventory and the map redraw only what changed, and whatever you click or press is on "
         "screen in the very frame you do it. The UI's share of every frame at the 240 fps cap, RTX 4090, 5K, the "
         "same scenes:")
ROWS = [
    ("HUD", "health, hotbar, clock, nothing open",
     (2.2, "2.2 %"), (1.0, "1.0 %"), "-55 %"),
    ("Inventory open", "inventory and loot windows",
     (9.43, "9.4 %"), (1.66, "1.7 %"), "-82 %"),
    ("Crafting window", "the recipe list and details",
     (16.04, "16.0 %"), (2.46, "2.5 %"), "-85 %"),
    ("World map", "street names, map symbols",
     (36.17, "36.2 %"), (3.75, "3.8 %"), "-90 %"),
    ("A click or a key", "until the UI shows it",
     (None, "0-17 ms later"), (None, "same frame"), "instant"),
]
FOOTER = [
    "Left: this release (the game as it is draws the same pictures). Also: the first map open of a session 97 -> 67 ms, "
    "the save stutter after the first item transfer 17 -> 6 ms.",
    "On for everyone: Options > Optimizations > Menus, inventory and map (UI). Windows, Linux and macOS.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-ui-snappy-2026-09-30.md.",
]
# scene mark, seconds after it, crop in 5120x2160 pixels (x, y, w, h; above the queue's notification at the bottom),
# caption, (stock %, new %)
SCENES = [
    ("scene_hud2", 2.0, (0, 0, 1100, 1280), "HUD", (2.2, 1.0)),
    ("scene_inv", 2.0, (852, 0, 1100, 1280), "INVENTORY OPEN", (9.43, 1.66)),
    ("scene_craft", 2.0, (1000, 0, 1100, 1280), "CRAFTING WINDOW", (16.04, 2.46)),
    ("scene_map", 2.0, (3900, 0, 1220, 1420), "WORLD MAP", (36.17, 3.75)),
]


def run_dir(root, label):
    runs = sorted(glob.glob(os.path.join(root, label + "-2*")))
    if not runs:
        sys.exit(f"no {label} run under {root}")
    return Path(runs[-1])


def marks(run):
    out = {}
    for line in open(run / "pzopt-ui.out"):
        p = line.split()
        if p and p[0] == "M" and p[2].startswith("scene_"):
            out[p[2]] = int(p[1]) / 1e6
    return out


def extract(run, start_s, crop, width, height, work, tag):
    """The recording's frames of [start_s, start_s + SECONDS) at FPS, cropped, fitted into width x height, tone-mapped."""
    mp4 = run / "recording.mp4"
    x, y, w, h = crop
    d = work / tag
    d.mkdir()
    vf = f"crop={w}:{h}:{x}:{y},scale={width}:{height}:force_original_aspect_ratio=decrease:force_divisible_by=2,{TONEMAP}"
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-ss", f"{start_s:.2f}", "-t", f"{SECONDS:.2f}",
                    "-i", str(mp4), "-vf", vf + f",fps={FPS}", str(d / "%04d.png")], check=True)
    return sorted(d.glob("*.png"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", default=os.path.join(HERE, "runs"))
    ap.add_argument("--still")
    ap.add_argument("--scene", type=int, default=1)
    ap.add_argument("--at", type=float, default=2.0)
    a = ap.parse_args()
    card = Card("New! Zero Lag UI", "2026-09-30", INTRO, ROWS, FOOTER)
    x, y, mw, mh = card.media
    bars_h = 150
    img_h = mh - bars_h - 10
    run = run_dir(a.runs, "final-rec")
    mp4 = run / "recording.mp4"
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                         str(mp4)]).decode().strip())
    rec_start = os.path.getmtime(mp4) - dur  # the recording's first frame on the wall clock
    m = marks(run)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-ui-"))
    scenes = []
    for i, (mark, after, crop, caption, (s_pct, n_pct)) in enumerate(SCENES):
        if a.still and i != a.scene:
            continue
        frames = extract(run, m[mark] - rec_start + after, crop, mw, img_h, work, mark)
        print(f"{mark}: {len(frames)} frames")
        scenes.append((frames, caption, s_pct, n_pct))
    cap_f, lab_f, val_f = font(24, "bold"), font(21), font(24, "semibold", mono=True)
    out = work / "card"
    out.mkdir()
    k = 0
    for frames, caption, s_pct, n_pct in scenes:
        n = len(frames)
        for i in range(n):
            if a.still and i != min(n - 1, int(a.at * FPS)):
                continue
            im = card.base()
            d = ImageDraw.Draw(im)
            src = Image.open(frames[i]).convert("RGB")
            sw, sh = src.size
            im.paste(src, (x + (mw - sw) // 2, y + (img_h - sh) // 2))
            # the bars under the picture: they fill in over the scene's first 0.8 s
            grow = min(1.0, (i / FPS) / 0.8)
            by = y + img_h + 10
            d.text((x, by + 16), caption, font=cap_f, fill=INK, anchor="lm")
            for j, (who, pct, colour) in enumerate((("the game as it is", s_pct, STOCK), ("this release", n_pct, OPT))):
                ry = by + 58 + j * 44
                d.text((x, ry), who, font=lab_f, fill=MUTED, anchor="lm")
                bx, bw = x + 190, mw - 190 - 110
                w = max(4.0, bw * (pct / s_pct) * grow)  # per scene, like the table's rows
                d.rounded_rectangle((bx, ry - 11, bx + w, ry + 11), radius=4, fill=colour)
                d.text((bx + w + 10, ry), f"{pct * grow:.1f} %", font=val_f, fill=colour, anchor="lm")
            d.text((x, by + 140), "of every frame on the UI", font=lab_f, fill=MUTED, anchor="lm")
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
