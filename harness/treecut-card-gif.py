#!/usr/bin/env python3
"""Render docs/workshop/images/52-driving-trees.gif: the Workshop's "New! Driving through the trees" card in the animated New!
format (harness/newcard.py). Right half: the 120 km/h drive (E:1200, max zoom, uncapped) with Build 42.21's driving tree
cutaway as the previous release handled it against this release (docs/findings-4221-drive-regression-2026-10-03.md, runs
r4221-full-*, r4221-final-*, mac-r4221-*). Left half: both recordings at the same route second (previous release on top, `r4221-card-hotfix` below),
and under them both runs' frame times over the last 3 s; the clip is the previous release's slowest 3.5 s.

    harness/queue.sh submit media --label treecut-card-gif --out docs/workshop/images/52-driving-trees.gif \\
        -- python3 harness/treecut-card-gif.py
    (--still <png>: one frame, the layout check)
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, INK2, MUTED, OPT, RULE, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/52-driving-trees.gif"
RUNS = Path(os.environ.get("SMOOTH_RUNS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs")))
STOCK_RUN, NEW_RUN = "r4221-final-drive-rec-off", "r4221-card-hotfix"  # recorded; the table: unrecorded runs
FPS = 12
CLIP = 3.5          # seconds of the route shown (5 s was 9.4 MB)
TRACE = 3.0         # seconds of frame times on screen
YMAX = 12.0         # ms at the top of the trace
CROP = (2240, 540, 4800, 1620)  # right of the in-game overlay panel; the car sits near its left third
TONEMAP = ('zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,'
           'tonemap=hable,zscale=p=bt709:t=bt709:m=bt709,format=rgb24')  # centre half of the 5120x2160 capture: the car and the streets around it

INTRO = ("Driving is back to Build 42.20 speed. Build 42.21 turns every tree around your car see-through while you "
         "drive; with our baked trees each tree you passed re-baked its chunk picture and its neighbours' up to four "
         "times and was drawn again every frame. Now trees re-bake only when they really change, trees the cutaway cannot "
         "reach stay baked, and the 42.20 tree rule is the default.")
ROWS = [
    ("Frame rate, desktop", "120 km/h, max zoom, mean of 2 runs",
     (323, "323 fps"), (500, "500 fps"), "+55 %"),
    ("p99 frame time, desktop", "1 frame in 100 is slower than this",
     (9.25, "9.3 ms"), (5.65, "5.7 ms"), "-39 %"),
    ("Chunk pictures baked", "over the 39 s route",
     (31.4, "31.4k"), (17.4, "17.4k"), "-45 %"),
    ("Frame rate, MacBook M1 Pro", "same drive",
     (118, "118 fps"), (167, "167 fps"), "+42 %"),
    ("p99 frame time, Mac", "same drive",
     (21.2, "21.2 ms"), (16.4, "16.4 ms"), "-23 %"),
    ("On foot", "storm, camera spinning: unchanged",
     (272, "272 fps"), (272, "272 fps"), ("=", "same")),
]
FOOTER = [
    "Left: the previous release (top) and this release (bottom) at the same route second, and their frame times over the "
    "last 3 s (recorded runs, their slowest 3.5 s; the recorder costs ~15 %). Desktop: Linux, RTX 4090, 5120x2160, "
    "uncapped; Mac: MacBook Pro M1 Pro.",
    "Options > Optimizations > Trees: see-through round your car while driving (Build 42.21) brings back 42.21's rule; "
    "with it on the drive is still +25 % (405 fps) thanks to the fewer re-bakes.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-4221-drive-regression-2026-10-03.md.",
]


def run_dir(label):
    return sorted(RUNS.glob(label + "-2*"))[-1]


def route(run):
    """(route start epoch ms, route end epoch ms, route start in the recording, s)."""
    kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    a, b = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    sched = run / "schedule.log"
    m = re.search(r"route starts at \+(\d+) s", sched.read_text()) if sched.exists() else None
    if m:
        return a, b, int(m.group(1))
    # --no-mangohud runs have no schedule.log: the capture runs from launch to exit, so it began at the file's
    # modification time minus its duration (align() matches the two clips on the picture afterwards)
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                str(run / "recording.mp4")], capture_output=True, text=True).stdout)
    return a, b, max(0.0, a / 1000.0 - ((run / "recording.mp4").stat().st_mtime - dur))


def frametimes(run):
    """(ms since route start, frame ms) from the in-game overlay log."""
    a, b, _ = route(run)
    out = []
    for line in (run / "pzopt-overlay.out").read_text().splitlines()[1:]:
        p = line.split(",")
        try:
            e, ft = int(p[-1]), float(p[1])
        except (ValueError, IndexError):
            continue
        if a <= e <= b:
            out.append(((e - a) / 1000.0, ft))
    return out


def roughest(ft, length):
    """Start (s) of the window of the route where the previous release spent the most frame time."""
    end = ft[-1][0]
    best, at = -1, TRACE
    t = TRACE
    while t + length < end - 1:
        n = sum(v for s, v in ft if t <= s < t + length)
        if n > best:
            best, at = n, t
        t += 0.5
    return at


def video_frames(run, t0, n, w, h, work, tag):
    start = route(run)[2]
    d = work / tag
    d.mkdir()
    x0, y0, x1, y1 = CROP
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-ss", f"{start + t0:.3f}", "-i", str(run / "recording.mp4"),
                    "-t", f"{n / FPS:.3f}", "-vf", f"fps={FPS},crop={x1 - x0}:{y1 - y0}:{x0}:{y0},{TONEMAP},scale={w}:{h}:flags=lanczos",
                    "-frames:v", str(n), str(d / "%04d.png")], check=True)
    return [Image.open(p).convert("RGB") for p in sorted(d.glob("*.png"))]


def gray(run, at, n, fps):
    """n grayscale 160x68 frames of the recording from `at` s at `fps` (numpy array)."""
    import numpy as np
    x0, y0, x1, y1 = CROP
    raw = subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-ss", f"{max(0.0, at):.3f}", "-i", str(run / "recording.mp4"),
                          "-vf", f"fps={fps},crop={x1 - x0}:{y1 - y0}:{x0}:{y0},scale=160:68,format=gray", "-frames:v", str(n),
                          "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, 68, 160).astype(np.float32)


def align(stock_run, new_run, t0):
    """Seconds to add to the release run's route time so its picture matches the stock one at route time t0: the route
    start in schedule.log is a whole second and the capture starts before the game window, so the recordings are
    matched on the picture itself (least mean squared difference over +-2 s at 24 fps)."""
    ref = gray(stock_run, route(stock_run)[2] + t0, 1, 24)[0]
    cand = gray(new_run, route(new_run)[2] + t0 - 2.0, 96, 24)
    err = [float(((c - ref) ** 2).mean()) for c in cand]
    k = min(range(len(err)), key=err.__getitem__)
    return -2.0 + k / 24.0


def live_fps(ft, t):
    n = sum(1 for s, _ in ft if t - 1.0 < s <= t)
    return n


def draw_trace(d, box, t, series):
    x, y, w, h = box
    d.rounded_rectangle((x, y, x + w, y + h), radius=6, fill=BG)
    lab = font(18)
    px = lambda ms: y + h - 8 - (h - 34) * min(ms, YMAX) / YMAX  # noqa: E731
    for ms, _ in ((2.0, "500 fps"), (4.17, "240 fps"), (8.33, "120 fps")):
        yy = px(ms)
        d.line((x + 8, yy, x + w - 8, yy), fill=RULE, width=1)
    d.text((x + 12, y + 16), "frame time, last 3 s", font=lab, fill=INK2, anchor="lm")
    cols = w - 16
    for ft, colour in series:
        lo = t - TRACE
        peak = [0.0] * cols
        for s, v in ft:
            if lo <= s <= t:
                c = min(cols - 1, int((s - lo) / TRACE * cols))
                peak[c] = max(peak[c], v)
        pts = [(x + 8 + c, px(v)) for c, v in enumerate(peak) if v > 0]
        if len(pts) > 1:
            d.line(pts, fill=colour, width=2)
    for ms, text in ((2.0, "2 ms = 500 fps"), (4.17, "4.2 ms = 240 fps"), (8.33, "8.3 ms = 120 fps")):
        yy = px(ms)
        tw = lab.getlength(text)
        d.rounded_rectangle((x + 10, yy - 12, x + 20 + tw, yy + 10), radius=4, fill=BG)
        d.text((x + 15, yy - 1), text, font=lab, fill=MUTED, anchor="lm")


def main():
    stock_run, new_run = run_dir(STOCK_RUN), run_dir(NEW_RUN)
    ft_s, ft_n = frametimes(stock_run), frametimes(new_run)
    card = Card("New! Driving through the trees", "2026-10-03", INTRO, ROWS, FOOTER, cols=("BEFORE", "NOW"))
    x, y, w, h = card.media
    vh = round(w * (CROP[3] - CROP[1]) / (CROP[2] - CROP[0]) / 2) * 2
    trace = (x, y + 2 * vh + 20, w, h - 2 * vh - 20)
    t0 = roughest(ft_s, CLIP)
    still = sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--still" else None
    n = 1 if still else round(CLIP * FPS)
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-treecut-"))
    dn = align(stock_run, new_run, t0)
    ft_n = [(s_ - dn, v) for s_, v in ft_n]  # the release run on the stock run's route clock
    vs = video_frames(stock_run, t0, n, w, vh, work, "s")
    vn = video_frames(new_run, t0 + dn, n, w, vh, work, "n")
    chip = font(22, "bold")
    for k in range(min(len(vs), len(vn))):
        t = t0 + k / FPS
        im = card.base()
        im.paste(vs[k], (x, y))
        im.paste(vn[k], (x, y + vh + 10))
        d = ImageDraw.Draw(im)
        for text, colour, yy, ft in (("PREVIOUS RELEASE", STOCK, y, ft_s), ("THIS RELEASE", OPT, y + vh + 10, ft_n)):
            label = f"{text}  {live_fps(ft, t)} fps"
            tw = chip.getlength(label)
            d.rounded_rectangle((x + 8, yy + 8, x + 28 + tw, yy + 42), radius=6, fill=BG)
            d.text((x + 18, yy + 25), label, font=chip, fill=colour, anchor="lm")
        draw_trace(d, trace, t, ((ft_s, STOCK), (ft_n, OPT)))
        if still:
            im.save(still)
            print(f"wrote {still} (route {t0:.1f} s, release run shifted {dn:+.2f} s)")
            shutil.rmtree(work)
            return
        im.save(work / f"{k + 1:04d}.png")
    write_gif(str(work), FPS, OUT)
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
