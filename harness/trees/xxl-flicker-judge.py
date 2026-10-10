#!/usr/bin/env python3
"""Jev's verdict on the XXL tree flicker (2026-10-08): does the see-through XXL tree over a standing player blink in
`test` where the stock control does not? Reads two xxl-flicker.py JSON files of the same save, spot and capture size,
over numbers only.

Usage: xxl-flicker-judge.py TEST.json CONTROL.json [--context "..."] [--out verdict.json]
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from typesafe_client import ask, choice, noul, fmt  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("test")
    ap.add_argument("control")
    ap.add_argument("--context", default="")
    ap.add_argument("--out")
    a = ap.parse_args()
    state = {
        "setup": "Two frame-exact captures (every presented frame read back in-game) of a copy of the player's save: the "
                 "character stands still in a parking lot with a large XXL tree in front, drawn see-through by the game's "
                 "tree cutaway, and the camera does not move. `test` = the optimization mod as the player runs it; `control` "
                 "= the same build with every optimization disabled (the stock game). A transient pixel changes by >= 32/255 "
                 "and returns within 3 frames (something appearing or vanishing for 1-3 frames). `centre` is a box round the "
                 "player that holds the see-through tree; `rest` is the remaining world view (HUD masked). A burst frame is one "
                 "where more than 1 % of the centre box blinks in dense clusters. `blob_*` count only blinking pixels in dense clusters (a crown or sprite "
                 "blinking as a whole: the reported symptom); isolated single-pixel twinkles (wet-blood glints, sub-pixel sparkle) are "
                 "in the transient rate but not in the blob figures. " + a.context,
        "report": "the maintainer sees the see-through XXL tree flicker while standing still",
        "test": json.loads(Path(a.test).read_text()),
        "control": json.loads(Path(a.control).read_text()),
    }
    for side in ("test", "control"):
        state[side]["centre"]["bursts"] = state[side]["centre"]["bursts"][:12]
    print(json.dumps(state, indent=1))
    questions = {
        "flicker_present": noul("Does `test` show the tree flickering: burst frames in the centre box, or blob figures (whole "
                                "areas blinking) clearly above `control`'s? Isolated single-pixel twinkles are not the tree."),
        "stock_also": noul("Does `control` show the same kind of centre bursts at a comparable rate?"),
        "verdict": choice({"question": "What best describes the tree in `test` against `control` (bursts and blob figures; "
                                       "isolated single-pixel twinkles are a separate effect, not the tree)?"},
                          {"pzopt_flicker": "test has bursts or blob blinking (whole areas) in the centre where control does not",
                           "shared_flicker": "both have comparable bursts or blob blinking",
                           "no_flicker": "no bursts and no blob blinking beyond a small steady background on either side",
                           "inconclusive": "too few frames, capture gaps or contradicting numbers"}),
    }
    log = []
    answers = ask(state, questions, log=log)
    print("jev:")
    print("  " + fmt(answers).replace("\n", "\n  "))
    print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
    if a.out:
        Path(a.out).write_text(json.dumps({"state": state, "answers": answers, "typesafe": log[0]}, indent=1))


if __name__ == "__main__":
    main()
