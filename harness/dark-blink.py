#!/usr/bin/env python3
"""Dark one-frame blinks inside a screen crop of a devCapture sequence (2026-09-29, the Fossoil shelves report: items on
the store shelves went black every other frame while the player walked circles).

    harness/dark-blink.py <run> [--crop x,y,w,h | --screen] [--start S] [--end E] [--strip out.png] [--json f]

A pixel blinks dark in frame i when it is near black (<= 40/255) and more than 50 darker than both neighbours (i-1, i+1),
which agree within 20: a per-frame drop to black and back, not camera motion or film grain. The crop is in capture pixels, top-left origin (the
capture is read back bottom-up and flipped here). Prints px/frame, frames with >= 20 px, and the longest run of
alternating frames; --strip writes the worst frame with its neighbours, 3x. --screen: the whole frame with the HUD (top bar,
left icon column, bottom row, the pzopt overlay in the bottom-right corner) and a disc round the walking player masked, for
walks that move the camera (explore=circle director=jev); then the console's `harness: circle: spot` events split the
numbers per spot.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np


def load(run):
    cap = Path(run) / "capture"
    idx = (cap / "index.txt").read_text().split()
    hdr = dict(kv.split("=") for kv in idx[0:3] if "=" in kv)
    w, h = int(hdr["w"]), int(hdr["h"])
    stamps = np.array([int(s) for s in idx[3:]], dtype=np.int64)
    gray = cap / "frames.gray"
    if gray.exists():
        d = np.fromfile(gray, dtype=np.uint8)
        d = np.memmap(gray, dtype=np.uint8, mode="r")
        f = d[: d.size // (w * h) * w * h].reshape(-1, h, w)
    else:
        d = np.memmap(cap / "frames.rgba", dtype=np.uint8, mode="r")
        f = d[: d.size // (w * h * 4) * w * h * 4].reshape(-1, h, w, 4)
    return f, stamps[: len(f)]


def luma(f, i, y, x, h, w):
    """Frame i, flipped to a top-left origin, cropped, as int16 luma."""
    H = f.shape[1]
    a = f[i, H - y - h:H - y][::-1, x:x + w]
    if a.ndim == 3:
        a = a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114
    return a.astype(np.int16)


def screen_mask(w, h):
    m = np.ones((h, w), dtype=bool)
    m[: int(h * 0.03)] = False                     # top bars
    m[:, : int(w * 0.045)] = False                 # left icon column
    m[int(h * 0.965):] = False                     # bottom row, hotbar
    m[int(h * 0.74):, int(w * 0.735):] = False     # pzopt overlay (overlayCorner=br)
    m[: int(h * 0.12), int(w * 0.9):] = False      # speed buttons, clock
    yy, xx = np.mgrid[0:h, 0:w]
    m &= (xx - w / 2) ** 2 + (yy - h / 2) ** 2 > (0.05 * w) ** 2
    return m


def spot_events(run):
    import re
    ev = []
    for line in (Path(run) / "console.txt").read_text(errors="replace").splitlines():
        m = re.search(r"harness: circle: spot (\d+) at (\S+) epoch_ms=(\d+)", line)
        if m:
            ev.append((int(m.group(3)), int(m.group(1)), m.group(2)))
    return ev


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--crop", default="720,280,220,150")
    ap.add_argument("--screen", action="store_true")
    ap.add_argument("--start", type=float, default=0)
    ap.add_argument("--end", type=float, default=1e9)
    ap.add_argument("--strip")
    ap.add_argument("--json")
    a = ap.parse_args()
    f, ts = load(a.run)
    H, W = f.shape[1], f.shape[2]
    x, y, w, h = (0, 0, W, H) if a.screen else (int(v) for v in a.crop.split(","))
    mask = screen_mask(w, h) if a.screen else np.ones((h, w), dtype=bool)
    t = (ts - ts[0]) / 1000.0 if len(ts) else np.arange(len(f)) / 240.0
    sel = np.where((t >= a.start) & (t <= a.end))[0]
    px = np.zeros(len(sel), dtype=np.int64)
    prev = cur = None
    for k, i in enumerate(sel):
        nxt = luma(f, i, y, x, h, w)
        if prev is not None:
            px[k - 1] = ((cur <= 40) & (prev - cur > 50) & (nxt - cur > 50) & (np.abs(prev - nxt) < 20) & mask).sum()
        prev, cur = cur, nxt
    hit = px >= 20
    run_len = best = 0
    for i in range(1, len(hit)):  # alternating blink: hit, miss, hit ...
        run_len = run_len + 1 if hit[i] and not hit[i - 1] or hit[i - 1] and not hit[i] else 0
        best = max(best, run_len)
    res = {"run": str(a.run), "frames": int(len(sel)), "crop": "screen" if a.screen else a.crop, "dark_blink_px_per_frame": float(px.mean()),
           "frames_ge20": int(hit.sum()), "share_frames_ge20": float(hit.mean()), "p99_px": float(np.percentile(px, 99)),
           "max_px": int(px.max()), "longest_alternation": int(best)}
    if a.screen and len(ts):
        ev = spot_events(a.run)
        st = ts[sel]
        per = []
        for j, (ms, n, at) in enumerate(ev):
            end = ev[j + 1][0] if j + 1 < len(ev) else 1 << 62
            k = (st >= ms) & (st < end)
            if k.sum() > 2:
                per.append({"spot": n, "at": at, "frames": int(k.sum()), "px_per_frame": float(px[k].mean()), "frames_ge20": int(hit[k].sum())})
        res["per_spot"] = per
        for s_ in per:
            print(f"  spot {s_['spot']} at {s_['at']}: {s_['frames']} frames, {s_['px_per_frame']:.2f} px/frame, {s_['frames_ge20']} frames >= 20 px")
    print(" ".join(f"{k}={v:.3f}" if isinstance(v, float) else f"{k}={v}" for k, v in res.items() if k != "per_spot"))
    if a.json:
        Path(a.json).write_text(json.dumps(res, indent=1))
    if a.strip:
        from PIL import Image
        i = int(np.argmax(px))
        i = min(max(i, 2), len(sel) - 3)
        s = np.concatenate([luma(f, sel[j], y, x, h, w) for j in range(i - 2, i + 3)], axis=1).clip(0, 255).astype(np.uint8)
        Image.fromarray(s).resize((s.shape[1] * 3, s.shape[0] * 3), Image.NEAREST).save(a.strip)
    return 0


if __name__ == "__main__":
    sys.exit(main())
