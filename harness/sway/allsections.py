#!/usr/bin/env python3
"""Every GPU section of a devSwayAlternate run split by the sway's on / off half (gpuSections=true, instrument=true): each
section line takes the state of the frame's chunk composite (composite.swon / .swoff, the nearest before it in time),
summed per frame, mean per half; the first fifth skipped. Finds where an on-cost outside the composite lands.

    allsections.py <run dir> [--min-us 0.5] [--cpu]   (--cpu: the render thread's time from begin to end issue instead)"""
import collections
import os
import sys

run = sys.argv[1]
min_us = float(sys.argv[sys.argv.index("--min-us") + 1]) if "--min-us" in sys.argv else 0.5
rows = []
for line in open(os.path.join(run, "pzopt-gpusections.out")):
    p = line.split()
    if line.startswith("#") or len(p) < 3:
        continue
    rows.append((int(p[0]), p[1], int(p[3 if "--cpu" in sys.argv else 2]) / 1000.0))
rows.sort()
rows = rows[len(rows) // 5:]
# frames: a new frame starts at each "world" section (one a frame); its state is the composite's in that frame
frames = []
cur = None
for t, name, us in rows:
    if name == "world":
        cur = {"_state": None, "_sums": collections.defaultdict(float)}
        frames.append(cur)
    if cur is None:
        continue
    if name.startswith("composite.sw"):
        cur["_state"] = name[len("composite."):]
        name = "composite"
    if name.startswith("chunks.sw"):
        name = "chunks"
    cur["_sums"][name] += us
halves = {"swon": [], "swoff": []}
for f in frames:
    if f["_state"] in halves:
        halves[f["_state"]].append(f["_sums"])
names = sorted({n for h in halves.values() for s in h for n in s})
print(f"{os.path.basename(run.rstrip('/'))}: frames on {len(halves['swon'])} off {len(halves['swoff'])}")
out = []
for n in names:
    on = sum(s.get(n, 0.0) for s in halves["swon"]) / max(1, len(halves["swon"]))
    off = sum(s.get(n, 0.0) for s in halves["swoff"]) / max(1, len(halves["swoff"]))
    out.append((on - off, n, on, off))
for d, n, on, off in sorted(out, key=lambda r: -abs(r[0])):
    if abs(d) >= min_us:
        print(f"  {n:20s} on {on:8.1f} us  off {off:8.1f} us  delta {d:+7.1f}")
print(f"  sum of deltas {sum(r[0] for r in out):+.1f} us")
