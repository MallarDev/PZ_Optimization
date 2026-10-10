#!/usr/bin/env python3
"""Entity shadows: every GPU section's cost per devEntityShadowCycle variant (not only the moving pass).

    sections.py <run dir> [--skip S] [--top N]

Each line of pzopt-gpusections.out takes the variant of the last moving.es<variant> section before it (a period's first
frame dropped); per section name and variant the mean GPU and render-thread us a frame (summed over the frame's lines),
against the cycle's first variant."""
import collections, os, re, sys

args = sys.argv[1:]
skip, top = 6.0, 25
if "--skip" in args:
    i = args.index("--skip"); skip = float(args[i + 1]); del args[i:i + 2]
if "--top" in args:
    i = args.index("--top"); top = int(args[i + 1]); del args[i:i + 2]
r = args[0]
con = open(os.path.join(r, "console.txt"), errors="replace").read()
order = re.search(r"entity shadows: cycling ([\w,]+) every", con).group(1).split(",")
# frames: a frame = the lines between two moving.es sections (their variant = the later one's... use the earlier one)
sums = collections.defaultdict(lambda: collections.defaultdict(lambda: [0.0, 0.0]))
frames = collections.Counter()
t0, cur, prev = None, None, None
for line in open(os.path.join(r, "pzopt-gpusections.out"), errors="replace"):
    if line.startswith("#"):
        continue
    c = line.split()
    if len(c) < 4:
        continue
    b, name, g, cpu = int(c[0]), c[1], int(c[2]), int(c[3])
    t0 = b if t0 is None else t0
    if name.startswith("moving.es"):
        v = name[len("moving.es"):]
        prev, cur = cur, v
        name = "moving"
        if (b - t0) / 1e9 >= skip and prev == cur:
            frames[cur] += 1
    if cur is None or prev != cur or (b - t0) / 1e9 < skip:
        continue
    s = sums[cur][name]
    s[0] += g / 1e3
    s[1] += cpu / 1e3
base = order[0]
names = sorted({n for v in sums for n in sums[v]}, key=lambda n: -max(abs(sums[v][n][0] / max(1, frames[v]) - sums[base][n][0] / max(1, frames[base])) for v in sums))
print("%-28s" % "section (us/frame)" + "".join("%22s" % v for v in order))
for n in names[:top]:
    row = "%-28s" % n[:28]
    for v in order:
        g = sums[v][n][0] / max(1, frames[v]); c = sums[v][n][1] / max(1, frames[v])
        row += "  %8.1f/%8.1f%s" % (g, c, "  " if v == base else "")
    print(row)
tot = {v: (sum(x[0] for x in sums[v].values()) / max(1, frames[v]), sum(x[1] for x in sums[v].values()) / max(1, frames[v])) for v in order}
print("%-28s" % "TOTAL gpu/render" + "".join("  %8.1f/%8.1f" % tot[v] for v in order))
print("frames", dict(frames))
