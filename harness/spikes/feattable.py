#!/usr/bin/env python3
"""Per-Enhancement cost table (harness/spikes/ablate.sh feat-* runs, the defaults + one feature as the tab file sets it).

harness/spikes/feattable.py <suffix>... [--base def] [--variants v1,v2,...] [--route rw|hw|rs]
Each variant's runs under the given suffixes (e.g. e1 e2) averaged: fps, frame ms, the frame time it adds against the base
(ms and %), p99, and the game thread / render thread / GPU loads of the in-game overlay log.
"""
import glob, re, subprocess, sys
from pathlib import Path

args = sys.argv[1:]
base = "def"
if "--base" in args:
    base = args[args.index("--base") + 1]
    del args[args.index("--base"):args.index("--base") + 2]
prefix = "rw"
if "--route" in args:
    prefix = args[args.index("--route") + 1]
    del args[args.index("--route"):args.index("--route") + 2]
variants = None
if "--variants" in args:
    variants = args[args.index("--variants") + 1].split(",")
    del args[args.index("--variants"):args.index("--variants") + 2]
suffixes = args
if variants is None:
    found = set()
    for s in suffixes:
        for d in glob.glob(f"harness/runs/abl-{prefix}-*-{s}-2026*"):
            m = re.match(rf"abl-{prefix}-(.+)-{s}-\d{{8}}-\d{{6}}$", Path(d).name)
            if m:
                found.add(m.group(1))
    variants = sorted(found, key=lambda v: (v != base, v))


def stats(d):
    out = subprocess.run(["python3", "harness/analyze.py", d], capture_output=True, text=True).stdout
    lines = out.splitlines()
    for i, l in enumerate(lines):
        if l.startswith("overlay:"):
            fps = float(re.search(r"([0-9.]+) fps mean", l).group(1))
            p99 = float(re.search(r"p99 ([0-9.]+)ms", lines[i + 1]).group(1))
            u = "\n".join(lines[i:i + 4])
            g = re.search(r"game_load (\d+)%", u)
            r = re.search(r"render_load (\d+)%", u)
            gp = re.search(r"gpu_load (\d+)%", u)
            return fps, p99, int(g.group(1)) if g else 0, int(r.group(1)) if r else 0, int(gp.group(1)) if gp else 0
    return None


rows = {}
for v in variants:
    vals = []
    for s in suffixes:
        for d in sorted(glob.glob(f"harness/runs/abl-{prefix}-{v}-{s}-2026*")):
            st = stats(d)
            if st:
                vals.append(st)
    if vals:
        n = len(vals)
        rows[v] = [sum(x[k] for x in vals) / n for k in range(5)] + [n, [round(x[0]) for x in vals]]

if base not in rows:
    sys.exit(f"no {base} runs")
bms = 1000.0 / rows[base][0]
print(f"{'variant':16s} {'fps':>6s} {'runs':>14s} {'ms':>6s} {'+ms':>6s} {'+%':>6s} {'p99':>6s} {'game':>5s} {'rend':>5s} {'gpu':>5s}")
for v, (fps, p99, g, r, gp, n, each) in sorted(rows.items(), key=lambda kv: kv[1][0], reverse=True):
    ms = 1000.0 / fps
    print(f"{v:16s} {fps:6.1f} {str(each):>14s} {ms:6.2f} {ms - bms:+6.2f} {100 * (ms - bms) / bms:+6.0f} {p99:6.1f} {g:4.0f}% {r:4.0f}% {gp:4.0f}%")
