#!/usr/bin/env python3
"""One-frame whole-picture brightness dips in a devCapture sequence (2026-10-02, the flip's "heavy flickering of the whole
scene while driving": the HDR light map built mid chunk-map shift took the sun gain away for one frame, ~1 per second).

    frame-dips.py <run>... [--level 3]

A dip is a frame whose mean luma is more than --level (luma levels, 0..255) below both neighbours; a peak the same
upwards. Prints per run the frames, the seconds covered, dips and peaks and their rate. Any capture works (gray, a 6-12 %
scale at the full frame rate is enough: the dips are whole-picture). Rig: harness/CLAUDE.md, "Whole-frame dips".
"""
import argparse
import os

import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--level", type=float, default=3.0)
    a = ap.parse_args()
    for r in a.runs:
        d = os.path.join(r, "capture")
        lines = open(os.path.join(d, "index.txt")).read().split("\n")
        head = dict(kv.split("=") for kv in lines[0].split() if "=" in kv)
        w, h = int(head["w"]), int(head["h"])
        st = np.array([int(x) for x in lines[1:] if x.strip().isdigit() and int(x) > 10 ** 12], dtype=np.int64)  # (a last line cut short at exit dropped)
        gray = os.path.exists(os.path.join(d, "frames.gray"))
        ch = 1 if gray else 4
        raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
        n = min(len(st), raw.size // (w * h * ch))
        m = np.empty(n)
        for i in range(n):
            f = raw[i * w * h * ch:(i + 1) * w * h * ch].reshape(h, w, ch)
            m[i] = f[..., 0].mean() if gray else (f[..., :3] * np.array([0.299, 0.587, 0.114])).sum(axis=2).mean()
        dips = [i for i in range(1, n - 1) if m[i] < m[i - 1] - a.level and m[i] < m[i + 1] - a.level]
        peaks = [i for i in range(1, n - 1) if m[i] > m[i - 1] + a.level and m[i] > m[i + 1] + a.level]
        secs = max(1e-3, (st[n - 1] - st[0]) / 1000.0) if n > 1 else 1e-3
        print(f"{os.path.basename(r.rstrip('/'))}: frames={n} over {secs:.0f} s, dips={len(dips)} ({len(dips) / secs:.2f}/s), "
              f"peaks={len(peaks)}, mean luma {m.mean():.0f}")


if __name__ == "__main__":
    main()
