#!/usr/bin/env python3
"""Render docs/workshop/images/53-dynamic-resolution.gif: the Workshop's "New! Dynamic resolution" card in the animated
New! format (harness/newcard.py), release 3bd4ae3 (docs/findings-dynamic-resolution-2026-10-03.md).

Left half, top: the same still scene on the flip (Radeon 890M, 1920x1080, zoom 1) while the render size is forced from
60 % to 100 % and back every second (`devDynResForce=60,100,2,square`), with a spatial upscaler (FSR 1.0, runs
`dr-card-fsr1`) and with this release's temporal one (taau, `dr-card-taau`); 1:1 devCapture crops shown 2x, each with
its frame's render size. Bottom: frame times under a heavy GPU load at a 240 fps cap, dynamic resolution off
(`dr-sq-off4`) vs on (`dr-sq-model10-taau`), the same 4 s of each route. Right half: the measured numbers.

    harness/queue.sh submit media --label dynres-card-gif --out docs/workshop/images/53-dynamic-resolution.gif \\
        -- python3 harness/dynres-card-gif.py
    (--still <png>: one frame, the layout check)
"""
import bisect
import os
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, INK2, MUTED, OPT, RULE, STOCK, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/53-dynamic-resolution.gif"
RUNS = Path(os.environ.get("DYNRES_RUNS", os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs")))
CAP_FSR, CAP_TAAU = "flip-dr-card-fsr1", "flip-dr-card-taau"
TRACE_OFF, TRACE_ON = "dr-sq-off4", "dr-sq-model10-taau"
FPS = 12
SECONDS = 4.0
CROP = (int(os.environ.get("CARD_CX", "525")), int(os.environ.get("CARD_CY", "318")))  # centre of the source window in the 960x540 capture
SRC_W, SRC_H, ZOOM = 131, 230, 2
BIN_S = 0.25        # the trace: frame rate per quarter second over the route
FPS_MIN, FPS_MAX = 100.0, 260.0

INTRO = ("Dynamic resolution (Options > Enhancements, off by default): when the GPU cannot hold your frame-rate cap, the "
         "world renders smaller for as long as it needs to and grows back when there is room; the UI stays sharp. It learns "
         "what the pixels cost, so chunk-loading spikes and CPU-bound scenes keep full size, and a new temporal upscaler "
         "builds the picture over several frames, so a size change is hard to see.")
ROWS = [
    ("Frame rate, heavy GPU load", "desktop, 240 fps cap",
     (184, "184 fps"), (224, "224 fps"), "+22 %"),
    ("Frames late", "over 1.5x the cap's frame time",
     (28.0, "28.0 %"), (7.3, "7.3 %"), "-74 %"),
    ("p99.9 frame time", "1 frame in 1000 is slower",
     (25.6, "25.6 ms"), (18.2, "18.2 ms"), "-29 %"),
    ("A size change, worst frame", "detail jump between 2 frames",
     (87, "87 % FSR"), (30, "30 % taau"), "-66 %"),
    ("Laptop, all Enhancements on", "890M, 120 fps cap: render size",
     (None, "67 % fixed"), (None, "50-100 %"), ("same fps", "same")),
    ("Scenes the GPU handles", "120 km/h drive: render size",
     (None, "100 %"), (None, "100 %"), ("=", "same")),
]
FOOTER = [
    "Left: the same spot on a laptop while the render size jumps between 60 % and 100 % every second (forced, to show the "
    "change), with FSR 1.0 and with the new temporal upscaler, 1:1 pixels shown 2x; below, the frame rate per quarter second while a heavy "
    "GPU load switches on and off every 3 s, dynamic resolution off and on. Desktop: Linux, RTX 4090, 5120x2160; laptop: Radeon 890M, 1920x1080.",
    "Every number and the runs behind them: github.com/xD3I/PZ_Optimization, docs/findings-dynamic-resolution-2026-10-03.md.",
]
COLS = ("OFF", "DYNAMIC")


def run_dir(label):
    hits = sorted(RUNS.glob(label + "-2*"))
    if not hits:
        sys.exit(f"no run {label}-* under {RUNS}")
    return hits[-1]


def capture(run):
    """(frames as HxWx3 uint8, top-down; epoch ms per frame; (epochs, scales) of the dynres log)."""
    d = run / "capture"
    lines = (d / "index.txt").read_text().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = [int(x) for x in lines[1:] if x.strip()]
    raw = np.fromfile(d / "frames.rgba", dtype=np.uint8)
    n = min(len(stamps), raw.size // (w * h * 4))
    frames = raw[: n * w * h * 4].reshape(n, h, w, 4)[:, ::-1, :, :3]
    te, sc = [], []
    for line in (run / "pzopt-dynres.out").read_text().splitlines():
        if line.startswith("#"):
            continue
        p = line.split()
        if len(p) >= 3:
            te.append(int(p[0]))
            sc.append(float(p[2]))
    return frames, stamps[:n], (te, sc)


def scale_at(log, epoch):
    te, sc = log
    i = bisect.bisect_right(te, epoch + 12) - 1  # rows are written a few frames after their frame was shown
    return sc[i] if 0 <= i < len(sc) else 1.0


def frametimes(run):
    """(s since route start, frame ms) from the in-game overlay log over the route window."""
    kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    a, b = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
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


def panel(frame, scale, title, color, w, h):
    """The crop shown ZOOM x with nearest neighbour, the title above, the live render size in a badge."""
    cx, cy = CROP
    x0, y0 = cx - SRC_W // 2, cy - SRC_H // 2
    img = Image.fromarray(np.ascontiguousarray(frame[y0:y0 + SRC_H, x0:x0 + SRC_W])).resize((SRC_W * ZOOM, SRC_H * ZOOM), Image.NEAREST)
    out = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(out)
    d.text((0, 2), title, font=font(19, "semibold"), fill=color, anchor="lt")
    out.paste(img, (0, 30))
    badge = f"render size {round(scale * 100)} %"
    f = font(19, "semibold", mono=True)
    bw = int(f.getlength(badge)) + 16
    bx, by = 8, 30 + SRC_H * ZOOM - 34
    d.rounded_rectangle((bx, by, bx + bw, by + 26), radius=6, fill="#000000")
    d.text((bx + 8, by + 13), badge, font=f, fill=OPT if scale < 0.99 else INK, anchor="lm")
    return out


def fps_bins(pts):
    """Frames presented per BIN_S over the route, as fps."""
    if not pts:
        return []
    end = pts[-1][0]
    n = int(end / BIN_S)
    counts = [0] * (n + 1)
    for s, _ in pts:
        counts[min(n, int(s / BIN_S))] += 1
    return [((i + 0.5) * BIN_S, c / BIN_S) for i, c in enumerate(counts[:n])]


def draw_trace(d, box, series):
    """Frame rate over the route (heavy GPU load switching on and off every 3 s), both runs, the 240 fps cap."""
    x, y, w, h = box
    d.rounded_rectangle((x, y, x + w, y + h), radius=6, fill=BG, outline=RULE)
    d.text((x + 12, y + 10), "frame rate under a heavy GPU load, desktop", font=font(19, "semibold"), fill=INK2, anchor="lt")
    gx0, gx1, gy0, gy1 = x + 12, x + w - 12, y + 44, y + h - 40
    end = max(s for _, pts, _ in series for s, _ in pts)
    def gy(fps):
        return gy1 - (max(FPS_MIN, min(fps, FPS_MAX)) - FPS_MIN) / (FPS_MAX - FPS_MIN) * (gy1 - gy0)
    def gx(t):
        return gx0 + t / end * (gx1 - gx0)
    for f in (120, 180):
        d.line((gx0, gy(f), gx1, gy(f)), fill=RULE, width=1)
        d.text((gx0 + 2, gy(f) - 2), f"{f}", font=font(15), fill=MUTED, anchor="lb")
    cap = gy(240)
    d.line((gx0, cap, gx1, cap), fill=MUTED, width=1)
    d.text((gx1 - 2, cap - 3), "240 fps cap", font=font(15), fill=MUTED, anchor="rb")
    for _, pts, color in series:
        xy = [(gx(t), gy(v)) for t, v in pts]
        if len(xy) > 1:
            d.line(xy, fill=color, width=3)
    lx = x + 12
    for name, _, color in series:
        d.text((lx, gy1 + 12), name, font=font(17, "semibold"), fill=color, anchor="lt")
        lx += int(font(17, "semibold").getlength(name)) + 28
    d.text((gx1, gy1 + 12), f"{int(end)} s route", font=font(15), fill=MUTED, anchor="rt")


def main():
    still = sys.argv[sys.argv.index("--still") + 1] if "--still" in sys.argv else None
    card = Card("New! Dynamic resolution", "2026-10-03", INTRO, ROWS, FOOTER, cols=COLS)
    base = card.base()
    mx, my, mw, mh = card.media
    fr_f, st_f, log_f = capture(run_dir(CAP_FSR))
    fr_t, st_t, log_t = capture(run_dir(CAP_TAAU))
    off, on = fps_bins(frametimes(run_dir(TRACE_OFF))), fps_bins(frametimes(run_dir(TRACE_ON)))
    pw = SRC_W * ZOOM
    ph = 30 + SRC_H * ZOOM
    gap = mw - 2 * pw
    n = int(SECONDS * FPS)
    cap_fps = len(st_f) / max(1e-3, (st_f[-1] - st_f[0]) / 1000.0)
    skip = max(0, int(1.0 * cap_fps))  # the first second of the capture
    work = Path(tempfile.mkdtemp(prefix="dynres-card-"))
    try:
        for k in range(n):
            t = k / FPS
            i_f = min(len(st_f) - 1, skip + int(t * cap_fps))
            # the taau frame shown at the same phase of the forced square wave (both runs force the same 2 s period)
            phase_epoch = st_f[i_f] - st_f[0]
            i_t = min(len(st_t) - 1, bisect.bisect_left(st_t, st_t[0] + phase_epoch))
            frame = base.copy()
            frame.paste(panel(fr_f[i_f], scale_at(log_f, st_f[i_f]), "FSR 1.0 (spatial)", STOCK, pw, ph), (mx, my))
            frame.paste(panel(fr_t[i_t], scale_at(log_t, st_t[i_t]), "taau (this release)", OPT, pw, ph), (mx + pw + gap, my))
            d = ImageDraw.Draw(frame)
            ty = my + ph + 16
            draw_trace(d, (mx, ty, mw, my + mh - ty), [("off", off, STOCK), ("dynamic resolution", on, OPT)])
            if still:
                frame.save(still)
                print("still:", still)
                return
            frame.save(work / f"{k:04d}.png")
        write_gif(str(work), FPS, OUT)
        print(OUT, os.path.getsize(OUT), "bytes")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
