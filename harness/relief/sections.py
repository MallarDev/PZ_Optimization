#!/usr/bin/env python3
"""The chunk composite pass's own GPU time in the on / off halves of a devReliefAlternate run (gpuSections=true,
instrument=true: pzopt-gpusections.out, one line per section pair). Medians and means per section name, first fifth skipped.

    sections.py <run dir>..."""
import collections
import os
import statistics
import sys

for r in sys.argv[1:]:
    f = os.path.join(r, "pzopt-gpusections.out")
    if not os.path.exists(f):
        print(r, ": no pzopt-gpusections.out")
        continue
    d = collections.defaultdict(list)
    for line in open(f):
        p = line.split()
        if line.startswith("#") or len(p) < 3:
            continue
        d[p[1]].append(int(p[2]) / 1000.0)
    print(os.path.basename(r.rstrip("/")))
    rows = {}
    for k, v in sorted(d.items()):
        if k.endswith(".rlon") or k.endswith(".rloff"):
            v = v[len(v) // 5:]
            rows[k] = (statistics.median(v), statistics.mean(v))
            print("  %-22s n=%6d median %7.1f us  mean %7.1f us" % (k, len(v), rows[k][0], rows[k][1]))
    on = [k for k in rows if k.endswith(".rlon")]
    for k in on:
        off = k[:-5] + ".rloff"
        if off in rows:
            print("  %s cost: median %+.1f us, mean %+.1f us" % (k[:-5], rows[k][0] - rows[off][0], rows[k][1] - rows[off][1]))
