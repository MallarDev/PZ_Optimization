#!/usr/bin/env python3
"""Per-frame GPU-section costs of runs with --prop gpuSections=true, side by side (the 300 fps loop, 2026-10-09).

harness/spikes/sections.py [--gpu] [--top N] <label or run dir>...
Render-thread issue time (cpu_ns: begin to end issue) per frame by section, normalised by the `world` section's count
(once a frame), over the last 25.4 s of the log (the route); --gpu prints the GPU span instead. Nested sections overlap.
"""
import collections, glob, sys
from pathlib import Path

args = sys.argv[1:]
gpu = "--gpu" in args
top = 22
if "--top" in args:
    top = int(args[args.index("--top") + 1])
    del args[args.index("--top"):args.index("--top") + 2]
args = [a for a in args if a != "--gpu"]


def run_dir(x):
    p = Path(x)
    if p.is_dir():
        return p
    c = sorted(d for d in glob.glob(f"harness/runs/{x}-2026*") if Path(d).name[len(x) + 1:len(x) + 9].isdigit())
    return Path(c[-1])


cols = []
for a in args:
    d = run_dir(a)
    rows = []
    for l in open(d / "pzopt-gpusections.out"):
        if l.startswith("#"):
            continue
        p = l.split()
        if len(p) >= 4:
            rows.append((int(p[0]), p[1], int(p[2]), int(p[3])))
    rows.sort()
    end = rows[-1][0]
    start = end - 25_400_000_000
    v = collections.Counter()
    n = collections.Counter()
    for b, name, g, c in rows:
        if start <= b <= end:
            v[name] += g if gpu else c
            n[name] += 1
    frames = n["world"] or 1
    cols.append((a, {k: v[k] / frames / 1000 for k in v}, frames / 25.4, {k: n[k] / frames for k in n}))

keys = sorted({k for _, c, _, _ in cols for k in c}, key=lambda k: -max(c.get(k, 0) for _, c, _, _ in cols))[:top]
w = max(14, *(len(a) for a, _, _, _ in cols))
print(f"{'us/frame' + (' gpu' if gpu else ' issue'):20s}" + "".join(f"{a[-w:]:>{w + 2}s}" for a, _, _, _ in cols))
print(f"{'fps (instrumented)':20s}" + "".join(f"{fps:>{w + 2}.0f}" for _, _, fps, _ in cols))
for k in keys:
    print(f"{k:20s}" + "".join(f"{c.get(k, 0):>{w - 6}.0f} x{cnt.get(k, 0):5.2f}" for _, c, _, cnt in cols))
