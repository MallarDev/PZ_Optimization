#!/usr/bin/env python3
"""Characters behind glass, before / after against stock (2026-10-02, the bus-shelter report).

Four --record runs of one static scene (player held, zombies pinned behind glass): BEFORE (the released bake), AFTER (the
fix), STOCK and STOCK2 (a second stock run: how much two runs of the same build differ, since the idle animations never
line up). Code pulls frames at the same seconds after each route start, aligns every run to STOCK by phase correlation
(integer pixels), and for each region measures the mean absolute difference to STOCK (PQ code values, 0-255) and the share
of pixels 25+ levels off. Regions: one box per zombie behind the glass (suspect) and plain surfaces nearby (control).
Jev reads only those numbers and answers whether BEFORE departed from stock, whether AFTER matches it, and the verdict.

usage: glass-judge.py --before <run> --after <run> --stock <run> --stock2 <run> --region name:x,y,w,h ...
                      --control name:x,y,w,h ... [--times 2,3,4,5,6] [--context "..."] [--json out] [--sheet out.png] [--no-jev]
Box coordinates are in the 5120x2160 capture of the STOCK run.
"""
import argparse, glob, json, os, subprocess, sys
import numpy as np

sys.path.insert(0, os.path.dirname(__file__))


def run_dir(r):
    return r if os.path.isdir(r) else sorted(glob.glob(f"harness/runs/{r}-*"))[-1]


def kv(p):
    return dict(l.strip().split("=", 1) for l in open(p) if "=" in l)


def route_start(d):
    # seconds into the recording of the route start (the capture starts ~1 s after the launch)
    return int(kv(d + "/pzopt-schedule.out")["route_start_epoch_ms"]) / 1000 - int(kv(d + "/run.opts")["launch_epoch"]) - 1.0


def frame(d, t):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t:.3f}", "-i", d + "/recording.mp4", "-frames:v", "1",
                          "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(2160, 5120, 3).astype(np.float32)


def shift_to(ref, img, win=(1560, 580, 2000, 1000)):
    """Integer (dy, dx) that moves img onto ref, from phase correlation of the luma in a central window."""
    x, y, w, h = win
    lum = lambda a: (0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2])[y:y + h, x:x + w]
    A, B = np.fft.fft2(lum(ref)), np.fft.fft2(lum(img))
    R = A * np.conj(B)
    r = np.abs(np.fft.ifft2(R / (np.abs(R) + 1e-9)))
    dy, dx = np.unravel_index(np.argmax(r), r.shape)
    return int(dy - h if dy > h // 2 else dy), int(dx - w if dx > w // 2 else dx)


def parse(spec):
    name, box = spec.split(":", 1)
    return name, tuple(int(v) for v in box.split(","))


def main():
    ap = argparse.ArgumentParser()
    for k in ("before", "after", "stock", "stock2"):
        ap.add_argument("--" + k, required=True)
    ap.add_argument("--region", action="append", default=[])
    ap.add_argument("--control", action="append", default=[])
    ap.add_argument("--times", default="2,3,4,5,6")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--sheet")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    runs = {k: run_dir(getattr(a, k)) for k in ("before", "after", "stock", "stock2")}
    times = [float(t) for t in a.times.split(",")]
    frames = {k: [frame(d, route_start(d) + t) for t in times] for k, d in runs.items()}
    shifts = {k: [shift_to(frames["stock"][i], f) for i, f in enumerate(fs)] for k, fs in frames.items()}
    for k in frames:
        frames[k] = [np.roll(f, s, axis=(0, 1)) for f, s in zip(frames[k], shifts[k])]
    regions = [("suspect", *parse(s)) for s in a.region] + [("control", *parse(s)) for s in a.control]
    card = {"context": a.context, "runs": runs, "seconds_after_route_start": times,
            "alignment_to_stock_px": {k: sorted(set(map(tuple, v))) for k, v in shifts.items() if k != "stock"},
            "suspect_regions": {}, "control_regions": {}}
    for kind, name, (x, y, w, h) in regions:
        row = {"box": [x, y, w, h]}
        for k in ("before", "after", "stock2"):
            d = [np.abs(f[y:y + h, x:x + w] - s[y:y + h, x:x + w]).mean(axis=2)
                 for f, s in zip(frames[k], frames["stock"])]
            row[f"{k}_vs_stock"] = {"mean_abs_diff": round(float(np.mean([v.mean() for v in d])), 2),
                                    "off_25_share": round(float(np.mean([(v > 25).mean() for v in d])), 3)}
        floor = max(row["stock2_vs_stock"]["mean_abs_diff"], 0.5)  # two stock runs: the idle animations never line up
        row["before_over_stock_floor"] = round(row["before_vs_stock"]["mean_abs_diff"] / floor, 2)
        row["after_over_stock_floor"] = round(row["after_vs_stock"]["mean_abs_diff"] / floor, 2)
        card[kind + "_regions"][name] = row
    print(json.dumps(card, indent=1))
    if a.sheet:
        from PIL import Image, ImageDraw
        tiles = []
        for k in ("before", "after", "stock", "stock2"):
            im = Image.fromarray(frames[k][len(times) // 2].clip(0, 255).astype(np.uint8))
            dr = ImageDraw.Draw(im)
            for kind, name, (x, y, w, h) in regions:
                dr.rectangle((x, y, x + w, y + h), outline=(255, 60, 60) if kind == "suspect" else (60, 200, 255), width=3)
                dr.text((x + 4, y + 4), name, fill=(255, 255, 0))
            dr.text((1700, 520), k, fill=(255, 255, 0))
            tiles.append(im.crop((1600, 500, 3600, 1600)))
        sheet = Image.new("RGB", (4000, 2200))
        for i, t in enumerate(tiles):
            sheet.paste(t, ((i % 2) * 2000, (i // 2) * 1100))
        sheet.save(a.sheet)
    if not a.no_jev:
        from typesafe_client import ask, noul, choice, fmt
        q = {
            "before_differs_from_stock": noul(
                "Each suspect region is a zombie standing behind glass. Do the suspect regions of BEFORE differ from STOCK "
                "clearly more than STOCK2 differs from STOCK there (`before_vs_stock` vs `stock2_vs_stock`: higher "
                "mean_abs_diff and off_25_share, `before_over_stock_floor` well above 1), while the control regions stay alike?"),
            "after_matches_stock": noul(
                "Are the suspect regions of AFTER within the run-to-run variation of stock, i.e. `after_vs_stock` about as "
                "large as `stock2_vs_stock` or smaller (`after_over_stock_floor` near or below 1), with the controls alike too?"),
            "verdict": choice("What best describes the characters behind the glass?", {
                "fixed": "BEFORE differed from stock, AFTER matches stock",
                "not_fixed": "AFTER still differs from stock clearly more than two stock runs differ",
                "no_problem_before": "BEFORE already matched stock",
                "inconclusive": "the numbers do not support any of these",
            }),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    if a.json:
        json.dump(card, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
