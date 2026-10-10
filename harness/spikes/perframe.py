#!/usr/bin/env python3
"""Per frame over N ms in the route window, the top async-profiler wall stacks of the game / render threads (leaf side).
Usage: perframe.py <run> [ms=25] [chain depth=5]; needs run.sh --asprof ...,wall=..."""
import collections, subprocess, sys, tempfile
from pathlib import Path
run = Path(sys.argv[1]); thr = float(sys.argv[2]) if len(sys.argv) > 2 else 25
J = "/home/diegov/.local/share/async-profiler/async-profiler-4.1-linux-x64/bin/jfrconv"
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
L = (run / "pzopt-overlay.out").read_text().splitlines(); h = L[0].split(",")
it, ie = h.index("frametime"), h.index("epoch_ms")
fr = []
for l in L[1:]:
    p = l.split(",")
    try: e, f = int(p[ie]), float(p[it])
    except (ValueError, IndexError): continue
    if A <= e <= B and f > thr: fr.append((int(e - f), e, f))
SK = ("java.", "jdk.", "Thread.", "Unsafe.park")
def ch(fs, n):
    g = [x.split("_[")[0] for x in fs[1:]]; g = [x for x in g if not x.startswith(SK)]
    return " < ".join(reversed(g[-n:]))
with tempfile.TemporaryDirectory() as td:
    out = Path(td) / "w.txt"
    for s, e, f in fr:
        if out.exists(): out.unlink()
        subprocess.run([J, "--wall", "-t", "--simple", "--from", str(s), "--to", str(e), "-o", "collapsed", str(run / "asprof.jfr"), str(out)], capture_output=True)
        th = collections.defaultdict(collections.Counter)
        for l in (out.read_text().splitlines() if out.exists() else []):
            st, n = l.rsplit(" ", 1); fs = st.split(";")
            t = fs[0]
            if not (t.startswith("[MainThread") or t.startswith("[main")): continue
            th[t][ch(fs, int(sys.argv[3]) if len(sys.argv) > 3 else 5)] += int(n)
        print(f"\n### +{(s - A)/1000:6.2f}s  {f:6.1f} ms")
        for t, c in th.items():
            tot = sum(c.values())
            pass
            print(f"  {t} ({tot} samples)")
            for k, n in c.most_common(3): print(f"    {n:3d} {k[:260]}")
