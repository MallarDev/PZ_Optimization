#!/usr/bin/env python3
"""Large one-step picture changes ("mask jumps") per devGtAlternate phase (2026-10-03, the floor tiles flashing while
turning: the vision cone's fog-of-war shape jumping between two frames).

    mask-jumps.py <run> [--px 30] [--share 0.04] [--judge]

For a `devCapture` sequence (gray or colour) every consecutive frame pair gets the share of pixels whose luma changes by
more than --px. A jump is a pair above --share (a slow turn changes far less a frame). With `pzopt-gtab.out` in the run
(--prop devGtAlternate=<ms> --prop devGtAlternateKeys=<keys>) the pairs are split by the phase their later frame was
drawn in (1 = the keys on, 0 = off; a frame captured within 50 ms of a switch is left out). --judge: Jev over the two
phases' numbers.
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def load(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split())
    w, h = int(head["w"]), int(head["h"])
    st = [int(x) for x in lines[1:] if x.strip()]
    gray = head.get("fmt") == "gray"
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(st), raw.size // (w * h * (1 if gray else 4)))

    def get(i):
        if gray:
            return np.asarray(raw[i * w * h:(i + 1) * w * h]).reshape(h, w).astype(np.int16)
        f = np.asarray(raw[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[..., :3].astype(np.float32)
        return (f @ np.array([0.299, 0.587, 0.114], np.float32)).astype(np.int16)
    return get, st[:n]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--px", type=int, default=30)
    ap.add_argument("--share", type=float, default=0.04)
    ap.add_argument("--judge", action="store_true")
    a = ap.parse_args()
    get, st = load(a.run)
    phases = []
    gp = os.path.join(a.run, "pzopt-gtab.out")
    if os.path.exists(gp):
        for line in open(gp):
            if line.startswith("#"):
                continue
            p = line.split()
            if len(p) >= 2:
                phases.append((int(p[0]), int(p[1])))
    pt = np.array([p[0] for p in phases], np.int64)

    def phase(t):
        if not phases:
            return "all"
        i = int(np.searchsorted(pt, t, side="right")) - 1
        if i < 0:
            return None
        ph = phases[i][1]
        j = i
        while j > 0 and phases[j - 1][1] == ph:
            j -= 1
        k = i
        while k + 1 < len(phases) and phases[k + 1][1] == ph:
            k += 1
        if t - phases[j][0] < 50 or (k + 1 < len(phases) and phases[k + 1][0] - t < 50):
            return None
        return "on" if ph == 1 else "off"

    acc = {}
    prev = get(0)
    for i in range(1, len(st)):
        cur = get(i)
        s = float((np.abs(cur - prev) > a.px).mean())
        prev = cur
        ph = phase(st[i])
        if ph is None:
            continue
        e = acc.setdefault(ph, {"pairs": 0, "jumps": 0, "share_sum": 0.0, "max_share": 0.0})
        e["pairs"] += 1
        e["jumps"] += s > a.share
        e["share_sum"] += s
        e["max_share"] = max(e["max_share"], s)
    res = {}
    for ph, e in acc.items():
        res[ph] = {"frame_pairs": e["pairs"], "jumps": e["jumps"], "jumps_per_1000_pairs": round(1000.0 * e["jumps"] / max(1, e["pairs"]), 2),
                   "mean_changed_share": round(e["share_sum"] / max(1, e["pairs"]), 5), "max_changed_share": round(e["max_share"], 3)}
        print(ph, res[ph])
    if a.judge and len(res) >= 2:
        from typesafe_client import ask, choice, noul, fmt
        state = {
            "setup": "One frame-exact capture (every presented frame read back in-game) of a night scene: the character stands and "
                     "turns slowly (25 degrees a second) with a lit torch and lantern; the fog of war darkens what the character "
                     "cannot see. Every 2 s the run switches one optimization (the vision cone's shadow shape computed on a worker "
                     "thread, 'on') against the stock way ('off'). A jump is a pair of consecutive frames where more than "
                     f"{a.share * 100:.0f} % of the picture changes brightness by > {a.px}/255: at this slow turn that only happens "
                     "when the lit / dark layout snaps to a new shape at once, which a player sees as floor tiles flashing.",
            "phases": res,
        }
        questions = {
            "on_jumps_more": noul("Does the 'on' phase show clearly more jumps (per 1000 frame pairs) than the 'off' phase?"),
            "off_jumps_too": noul("Does the 'off' phase (stock vision shape) also show jumps at a comparable rate?"),
            "verdict": choice({"question": "What do the numbers say about the floor tiles flashing while turning?"},
                              {"optimization_causes": "the 'on' phase has the jumps, 'off' does not: the optimization causes them",
                               "both": "both phases jump comparably: not this optimization",
                               "none": "neither phase jumps meaningfully",
                               "inconclusive": "too few frame pairs or contradicting numbers"}),
        }
        log = []
        ans = ask(state, questions, log=log)
        print("jev:")
        print("  " + fmt(ans).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")


if __name__ == "__main__":
    main()
