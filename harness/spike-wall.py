#!/usr/bin/env python3
"""Where the time of the long frames goes, from an async-profiler wall-clock recording (run.sh --asprof ...,wall=5ms).

For every frame over --spike ms in the route window (after --skip seconds), the game thread's and the render thread's
wall samples are folded; the report sums them over all spike frames: the game thread's leaf chains (what it ran or
waited in) and, when it waited at the frame hand-off, the render thread's leaf chains.
Usage: spike-wall.py RUN [--spike 50] [--skip 5] [--top 12] [--jfrconv PATH]
"""
import argparse, collections, os, subprocess, tempfile
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--spike", type=float, default=50)
ap.add_argument("--skip", type=float, default=5)
ap.add_argument("--top", type=int, default=12)
ap.add_argument("--event", default="wall", choices=("wall", "cpu"), help="sample kind of the recording: wall (wall=...) or cpu (event=cpu; on-CPU time only)")
ap.add_argument("--below", type=float, default=0, help="instead of the long frames, the frames under this many ms (a baseline to compare against)")
ap.add_argument("--jfrconv", default=os.path.expanduser("~/.local/share/async-profiler/async-profiler-4.1-linux-x64/bin/jfrconv"))
a = ap.parse_args()
run = Path(a.run)
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
L = (run / "pzopt-overlay.out").read_text().splitlines()
h = L[0].split(",")
it, ie = h.index("frametime"), h.index("epoch_ms")
frames = []
for l in L[1:]:
    p = l.split(",")
    try:
        e, f = int(p[ie]), float(p[it])
    except (ValueError, IndexError):
        continue
    if A + a.skip * 1000 <= e <= B and ((f < a.below) if a.below > 0 else (f > a.spike)):
        frames.append((int(e - f), e, f))
SKIP = ("java.", "jdk.", "libc", "/usr/lib/libc", "Object.wait", "Thread.", "Unsafe.park")


def chain(fr, n=3):
    g = [x.split("_[")[0] for x in fr[1:]]
    g = [x for x in g if not x.startswith(SKIP)]
    return " < ".join(reversed(g[-n:]))


game, rend = collections.Counter(), collections.Counter()
gtot = rtot = 0
with tempfile.TemporaryDirectory() as td:
    out = Path(td) / "w.txt"
    for s, e, f in frames:
        subprocess.run([a.jfrconv, "--" + a.event, "-t", "--simple", "--from", str(s), "--to", str(e), "-o", "collapsed",
                        str(run / "asprof.jfr"), str(out)], capture_output=True, timeout=120)
        if not out.exists():
            continue
        for l in out.read_text().splitlines():
            st, n = l.rsplit(" ", 1)
            fr = st.split(";")
            n = int(n)
            # native threads started from the game thread inherit its name: only the one running Java frames counts
            if fr[0].startswith("[MainThread") and any(x.endswith("_[j]") for x in fr):
                game[chain(fr)] += n
                gtot += n
            elif fr[0].startswith("[main"):
                rend[chain(fr)] += n
                rtot += n
print(f"{run.name}: {len(frames)} frames {'< %.0f' % a.below if a.below > 0 else '> %.0f' % a.spike} ms after the first {a.skip:.0f} s, {sum(f for _, _, f in frames):.0f} ms in them")
for name, c, tot in (("game thread", game, gtot), ("render thread", rend, rtot)):
    print(f"  {name} ({tot} {a.event} samples):")
    for k, n in c.most_common(a.top):
        print(f"    {100 * n / max(1, tot):5.1f}% {k[:180]}")
