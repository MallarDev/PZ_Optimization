#!/usr/bin/env python3
"""Who calls a method on the game thread: the caller chains above a frame in pzopt-stacks.out, summed over runs.

    harness/callers.py <run> [<run> ...] --frame IsoCell.getGridSquare [--up 3] [--min-pct 0.1] [--all]

Each line is one distinct chain of --up frames above the matched frame (nearest caller last), with its share of the
route-window samples. Stacks that contain the frame more than once count once, at the outermost match.
"""
import argparse
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from flamegraph import route_window  # noqa: E402  (the route window subtree.py uses)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--frame", required=True)
    ap.add_argument("--up", type=int, default=3)
    ap.add_argument("--min-pct", type=float, default=0.1)
    ap.add_argument("--all", action="store_true")
    a = ap.parse_args()
    chains = collections.Counter()
    total = 0
    hit = 0
    for run in a.runs:
        path = os.path.join(run, "pzopt-stacks.out")
        if not os.path.exists(path):
            print(f"{path} not found", file=sys.stderr)
            continue
        win = None if a.all else route_window(run)
        names = {}
        on = False
        for line in open(path):
            if line.startswith("#"):
                continue
            if line.startswith("f "):
                _, i, n = line.rstrip("\n").split(" ", 2)
                names[i] = n
                continue
            if line.startswith("t "):
                t = int(line.split()[1])
                on = win is None or win[0] <= t <= win[1]
                continue
            if not on:
                continue
            ids, n = line.rsplit(" ", 1)
            n = int(n)
            frames = [names.get(x, x) for x in ids.split(";")]
            total += n
            for k, f in enumerate(frames):
                if f == a.frame:
                    hit += n
                    chains[" > ".join(frames[max(0, k - a.up):k])] += n
                    break
    if total == 0:
        print("no samples")
        return
    print(f"{a.frame}: {100.0 * hit / total:.1f}% of {total} samples")
    for c, n in chains.most_common():
        p = 100.0 * n / total
        if p < a.min_pct:
            break
        print(f"  {p:5.1f}%  {c}")


if __name__ == "__main__":
    main()
