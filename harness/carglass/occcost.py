#!/usr/bin/env python3
"""Car occupant cost from a devCarGlassAlternate + devCarGlassCycle run (gpuSections=true, instrument=true).

    occcost.py <run dir>...

Per cycle entry (".cg<entry>"): the GPU time of the moving objects pass ("moving": the cars, their glass, the characters)
and of the occupant pass ("carOccupant": the occupants drawn into their tiles, on the frames it ran), both per frame of that
entry, against the "off" entry (glass off) and against entry 1024 (glass on, no occupant pass); the render thread's time per
frame (pzopt-pacing.out: acquire -> swap call) per entry; the frame time per entry. The first fifth of the run is skipped."""
import collections, os, re, statistics as st, sys


def entries(con):
    m = re.search(r"car glass: alternating every (\d+) ms from epoch_ms (\d+)(?: cycle (\S+))?", con)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), (m.group(3) or "on,off").split(",")


for r in sys.argv[1:]:
    con = open(os.path.join(r, "console.txt"), errors="replace").read()
    cyc = entries(con)
    print(os.path.basename(r.rstrip("/")))
    for line in re.findall(r"car occupant: mode .*", con)[-1:]:
        print("  " + line)
    sec = collections.defaultdict(list)
    f = os.path.join(r, "pzopt-gpusections.out")
    frames = collections.Counter()
    if os.path.exists(f):
        rows = [l.split() for l in open(f) if not l.startswith("#")]
        rows = rows[len(rows) // 5:]
        for p in rows:
            if len(p) < 3:
                continue
            name, us = p[1], int(p[2]) / 1000.0
            base, _, tag = name.partition(".cg")
            if tag:
                sec[(base, tag)].append(us)
                if base == "moving":
                    frames[tag] += 1
    tags = sorted({t for (_, t) in sec}, key=lambda t: (t != "off", t))
    print("  %-8s %7s %12s %12s %14s" % ("entry", "frames", "moving us", "occupant us", "sum vs off/1024"))
    tot = {}
    for t in tags:
        mv = sec.get(("moving", t), [])
        oc = sec.get(("carOccupant", t), [])
        n = frames[t] or 1
        m = st.mean(mv) if mv else 0.0
        o = sum(oc) / n
        tot[t] = m + o
        print("  %-8s %7d %12.1f %12.1f" % (t, frames[t], m, o), end="")
        print("   %+7.1f / %+7.1f" % (tot[t] - tot.get("off", tot[t]), tot[t] - tot.get("1024", tot[t])))
    pf = os.path.join(r, "pzopt-pacing.out")
    if cyc and os.path.exists(pf):
        P, T0, names = cyc
        rt = collections.defaultdict(list)
        ft = collections.defaultdict(list)
        lines = [l.split() for l in open(pf) if not l.startswith("#")]
        lines = lines[len(lines) // 5:]
        prev = None
        for c in lines:
            if len(c) < 4:
                continue
            e, acq, sw = int(c[0]), int(c[2]), int(c[3])
            if acq == 0:
                continue
            ph = (e - T0) % P
            if ph < 0.15 * P or ph > 0.85 * P:
                prev = None
                continue
            tag = names[((e - T0) // P) % len(names)]
            rt[tag].append((sw - acq) / 1000.0)
        for t in sorted(rt, key=lambda t: (t != "off", t)):
            v = rt[t]
            print("  render thread %-6s median %7.1f us mean %7.1f us (n %d)" % (t, st.median(v), st.mean(v), len(v)))
