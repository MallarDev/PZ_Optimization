#!/usr/bin/env python3
"""Paired cost: per cycle (one period of each variant in order), each variant's median minus the first variant's median in
the same cycle; prints the median and IQR of those paired differences (drift between cycles cancels)."""
import collections, os, re, statistics as st, sys
r = sys.argv[1]; skip = float(sys.argv[2]) if len(sys.argv) > 2 else 6.0
con = open(os.path.join(r, "console.txt"), errors="replace").read()
order = re.search(r"entity shadows: cycling ([\w,]+) every", con).group(1).split(",")
periods = []  # (name, [gpu], [cpu])
t0 = None
for line in open(os.path.join(r, "pzopt-gpusections.out"), errors="replace"):
    c = line.split()
    if len(c) < 4 or not c[1].startswith("moving.es"):
        continue
    b, name, g, cpu = int(c[0]), c[1][9:], int(c[2]) / 1e3, int(c[3]) / 1e3
    t0 = b if t0 is None else t0
    if (b - t0) / 1e9 < skip:
        continue
    if not periods or periods[-1][0] != name:
        periods.append((name, [], []))
        continue  # first frame of a period
    periods[-1][1].append(g); periods[-1][2].append(cpu)
# align cycles at the first period of order[0]
i = next(k for k, p in enumerate(periods) if p[0] == order[0])
diffs = collections.defaultdict(lambda: ([], []))
while i + len(order) <= len(periods):
    cyc = periods[i:i + len(order)]
    if [p[0] for p in cyc] != order or any(len(p[1]) < 3 for p in cyc):
        i += 1; continue
    bg, bc = st.median(cyc[0][1]), st.median(cyc[0][2])
    for p in cyc[1:]:
        diffs[p[0]][0].append(st.median(p[1]) - bg); diffs[p[0]][1].append(st.median(p[2]) - bc)
    i += len(order)
base = [st.median(p[1]) for p in periods if p[0] == order[0] and len(p[1]) > 2]
print(os.path.basename(r.rstrip("/")), "| %s gpu median %.1f us" % (order[0], st.median(base)))
for k in order[1:]:
    g, c = diffs[k]
    if not g: continue
    q = st.quantiles(g, n=4) if len(g) > 3 else [min(g), 0, max(g)]
    print("  %-8s cycles=%3d  gpu %+6.1f us (IQR %+.1f..%+.1f)   render thread %+6.1f us" % (k, len(g), st.median(g), q[0], q[2], st.median(c)))
