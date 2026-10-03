#!/usr/bin/env python3
"""Splits a devTorchSourceCycle run's frames by variant (torchSource, 2026-10-03).

    torchsrc-split.py <run> [--skip-ms 300] [--skip-cycles 1]

Reads the console's `torch source cycle: variant <v> epoch_ms=<t>` switches and pzopt-overlay.out (one row per presented
frame: frametime, gpu_ms, epoch_ms) and prints per variant: frames, mean / p99 frame time, mean / p99 GPU time (the GL
timer queries of the frame). The first `--skip-ms` after a switch and the first `--skip-cycles` cycles are left out (the
same rule as the in-game report line).
"""
import argparse
import csv
import glob
import os
import re


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--skip-ms", type=int, default=300)
    ap.add_argument("--skip-cycles", type=int, default=1)
    a = ap.parse_args()
    cons = sorted(glob.glob(os.path.join(a.run, "console*.txt")))
    text = "".join(open(c, errors="ignore").read() for c in cons)
    sw = [(int(m.group(2)), m.group(1)) for m in re.finditer(r"torch source cycle: variant (\w+) epoch_ms=(\d+)", text)]
    if not sw:
        raise SystemExit("no devTorchSourceCycle switches in the console")
    names = []
    for _, v in sw:
        if v in names:
            break
        names.append(v)
    first_measured = sw[min(len(sw) - 1, a.skip_cycles * len(names))][0]
    rows = list(csv.DictReader(open(os.path.join(a.run, "pzopt-overlay.out"))))
    acc = {v: ([], []) for v in names}
    j = 0
    for r in rows:
        t = int(r["epoch_ms"])
        while j + 1 < len(sw) and sw[j + 1][0] <= t:
            j += 1
        if t < first_measured or sw[j][0] > t or t - sw[j][0] < a.skip_ms:
            continue
        ft, gpu = acc[sw[j][1]]
        ft.append(float(r["frametime"]))
        gpu.append(float(r["gpu_ms"]))

    def p99(x):
        s = sorted(x)
        return s[min(len(s) - 1, int(len(s) * 0.99))] if s else float("nan")

    print(f"{'variant':10} {'frames':>7} {'ft_mean':>8} {'ft_p99':>8} {'gpu_mean':>9} {'gpu_p99':>8}")
    for v in names:
        ft, gpu = acc[v]
        if not ft:
            continue
        print(f"{v:10} {len(ft):7d} {sum(ft) / len(ft):8.3f} {p99(ft):8.3f} {sum(gpu) / len(gpu):9.4f} {p99(gpu):8.3f}")


if __name__ == "__main__":
    main()
