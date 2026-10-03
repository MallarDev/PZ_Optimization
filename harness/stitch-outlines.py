#!/usr/bin/env python3
"""Off / on video of the occluded zombie outlines (2026-10-03, PR #48 reworked): one pzopt FrameCapture run with
`--prop devOutlineAlternate=MS` (the outlines switch off and on every MS; the console logs
`occluded outlines: phase on|off at <epoch ms>`), each OFF frame shown beside the ON frame at the same offset into the
next ON period (zombies shuffle a little in between). Writes an SDR H.264 intermediate, then
harness/encode-av1-hdr.sh makes the published AV1 10-bit PQ copy.

    harness/queue.sh submit media --label outline-video --out docs/media/occluded-outlines-off-vs-on.mp4 \\
        -- python3 harness/stitch-outlines.py harness/runs/outl-video-<ts> --crop x0,y0,x1,y1

Capture first: `--prop devCapture=<start>,<seconds>,<fps>,100,crop=x:y:w:h,ram` (1:1 crop of the screen).
"""
import argparse
import os
import re
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "ppl"))
from capture import load  # noqa: E402
from newcard import BG, INK, INK2, OPT, STOCK, font  # noqa: E402

PHASE = re.compile(r"occluded outlines: phase (on|off) at (\d+)")
GAP = 16
BAND = 96
FPS = 24


def periods(run):
    return [(int(m.group(2)), m.group(1)) for m in PHASE.finditer(open(os.path.join(run, "console.txt"), errors="replace").read())]


def pairs(stamps, ev, skip_ms):
    """(off frame, on frame) at the same offset into an OFF period and the ON period after it."""
    out = []
    for n, (e, k) in enumerate(ev):
        if k != "off" or n + 1 >= len(ev):
            continue
        e2 = ev[n + 1][0]
        end2 = ev[n + 2][0] if n + 2 < len(ev) else stamps[-1] + 1
        for i, t in enumerate(stamps):
            if e + skip_ms <= t < e2:
                j = int(np.argmin(np.abs(stamps - (e2 + (t - e)))))
                if e2 + skip_ms <= stamps[j] < end2:
                    out.append((i, j))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--out", default="docs/media/occluded-outlines-off-vs-on.mp4")
    ap.add_argument("--crop", default="", help="x0,y0,x1,y1 in capture pixels (default: the whole capture)")
    ap.add_argument("--pane", type=int, default=1280, help="pane width in the video")
    ap.add_argument("--loops", type=int, default=1)
    ap.add_argument("--fps", type=int, default=FPS, help="video rate (the capture's own rate plays in real time)")
    ap.add_argument("--skip-ms", type=int, default=120)
    ap.add_argument("--title", default="Occluded zombie outlines: off | on")
    ap.add_argument("--subtitle", default="same run, the setting switches every few seconds; frames paired at the same moment of each switch")
    ap.add_argument("--still", default="", help="write the first pair's frame to this PNG and stop (layout check)")
    a = ap.parse_args()
    frames, stamps = load(a.run)
    pr = pairs(stamps, periods(a.run), a.skip_ms)
    if not pr:
        sys.exit("no off / on frame pairs: run with --prop devOutlineAlternate=MS and a capture across the switches")
    fh, fw = frames.shape[1], frames.shape[2]
    crop = tuple(map(int, a.crop.split(","))) if a.crop else (0, 0, fw, fh)
    cw, ch = crop[2] - crop[0], crop[3] - crop[1]
    ph = round(a.pane * ch / cw)
    W = 2 * a.pane + GAP
    H = BAND + ph
    W += W % 2
    H += H % 2
    tmp = os.path.join(os.path.dirname(os.path.abspath(a.out)) or ".", ".outlines-src.mp4")
    ff = None if a.still else subprocess.Popen(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
                           "-r", str(a.fps), "-i", "-", "-c:v", "libx264", "-crf", "10", "-preset", "slow", "-pix_fmt", "yuv420p",
                           "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", tmp], stdin=subprocess.PIPE)
    base = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(base)
    d.text((24, 14), a.title, font=font(38, "bold"), fill=INK)
    d.text((24, 58), a.subtitle, font=font(24), fill=INK2)
    for _ in range(a.loops):
        for i, j in pr:
            im = base.copy()
            for col, k in ((0, i), (1, j)):
                fr = Image.fromarray(np.ascontiguousarray(frames[k])).crop(crop)
                if fr.size != (a.pane, ph):
                    fr = fr.resize((a.pane, ph), Image.LANCZOS)
                im.paste(fr, (col * (a.pane + GAP), BAND))
            dd = ImageDraw.Draw(im)
            for col, text, colour in ((0, "OFF", STOCK), (1, "ON", OPT)):
                x = col * (a.pane + GAP)
                dd.rectangle((x, BAND, x + 130, BAND + 64), fill=BG)
                dd.text((x + 18, BAND + 8), text, font=font(44, "bold"), fill=colour)
            if a.still:
                im.save(a.still)
                return
            ff.stdin.write(im.tobytes())
    ff.stdin.close()
    if ff.wait() != 0:
        sys.exit("ffmpeg failed")
    subprocess.run([os.path.join(HERE, "encode-av1-hdr.sh"), tmp, a.out], check=True)
    os.remove(tmp)
    print(f"{a.out}: {len(pr)} frame pairs x {a.loops} at {a.fps} fps, {W}x{H}")


if __name__ == "__main__":
    main()
