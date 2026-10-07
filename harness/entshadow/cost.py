#!/usr/bin/env python3
"""Entity shadows: the cost of each method from one devEntityShadowCycle run.

    cost.py <run dir>... [--skip S] [--section moving]

The run cycles entityShadowMethod per period (--prop devEntityShadowCycle=cpu,pixel,... --prop devEntityShadowAlternate=MS)
with --prop gpuSections=true --prop instrument=true: the moving objects' GPU section is tagged per method
("moving.es<method>", pzopt-gpusections.out: begin_cpu_ns name gpu_ns cpu_ns). Prints per method the section's GPU time and
render-thread time (median, mean, p90) and the difference against the first method of the cycle; the first S seconds
(default 6) are dropped, and the first frame after each switch (it may straddle the change)."""
import collections, os, re, statistics as st, sys

args = sys.argv[1:]
skip, section = 6.0, "moving"
if "--skip" in args:
    i = args.index("--skip"); skip = float(args[i + 1]); del args[i:i + 2]
if "--section" in args:
    i = args.index("--section"); section = args[i + 1]; del args[i:i + 2]


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q * len(v)))]


for r in args:
    print(os.path.basename(r.rstrip("/")))
    con = open(os.path.join(r, "console.txt"), errors="replace").read()
    m = re.search(r"entity shadows: cycling ([\w,]+) every (\d+) ms", con)
    order = m.group(1).split(",") if m else []
    data = collections.defaultdict(lambda: ([], []))
    t0, last = None, None
    for line in open(os.path.join(r, "pzopt-gpusections.out"), errors="replace"):
        if line.startswith("#"):
            continue
        c = line.split()
        if len(c) < 4 or not c[1].startswith(section + ".es"):
            continue
        b, name, g, cpu = int(c[0]), c[1][len(section) + 3:], int(c[2]), int(c[3])
        t0 = b if t0 is None else t0
        if (b - t0) / 1e9 < skip:
            last = name
            continue
        if name != last:
            last = name  # the first frame of a period
            continue
        data[name][0].append(g / 1e3)
        data[name][1].append(cpu / 1e3)
    if not data:
        print("  no %s.es* sections (gpuSections=true instrument=true and a devEntityShadowCycle?)" % section)
        continue
    base = order[0] if order and order[0] in data else sorted(data)[0]
    bg, bc = st.median(data[base][0]), st.median(data[base][1])
    for k in order or sorted(data):
        if k not in data:
            continue
        g, c = data[k]
        print("  %-10s n=%5d  gpu us median %7.1f mean %7.1f p90 %7.1f  (%+6.1f)   render thread us median %7.1f mean %7.1f (%+6.1f)" % (
            k, len(g), st.median(g), st.mean(g), pct(g, 0.9), st.median(g) - bg, st.median(c), st.mean(c), st.median(c) - bc))
