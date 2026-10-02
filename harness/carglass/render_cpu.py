#!/usr/bin/env python3
"""Render-thread time per frame (pzopt-pacing.out: acquire -> swap call) split by the car glass alternation.

    render_cpu.py <run dir>..."""
import re, statistics as st, sys
for run in sys.argv[1:]:
    con = open(f"{run}/console.txt", errors="replace").read()
    m = re.search(r"car glass: alternating every (\d+) ms from epoch_ms (\d+)", con)
    if not m:
        print(run, "no alternation"); continue
    P, T0 = int(m.group(1)), int(m.group(2))
    d = {"on": [], "off": []}
    for line in open(f"{run}/pzopt-pacing.out"):
        if line.startswith("#"):
            continue
        c = line.split()
        if len(c) < 4:
            continue
        e, acq, sw = int(c[0]), int(c[2]), int(c[3])
        dt = e - T0
        if dt < 10000 or (dt % P) < 0.15 * P or (dt % P) > 0.85 * P or acq == 0:
            continue
        d["on" if (dt // P) % 2 == 0 else "off"].append((sw - acq) / 1000.0)
    on, off = d["on"], d["off"]
    print(run.rstrip("/").split("/")[-1], "render thread acquire->swap: on median %.1f us mean %.1f | off median %.1f us mean %.1f | delta median %+.1f mean %+.1f us"
          % (st.median(on), st.mean(on), st.median(off), st.mean(off), st.median(on) - st.median(off), st.mean(on) - st.mean(off)))
