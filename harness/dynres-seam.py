#!/usr/bin/env python3
"""How visible render-scale changes are (dynamic resolution, pzopt.DynRes): reads a devCapture sequence of a still
scene (<run>/capture/frames.gray|rgba + index.txt, best a 1:1 crop) and the run's pzopt-dynres.out.

    harness/dynres-seam.py <run> [<run> ...] [--skip S]

Per run:
  sharp     mean Laplacian RMS of the luma (detail; higher = sharper) and its variation over time relative to its mean
            (pump: the image going soft / sharp with the scale; a still scene at a fixed scale gives the noise floor)
  sharp@lo / sharp@hi   mean sharpness of the frames rendered in the lowest / highest third of the scale range
  diff      mean absolute luma change between consecutive captured frames (temporal noise: shimmer, jitter, pops)
  pop       mean diff of the frames right after a scale change over the median diff of all frames (1.0 = invisible)
Frames are matched to scales by epoch ms (the dynres row of the frame, logged a few frames late: the scale in force
at the capture is the latest row whose frame number was rendered before the capture's epoch, approximated by epoch
minus --lag-ms).
"""
import argparse
import bisect
import os
import sys

import numpy as np


def load_capture(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    stamps = [int(x) for x in lines[1:] if x.strip()]
    gray = os.path.exists(os.path.join(d, "frames.gray"))
    if gray:
        raw = np.fromfile(os.path.join(d, "frames.gray"), dtype=np.uint8)
        n = min(len(stamps), raw.size // (w * h))
        frames = raw[: n * w * h].reshape(n, h, w)[:, ::-1].astype(np.float32)
    else:
        raw = np.fromfile(os.path.join(d, "frames.rgba"), dtype=np.uint8)
        n = min(len(stamps), raw.size // (w * h * 4))
        f = raw[: n * w * h * 4].reshape(n, h, w, 4)[:, ::-1, :, :3].astype(np.float32)
        frames = f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114
    return frames, np.array(stamps[:n])


def load_scales(run):
    t, s = [], []
    p = os.path.join(run, "pzopt-dynres.out")
    if not os.path.exists(p):
        return t, s
    for line in open(p):
        if line.startswith("#"):
            continue
        f = line.split()
        if len(f) >= 3:
            t.append(int(f[0]))
            s.append(float(f[2]))
    return t, s


def laplacian_rms(img):
    lap = -4.0 * img[1:-1, 1:-1] + img[:-2, 1:-1] + img[2:, 1:-1] + img[1:-1, :-2] + img[1:-1, 2:]
    return float(np.sqrt(np.mean(lap * lap)))


def report(run, skip, lag_ms):
    frames, stamps = load_capture(run)
    if skip > 0 and len(stamps):
        keep = stamps >= stamps[0] + skip * 1000
        frames, stamps = frames[keep], stamps[keep]
    n = len(frames)
    if n < 3:
        print(f"{run}: too few frames ({n})")
        return
    st, ss = load_scales(run)
    scale = []
    for e in stamps:
        i = bisect.bisect_right(st, int(e) + lag_ms) - 1
        scale.append(ss[i] if 0 <= i < len(ss) else float("nan"))
    scale = np.array(scale)
    sharp = np.array([laplacian_rms(f) for f in frames])
    diff = np.array([0.0] + [float(np.mean(np.abs(frames[i] - frames[i - 1]))) for i in range(1, n)])
    med = float(np.median(diff[1:]))
    changed = [i for i in range(1, n) if np.isfinite(scale[i]) and np.isfinite(scale[i - 1]) and abs(scale[i] - scale[i - 1]) > 1e-3]
    pop = float(np.mean(diff[changed]) / med) if changed and med > 0 else float("nan")
    fin = np.isfinite(scale)
    lo_s, hi_s = (np.nanmin(scale), np.nanmax(scale)) if fin.any() else (float("nan"), float("nan"))
    span = hi_s - lo_s
    lo = sharp[fin & (scale <= lo_s + span / 3)] if span > 1e-3 else np.array([])
    hi = sharp[fin & (scale >= hi_s - span / 3)] if span > 1e-3 else np.array([])
    name = os.path.basename(run.rstrip("/"))
    print(f"== {name}: {n} frames {frames.shape[2]}x{frames.shape[1]}, scale {lo_s:.3f}..{hi_s:.3f}, {len(changed)} captured frames after a change")
    print(f"   sharp  mean {sharp.mean():.3f}  pump (std/mean) {sharp.std() / sharp.mean() * 100:.2f} %"
          + (f"  sharp@lo {lo.mean():.3f}  sharp@hi {hi.mean():.3f}  (lo/hi {lo.mean() / hi.mean() * 100:.1f} %)" if len(lo) and len(hi) else ""))
    jumps = np.abs(np.diff(sharp)) / sharp.mean() * 100.0
    print(f"   diff   mean {diff[1:].mean():.3f}  median {med:.3f}  pop {pop:.2f}x   sharpness jump between frames p50 {np.percentile(jumps, 50):.2f} % p99 {np.percentile(jumps, 99):.2f} % max {jumps.max():.2f} %")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--skip", type=float, default=2.0, help="seconds at the start of the capture to leave out")
    ap.add_argument("--lag-ms", type=int, default=12, help="dynres rows are logged this much after their frame was shown")
    a = ap.parse_args()
    for r in a.runs:
        report(r, a.skip, a.lag_ms)


if __name__ == "__main__":
    sys.exit(main())
