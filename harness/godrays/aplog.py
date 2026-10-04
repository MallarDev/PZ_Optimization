#!/usr/bin/env python3
"""The light volumes' aperture log of a run with --prop devGodRaysApLog=N: per window / doorway the side the light comes
from, whether a light volume was built, its cap (how far it reaches into the room), the door glass, and the apertures the
roof rule closed (their outside square and what covers it).

    aplog.py <run> [--build N]   (default: the last logged prism build)
"""
import argparse
import os
import re


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--build", type=int, default=0)
    a = ap.parse_args()
    lines = open(os.path.join(a.run, "console.txt"), errors="replace").read().splitlines()
    builds, cur, covered = {}, None, []
    for ln in lines:
        m = re.search(r"god rays: prism build (\d+) (.*)", ln)
        if m:
            cur = int(m.group(1))
            builds[cur] = [m.group(2)]
            continue
        m = re.search(r"god rays: roof rule: (.*)", ln)
        if m:
            covered.append(m.group(1))
            continue
        m = re.search(r"god rays: (aperture .*|  .*)", ln)
        if m and cur is not None:
            builds[cur].append(m.group(1))
    if not builds:
        print("no prism builds logged (devGodRaysApLog?)")
    else:
        b = a.build or max(builds)
        print(f"prism build {b}: {builds[b][0]}")
        for row in builds[b][1:]:
            print("  " + row)
    print(f"roof rule closed {len(covered)} apertures" + (":" if covered else ""))
    seen = set()
    for c in covered:
        key = c.split(" covered")[0]
        if key not in seen:
            seen.add(key)
            print("  " + c)


if __name__ == "__main__":
    main()
