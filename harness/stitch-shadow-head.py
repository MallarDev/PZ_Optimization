#!/usr/bin/env python3
"""Before / after video of the characters' sun shadows cut flat at the head (2026-10-02, player report): one pzopt
FrameCapture run with `--prop devShadowTipTogglePeriod=2000` (the sun quads end at the old place and at the fixed one
every other 2 s), each old frame shown beside the fixed frame at the same offset in the next fixed period (the player
stands still; the zombies shuffle). Top row: the scene, BEFORE | AFTER; bottom row: the player's shadow tip magnified.
Writes an SDR H.264 intermediate, then harness/encode-av1-hdr.sh makes the published AV1 10-bit PQ copy.

    harness/queue.sh submit media --label shadow-head-video --out docs/media/shadow-head-cut-before-after.mp4 \\
        -- python3 harness/stitch-shadow-head.py harness/runs/headcut-t18.5-<ts>
"""
import argparse
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "ppl"))
from capture import load  # noqa: E402
from newcard import BG, INK, INK2, OPT, STOCK, font  # noqa: E402

import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("tipjudge", os.path.join(HERE, "shadow-tip-judge.py"))
tipjudge = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tipjudge)

SCENE = (540, 380, 1640, 1000)  # capture px: the player and the zombies with their 18:30 shadows
TIP = (1290, 840, 1580, 1000)  # the player's shadow tip
PANE_W = 1100
GAP = 16
BAND = 96
FPS = 24


def pairs(stamps, ev, skip_ms):
    """(old frame, fixed frame) at the same offset into an old period and the fixed period after it."""
    starts = [(e, k) for e, k in ev]
    out = []
    for n, (e, k) in enumerate(starts):
        if k != "old" or n + 1 >= len(starts):
            continue
        e2 = starts[n + 1][0]
        end2 = starts[n + 2][0] if n + 2 < len(starts) else stamps[-1]
        for i, t in enumerate(stamps):
            if e + skip_ms <= t < e2:
                j = int(np.argmin(np.abs(stamps - (e2 + (t - e)))))
                if e2 + skip_ms <= stamps[j] < end2:
                    out.append((i, j))
    return out


def label(d, x, y, text, colour, size=40):
    d.text((x, y), text, font=font(size, "bold"), fill=colour)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--out", default="docs/media/shadow-head-cut-before-after.mp4")
    ap.add_argument("--loops", type=int, default=2)
    ap.add_argument("--skip-ms", type=int, default=150)
    ap.add_argument("--still", default="", help="write the first pair's frame to this PNG and stop (layout check)")
    a = ap.parse_args()
    frames, stamps = load(a.run)
    ev = tipjudge.periods(a.run)
    pr = pairs(stamps, ev, a.skip_ms)
    if not pr:
        sys.exit("no old / fixed frame pairs: run with --prop devShadowTipTogglePeriod=MS and a capture across the switches")
    sw, sh = SCENE[2] - SCENE[0], SCENE[3] - SCENE[1]
    tw, th = TIP[2] - TIP[0], TIP[3] - TIP[1]
    tip_h = round(PANE_W * th / tw)
    W = 2 * PANE_W + GAP
    H = BAND + sh + GAP + 56 + tip_h
    H += H % 2
    tmp = os.path.join(os.path.dirname(os.path.abspath(a.out)) or ".", ".shadow-head-src.mp4")
    ff = None if a.still else subprocess.Popen(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
                           "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-crf", "10", "-preset", "slow", "-pix_fmt", "yuv420p",
                           "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", tmp], stdin=subprocess.PIPE)
    base = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(base)
    d.text((24, 14), "Sun shadows: the head's shadow cut flat (before) and fixed (after)", font=font(38, "bold"), fill=INK)
    d.text((24, 58), "same run, 18:30, sun 25° above the horizon; the build switches every 2 s, frames paired at the same moment of each switch",
           font=font(24), fill=INK2)
    label(d, 24, BAND + sh + GAP + 8, "BEFORE  (the player's shadow, 3.8x)", STOCK, 32)
    label(d, PANE_W + GAP + 24, BAND + sh + GAP + 8, "AFTER  (the player's shadow, 3.8x)", OPT, 32)
    for _ in range(a.loops):
        for i, j in pr:
            im = base.copy()
            for col, k in ((0, i), (1, j)):
                fr = Image.fromarray(np.ascontiguousarray(frames[k]))
                x = col * (PANE_W + GAP)
                im.paste(fr.crop(SCENE), (x, BAND))
                im.paste(fr.crop(TIP).resize((PANE_W, tip_h), Image.LANCZOS), (x, BAND + sh + GAP + 56))
            dd = ImageDraw.Draw(im)
            for x in (0, PANE_W + GAP):
                dd.rectangle((x, BAND, x + 200, BAND + 72), fill=BG)
            label(dd, 20, BAND + 12, "BEFORE", STOCK, 44)
            label(dd, PANE_W + GAP + 20, BAND + 12, "AFTER", OPT, 44)
            if a.still:
                im.save(a.still)
                return
            ff.stdin.write(im.tobytes())
    ff.stdin.close()
    if ff.wait() != 0:
        sys.exit("ffmpeg failed")
    subprocess.run([os.path.join(HERE, "encode-av1-hdr.sh"), tmp, a.out], check=True)
    os.remove(tmp)
    print(f"{a.out}: {len(pr)} frame pairs x {a.loops} at {FPS} fps, {W}x{H}")


if __name__ == "__main__":
    main()
