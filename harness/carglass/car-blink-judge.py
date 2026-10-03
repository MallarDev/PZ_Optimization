#!/usr/bin/env python3
"""Jev's verdict on "the car flashes": devCapture runs of the same drive (crop round the player's car), each frame's
one-frame pops in the car's box (a pixel that jumps >= 32/255 against both neighbouring frames in the same direction:
something that appears or vanishes for one frame), against a control run.

    car-blink-judge.py --control <run> <run>... [--box x0,y0,x1,y1] [--context "..."] [--out verdict.json]

Built for the car occupant release (2026-10-03): the maintainer saw the car flashing in run occ-card-impostor; the car
blinked out for a frame on each occupant tile refresh (the pass put back a stale framebuffer)."""
import argparse
import json
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from typesafe_client import ask, choice, noul, fmt  # noqa: E402


def pops(run, box):
    cap = os.path.join(run, "capture")
    head = open(os.path.join(cap, "index.txt")).read().split()
    w, h = (int(x.split("=")[1]) for x in head[:2])
    ts = [int(x) for x in head[2:]]
    f = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r").reshape(-1, h, w, 4)
    x0, y0, x1, y1 = box
    y = np.stack([(fr[::-1][y0:y1, x0:x1, :3].astype(np.int16) * np.array([0.3, 0.59, 0.11])).sum(2) for fr in f])
    area = (x1 - x0) * (y1 - y0)
    p = []
    for i in range(1, len(y) - 1):
        a, b = y[i] - y[i - 1], y[i] - y[i + 1]
        p.append(int((((a >= 32) & (b >= 32)) | ((a <= -32) & (b <= -32))).sum()))
    p = np.array(p)
    blinks = [i + 1 for i in np.nonzero(p > 0.10 * area)[0]]
    gaps = [ts[blinks[k + 1]] - ts[blinks[k]] for k in range(len(blinks) - 1)]
    secs = (ts[-1] - ts[0]) / 1000
    return {"frames": len(y), "seconds": round(secs, 2), "fps": round(len(y) / max(secs, 1e-3)),
            "one_frame_pop_px_mean": round(float(p.mean())), "one_frame_pop_px_p99": round(float(np.percentile(p, 99))),
            "max_share_of_car_box_in_one_frame_pct": round(100 * float(p.max()) / area, 1),
            "frames_where_over_10pct_of_the_car_box_pops": len(blinks),
            "those_frames_per_second": round(len(blinks) / max(secs, 1e-3), 2),
            "ms_between_those_frames": gaps[:12]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--control", required=True)
    ap.add_argument("--box", default="150,230,800,600")
    ap.add_argument("--context", default="")
    ap.add_argument("--out")
    a = ap.parse_args()
    box = [int(v) for v in a.box.split(",")]
    state = {
        "setup": "Frame-exact in-game captures (30 fps, every captured frame read back) of the same 60 km/h drive, cropped round "
                 "the player's car with the camera following it. A one-frame pop is a pixel that changes by >= 32/255 against both "
                 "the frame before and the frame after in the same direction (something appearing or vanishing for one frame); "
                 "steady motion of the road under the car gives a small background in every run. `control` = the build with the "
                 "occupant feature off. " + a.context,
        "report": "the maintainer saw the car flashing (blinking) in the run with the people-in-cars feature on",
        "control": pops(a.control, box),
    }
    for r in a.runs:
        state[os.path.basename(r.rstrip("/")).rsplit("-2026", 1)[0]] = pops(r, box)
    print(json.dumps(state, indent=1))
    names = [k for k in state if k not in ("setup", "report", "control")]
    questions = {}
    for n in names:
        questions[f"{n}_blinks"] = noul(f"Does `{n}` show the car blinking: frames where a large share of the car's box pops for one "
                                        "frame, far above `control`'s background?")
        questions[f"{n}_like_control"] = noul(f"Is `{n}` within the control's background (no blink the control does not have)?")
    questions["verdict"] = choice({"question": "Overall: is the reported flash real in the feature's first run and gone in the fixed run?",
                                   "note": f"runs in order: {names}; the first is the run the maintainer watched, the last the fix"},
                                  {"confirmed_and_fixed": "the first run blinks, the fixed run is like the control",
                                   "confirmed_not_fixed": "the first run blinks and the fixed run still does",
                                   "not_confirmed": "no run blinks beyond the control",
                                   "inconclusive": "the numbers do not decide it"})
    log = []
    answers = ask(state, questions, log=log)
    print("jev:")
    print("  " + fmt(answers).replace("\n", "\n  "))
    print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
    if a.out:
        Path(a.out).write_text(json.dumps({"state": state, "answers": answers, "typesafe": log[0]}, indent=1))


if __name__ == "__main__":
    main()
