#!/usr/bin/env python3
"""Paired whole-frame cost from the overlay log (pzopt-overlay.out: epoch_ms, frametime ms, gpu_ms) of a devEntityShadowCycle
run: per cycle, each variant's median frame time (and GPU busy ms) minus the first variant's; median and IQR over cycles.
The first 30 % of each period is dropped."""
import os, re, statistics as st, sys
r = sys.argv[1]; skip = float(sys.argv[2]) if len(sys.argv) > 2 else 8.0
con = open(os.path.join(r, "console.txt"), errors="replace").read()
m = re.search(r"entity shadows: cycling ([\w,]+) every (\d+) ms from epoch_ms (\d+)", con)
order, P, t0 = m.group(1).split(","), int(m.group(2)), int(m.group(3))
rows = open(os.path.join(r, "pzopt-overlay.out")).read().split("\n")
hdr = rows[0].split(","); ix = {k: i for i, k in enumerate(hdr)}
per = {}
first = None
for row in rows[1:]:
    c = row.split(",")
    if len(c) < len(hdr): continue
    try:
        e = int(float(c[ix["epoch_ms"]])); ft = float(c[ix["frametime"]]); g = float(c[ix["gpu_ms"]])
    except ValueError:
        continue
    first = e if first is None else first
    if e - first < skip * 1000 or e < t0: continue
    k = (e - t0) // P; ph = (e - t0) % P
    if ph < 0.3 * P: continue
    per.setdefault(k, []).append((ft, g))
cyc = {}
for k, v in per.items():
    cyc.setdefault(k // len(order), {})[order[k % len(order)]] = (st.median([x[0] for x in v]), st.median([x[1] for x in v]), len(v))
diffs = {n: ([], []) for n in order[1:]}
base = []
for c in cyc.values():
    if order[0] not in c: continue
    b = c[order[0]]; base.append(b[0])
    for n in order[1:]:
        if n in c:
            diffs[n][0].append((c[n][0] - b[0]) * 1000); diffs[n][1].append((c[n][1] - b[1]) * 1000)
print(os.path.basename(r.rstrip("/")), "| %s frame %.3f ms (%.0f fps)" % (order[0], st.median(base), 1000 / st.median(base)))
for n in order[1:]:
    f, g = diffs[n]
    if len(f) < 3: continue
    q = st.quantiles(f, n=4)
    print("  %-8s cycles=%3d  frame %+6.1f us (IQR %+.1f..%+.1f)   gpu busy %+6.1f us" % (n, len(f), st.median(f), q[0], q[2], st.median(g)))
