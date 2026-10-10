#!/usr/bin/env python3
"""Per frame over N ms in the route window (pzopt-pacing.out): interval, game step, render submit, GPU, bakes of the next step by kind.
Usage: table.py <run> <ms>"""
import bisect, sys, collections
from pathlib import Path
run = Path(sys.argv[1]); thr = float(sys.argv[2])
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
rows = [list(map(int, l.split())) for l in (run / "pzopt-pacing.out").read_text().splitlines() if l and not l.startswith("#")]
rows = [r for r in rows if A <= r[0] <= B]
br = [list(map(int, l.split())) for l in (run / "pzopt-bakes.out").read_text().splitlines() if l and l[0].isdigit()]
bns = [r[0] for r in br]
def bakes(s1, s2):
    lo, hi = bisect.bisect_left(bns, s1), bisect.bisect_left(bns, s2)
    t = [0]*7
    for r in br[lo:hi]:
        for k in range(7): t[k] += r[k+1]
    return t
N = ["cr","ob","cu","tr","re","li","ot"]
blame = collections.Counter(); n = 0
print(" t(s)   interval  step  submit  gpu   bakes(next step)")
for i in range(1, len(rows) - 2):
    e, sim, acq, sc, sr, w, gd, _ = rows[i]
    iv = (sr - rows[i-1][4]) / 1e6
    if iv < thr: continue
    n += 1
    step = (rows[i+1][1] - sim) / 1e6; sub = (sc - acq - w) / 1e6; gpu = (gd - sc) / 1e6 if gd else -1
    b = bakes(rows[i+1][1], rows[i+2][1]); bs = " ".join(f"{N[k]}={b[k]}" for k in range(7) if b[k])
    blame["step" if step >= sub else "submit"] += 1
    print(f"{(e-A)/1000:6.2f} {iv:7.1f} {step:6.1f} {sub:6.1f} {gpu:5.1f}   {bs}")
print(n, "frames >", thr, "ms; bigger of step/submit:", dict(blame))
