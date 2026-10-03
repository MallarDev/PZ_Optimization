#!/usr/bin/env python3
"""Mirrors GPU cost from a devMirrorsAlternate / devMirrorsCycle run.

    cost.py <run dir>... [--skip S]

The console line 'mirrors: dev alternating every P ms from epoch_ms 0 (on first)[, cycle a,b,c]' gives the clock; each
overlay frame (pzopt-overlay.out: epoch_ms, gpu_ms, frametime) falls in an entry (on / off, or the cycle's entries);
the first S seconds (default 8) and the first 15 % of each period are dropped. Prints per entry the median / mean whole-frame
GPU time and frame time and the difference against 'off'. With gpuSections=true + instrument=true the mirrors.* sections
are summed per frame too (pzopt-gpusections.out)."""
import collections, os, re, statistics as st, sys

args = [a for a in sys.argv[1:]]
skip = 8.0
if "--skip" in args:
    i = args.index("--skip"); skip = float(args[i + 1]); del args[i:i + 2]
for r in args:
    print(os.path.basename(r.rstrip("/")))
    con = open(os.path.join(r, "console.txt"), errors="replace").read()
    m = re.search(r"mirrors: dev alternating every (\d+) ms from epoch_ms 0 \(on first\)(?:, cycle ([\w,]+))?", con)
    if not m:
        print("  no alternation line"); continue
    P = int(m.group(1))
    names = m.group(2).split(",") if m.group(2) else ["on", "off"]
    rows = open(os.path.join(r, "pzopt-overlay.out")).read().split("\n")
    hdr = rows[0].split(","); ix = {k: i for i, k in enumerate(hdr)}
    data = collections.defaultdict(lambda: ([], []))
    t0 = None
    for row in rows[1:]:
        c = row.split(",")
        if len(c) < len(hdr):
            continue
        try:
            e = int(float(c[ix["epoch_ms"]])); g = float(c[ix["gpu_ms"]]); ft = float(c[ix["frametime"]])
        except (ValueError, KeyError):
            continue
        t0 = e if t0 is None else t0
        if e - t0 < skip * 1000 or (e % P) < 0.15 * P:
            continue
        k = names[(e // P) % len(names)]
        data[k][0].append(g); data[k][1].append(ft)
    base = data.get("off")
    for k in names:
        if k not in data or not data[k][0]:
            continue
        g, ft = data[k]
        line = "  %-6s frames %5d gpu median %.4f mean %.4f ms | frame median %.3f mean %.3f ms" % (k, len(g), st.median(g), st.mean(g), st.median(ft), st.mean(ft))
        if base and k != "off" and base[0]:
            line += "   gpu vs off: median %+6.1f mean %+6.1f us" % ((st.median(g) - st.median(base[0])) * 1000, (st.mean(g) - st.mean(base[0])) * 1000)
        print(line)
    f = os.path.join(r, "pzopt-gpusections.out")
    if os.path.exists(f):
        per = collections.defaultdict(lambda: collections.defaultdict(float))
        for line in open(f):
            p = line.split()
            if line.startswith("#") or len(p) < 3 or not p[1].startswith("mirrors."):
                continue
            per[p[1]][p[0]] += int(p[2]) / 1000.0
        for k in sorted(per):
            v = list(per[k].values())[len(per[k]) // 5:]
            if v:
                print("  %-22s frames %6d  median %7.2f us  mean %7.2f us" % (k, len(v), st.median(v), st.mean(v)))
