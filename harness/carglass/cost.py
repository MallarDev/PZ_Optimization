#!/usr/bin/env python3
"""Car glass GPU cost from a devCarGlassAlternate run (gpuSections=true, instrument=true: pzopt-gpusections.out).

    cost.py <run dir>...

Sections "moving.cg<tag>": the vehicles and characters per alternation state (on / off, or devCarGlassCycle's entries);
"carGlassProbe": the probe pass (on the frames that march). Each tag against "off": the moving pass delta, plus the probe
pass averaged over the frames; first fifth skipped."""
import collections, os, statistics, sys

for r in sys.argv[1:]:
    f = os.path.join(r, "pzopt-gpusections.out")
    if not os.path.exists(f):
        print(r, ": no pzopt-gpusections.out"); continue
    d = collections.defaultdict(list)
    for line in open(f):
        p = line.split()
        if line.startswith("#") or len(p) < 3:
            continue
        d[p[1]].append(int(p[2]) / 1000.0)
    print(os.path.basename(r.rstrip("/")))
    tags = {}
    for k, v in sorted(d.items()):
        v = v[len(v) // 5:]
        if not v:
            continue
        if k.startswith("moving.cg"):
            tags[k[len("moving.cg"):]] = (statistics.median(v), statistics.mean(v), len(v))
        elif k.startswith("carGlass"):
            print("  %-16s n=%6d median %7.1f us mean %7.1f us" % (k, len(v), statistics.median(v), statistics.mean(v)))
    probe = d.get("carGlassProbe", [])
    probe = probe[len(probe) // 5:]
    off = tags.get("off")
    for t, (med, mean, n) in tags.items():
        line = "  moving.cg%-6s n=%6d median %7.1f us mean %7.1f us" % (t, n, med, mean)
        if off and t != "off":
            line += "   vs off: median %+6.1f mean %+6.1f us" % (med - off[0], mean - off[1])
        print(line)
    if probe and off:
        on_frames = sum(n for t, (_, _, n) in tags.items() if t != "off")
        print("  probe pass averaged over the glass frames: %.1f us" % (sum(probe) / max(1, on_frames)))
