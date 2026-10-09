#!/usr/bin/env python3
"""Per-frame transients in a gray devCapture sequence: the puddle-reflection flicker rig (2026-10-01).

A pixel is a transient at frame t when it jumps from its previous value and jumps back the next frame
(|f[t] - f[t-1]| > thresh, |f[t] - f[t+1]| > thresh, |f[t+1] - f[t-1]| < thresh / 2): a camera moving at a
steady pace never reverts, a surface alternating between two sources every frame does. Prints per-second
totals, the busiest frames, a block map of where it happens, and a heat-map PNG; --png dumps frames.

  python3 harness/glass-flicker.py <run> [--thresh 24] [--heat out.png] [--png dir --every 10] [--json f]
"""
import argparse, json, os, sys
import numpy as np


def load(run):
    cap = os.path.join(run, "capture")
    head = open(os.path.join(cap, "index.txt")).readline().split()
    kv = dict(p.split("=") for p in head)
    w, h = int(kv["w"]), int(kv["h"])
    gray = kv.get("fmt") == "gray"
    stamps = [int(x) for x in open(os.path.join(cap, "index.txt")).read().split("\n")[1:] if x.strip()]
    if gray:
        data = np.fromfile(os.path.join(cap, "frames.gray"), dtype=np.uint8)
        n = len(data) // (w * h)
        frames = data[: n * w * h].reshape(n, h, w)[:, ::-1, :]  # GL rows are bottom-up
    else:
        data = np.fromfile(os.path.join(cap, "frames.rgba"), dtype=np.uint8)
        n = len(data) // (w * h * 4)
        rgba = data[: n * w * h * 4].reshape(n, h, w, 4)[:, ::-1, :, :]
        frames = ((rgba[..., 0].astype(np.uint32) * 77 + rgba[..., 1].astype(np.uint32) * 150 + rgba[..., 2].astype(np.uint32) * 29) >> 8).astype(np.uint8)
    return frames, stamps[:n]


def aligned(src, ref, r):
    """src shifted onto ref by the best whole-pixel (dx, dy) within +-r, judged on a central crop; returns the shifted
    array (edges wrap, the caller trims r px) and the shift."""
    h, w = ref.shape
    cy, cx = h // 2, w // 2
    hh, hw = min(160, h // 4), min(240, w // 4)
    rc = ref[cy - hh:cy + hh, cx - hw:cx + hw]
    best = None
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            sc = src[cy - hh - dy:cy + hh - dy, cx - hw - dx:cx + hw - dx]
            e = np.abs(rc - sc).mean()
            if best is None or e < best[0]:
                best = (e, dx, dy)
    _, dx, dy = best
    return np.roll(np.roll(src, dy, axis=0), dx, axis=1), (dx, dy)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--thresh", type=int, default=24)
    ap.add_argument("--align", type=int, default=0, help="motion compensation: search +-N px for the camera shift between frames (walking captures)")
    ap.add_argument("--block", type=int, default=80, help="block size of the where-map")
    ap.add_argument("--heat")
    ap.add_argument("--png", help="directory for frame PNGs")
    ap.add_argument("--every", type=int, default=10)
    ap.add_argument("--json")
    a = ap.parse_args()
    frames, stamps = load(a.run)
    n, h, w = frames.shape
    if n < 3:
        print(f"{a.run}: only {n} frames"); sys.exit(1)
    f = frames.astype(np.int16)
    t0 = stamps[0] if stamps else 0
    secs = [(s - t0) / 1000.0 for s in stamps] if stamps else [i / 30.0 for i in range(n)]
    dur = secs[-1] - secs[0] if n > 1 else 0
    print(f"{a.run}: {n} frames {w}x{h}, {dur:.1f} s ({(n - 1) / max(dur, 1e-3):.1f} fps)")
    T = a.thresh
    heat = np.zeros((h, w), dtype=np.int32)
    per_frame = []
    shifts = []
    for t in range(1, n - 1):
        if a.align:
            # the camera moves while the player walks: align both neighbours onto frame t by the best whole-pixel
            # shift of a central crop (search +-align px), then test the overlap only
            prev, (dxp, dyp) = aligned(f[t - 1], f[t], a.align)
            nxt, (dxn, dyn) = aligned(f[t + 1], f[t], a.align)
            shifts.append((dxp, dyp, dxn, dyn))
            m = a.align
            cur = f[t][m:-m, m:-m]; prev = prev[m:-m, m:-m]; nxt = nxt[m:-m, m:-m]
            d1 = np.abs(cur - prev); d2 = np.abs(cur - nxt); d3 = np.abs(nxt - prev)
            tr = np.zeros((h, w), dtype=bool)
            tr[m:-m, m:-m] = (d1 > T) & (d2 > T) & (d3 < T // 2)
        else:
            d1 = np.abs(f[t] - f[t - 1]); d2 = np.abs(f[t] - f[t + 1]); d3 = np.abs(f[t + 1] - f[t - 1])
            tr = (d1 > T) & (d2 > T) & (d3 < T // 2)
        heat += tr
        per_frame.append(int(tr.sum()))
    if shifts:
        sh = np.array(shifts)
        print(f"camera shift per frame (px): prev->cur median dx {np.median(sh[:, 0]):.0f} dy {np.median(sh[:, 1]):.0f}, max |d| {np.abs(sh).max()}")
    per_frame = np.array(per_frame)
    # per second
    print("transient px per frame, by second:")
    buckets = {}
    for i, c in enumerate(per_frame):
        s = int(secs[i + 1]) if i + 1 < len(secs) else 0
        buckets.setdefault(s, []).append(c)
    for s in sorted(buckets):
        v = buckets[s]
        print(f"  t={s:2d}s  mean {np.mean(v):7.0f}  max {max(v):7d}  frames {len(v)}")
    top = np.argsort(per_frame)[::-1][:5]
    print("busiest frames:", ", ".join(f"#{i + 1} ({secs[i + 1]:.2f}s) {per_frame[i]} px" for i in top))
    # where
    B = a.block
    bh, bw = h // B, w // B
    bm = heat[: bh * B, : bw * B].reshape(bh, B, bw, B).sum(axis=(1, 3))
    print(f"where (sum of transient px per {B}x{B} block over the sequence; rows top to bottom):")
    for r in range(bh):
        print("  " + " ".join(f"{int(v):6d}" for v in bm[r]))
    out = {"frames": n, "seconds": dur, "per_frame": per_frame.tolist(), "mean": float(per_frame.mean()), "p95": float(np.percentile(per_frame, 95)),
           "max": int(per_frame.max()), "total": int(per_frame.sum()), "blocks": bm.tolist()}
    print(f"total {out['total']} transient px, mean {out['mean']:.0f}/frame, p95 {out['p95']:.0f}, max {out['max']}")
    if a.heat:
        from PIL import Image
        base = frames[n // 2].astype(np.float32) * 0.5
        rgb = np.stack([base, base, base], axis=-1)
        hm = np.clip(heat.astype(np.float32) * (255.0 / max(1, heat.max())) * 3, 0, 255)
        rgb[..., 0] = np.maximum(rgb[..., 0], hm)
        Image.fromarray(rgb.astype(np.uint8)).save(a.heat)
        print("heat map:", a.heat)
    if a.png:
        from PIL import Image
        os.makedirs(a.png, exist_ok=True)
        for i in range(0, n, a.every):
            Image.fromarray(frames[i]).save(os.path.join(a.png, f"f{i:04d}.png"))
        print("frames:", a.png)
    if a.json:
        json.dump(out, open(a.json, "w"))


if __name__ == "__main__":
    main()
