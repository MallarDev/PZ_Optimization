#!/usr/bin/env python3
"""Game-thread wall stacks of the frames starting at the given route seconds, folded from the root side below landmark frames.
Usage: rootside.py <run> <s,s,...> [depth=4]; needs run.sh --asprof ...,wall=..."""
import collections, subprocess, sys, tempfile
from pathlib import Path
run = Path(sys.argv[1]); at = [float(x) for x in sys.argv[2].split(",")]; depth = int(sys.argv[3]) if len(sys.argv) > 3 else 4
J = "/home/diegov/.local/share/async-profiler/async-profiler-4.1-linux-x64/bin/jfrconv"
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A = int(kv["route_start_epoch_ms"])
L = (run / "pzopt-overlay.out").read_text().splitlines(); h = L[0].split(","); it, ie = h.index("frametime"), h.index("epoch_ms")
fr = []
for l in L[1:]:
    p = l.split(",")
    try: e, f = int(p[ie]), float(p[it])
    except: continue
    if any(abs((e - f - A) / 1000 - t) < 0.02 for t in at) and f > 15: fr.append((int(e - f), e, f))
LAND = ("GameWindow.logic", "GameWindow.renderInternal", "IngameState.updateInternal", "IsoWorld.updateInternal", "IsoCell.updateInternal", "IsoChunkMap.updateInternal",
        "FBORenderCell.renderTilesInternal", "FBORenderCell.performRenderTiles", "IsoCell.render", "IsoWorld.render")
with tempfile.TemporaryDirectory() as td:
    out = Path(td) / "w.txt"
    for s, e, f in fr:
        if out.exists(): out.unlink()
        subprocess.run([J, "--wall", "-t", "--simple", "--from", str(s), "--to", str(e), "-o", "collapsed", str(run / "asprof.jfr"), str(out)], capture_output=True)
        c = collections.Counter(); tot = 0
        for l in out.read_text().splitlines():
            st, n = l.rsplit(" ", 1); fs = [x.split("_[")[0] for x in st.split(";")]
            if not fs[0].startswith("[MainThread") or not any("GameWindow" in x for x in fs): continue
            n = int(n); tot += n
            idx = max([i for i, x in enumerate(fs) if x in LAND] or [0])
            tail = [x for x in fs[idx+1:] if not x.startswith(("java.", "jdk.", "Thread."))]
            c[" > ".join(tail[:depth])] += n
        print(f"\n### +{(s-A)/1000:.2f}s {f:.1f} ms, game thread {tot} samples")
        for k, n in c.most_common(8): print(f"  {n:3d} {k[:300]}")
