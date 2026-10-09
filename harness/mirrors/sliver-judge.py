#!/usr/bin/env python3
"""Is a person in front of a mirror shown in it, or cut to a sliver? (2026-10-09, the maintainer's dresser mirror by a wall)

Reads `devCapture` runs made with `--prop devMirrorsView=9 --prop devMirrorsViewToggleMs=N`: every other N ms the composite
paints each mirror's glass flat, blue 38 / 255 plus green where the mirrored person is shown, red where the person is in the
model layer but hidden by something the composite thinks stands in front (`Mirrors.java`, dev view 9); the frames between
are the picture. Per run: the dev-view frames, the person's glass pixels shown / hidden, the hidden share over the frames
with a person on the glass, the worst frames. `--sheet out.png` = picture frames of each run (nearest to the dev frames with
the most person pixels) side by side for a look. Jev answers from the numbers whether BEFORE shows the sliver and AFTER
fixes it. Rig (the maintainer's save, left-right walk in front of the dresser): see docs/findings-mirror-sliver-2026-10-09.md.

  python3 harness/mirrors/sliver-judge.py --before <run> --after <run> [--sheet out.png] [--json f] [--no-jev]
"""
import argparse, json, os, re, sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def load(run):
    cap = os.path.join(run, "capture")
    lines = open(os.path.join(cap, "index.txt")).read().split("\n")
    kv = dict(p.split("=") for p in lines[0].split())
    w, h = int(kv["w"]), int(kv["h"])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()], dtype=np.int64)
    path = os.path.join(cap, "frames.rgba")
    n = min(len(stamps), os.path.getsize(path) // (w * h * 4))
    return np.memmap(path, dtype=np.uint8, mode="r", shape=(n, h, w, 4)), stamps[:n]


def colourlike(f):
    """Pixels the dev view's red / green could be confused with (the red box on the dresser), dilated 6 px."""
    r, g, b = (f[..., i].astype(np.int16) for i in range(3))
    m = (b >= 15) & (b <= 70) & (((r >= 100) & (g <= 40)) | ((g >= 100) & (r <= 40)))
    out = m.copy()
    for d in range(1, 7):
        out[d:] |= m[:-d]; out[:-d] |= m[d:]; out[:, d:] |= m[:, :-d]; out[:, :-d] |= m[:, d:]
    return out


def glass_region(f, rects):
    """The dev frame's mirror glass: the connected region of flat glass (navy, red, green; 4 px cells) that overlaps the
    logged mirror rects most. The rects come from a log line up to ~0.15 s off the frame: while the camera moves they drift
    onto the window beside the mirror, whose people keep the old occlusion test (and legitimately show red there)."""
    r, g, b = (f[..., i].astype(np.int16) for i in range(3))
    flat = (b >= 15) & (b <= 70) & (np.minimum(r, g) <= 40) & ((np.maximum(r, g) <= 8) | (r >= 100) | (g >= 100))
    h, w = flat.shape
    c = flat[:h // 4 * 4, :w // 4 * 4].reshape(h // 4, 4, w // 4, 4).mean(axis=(1, 3)) >= 0.5
    lab = np.zeros(c.shape, np.int32)
    n = 0
    for y0, x0 in zip(*np.nonzero(c)):
        if lab[y0, x0]:
            continue
        n += 1
        stack = [(y0, x0)]
        lab[y0, x0] = n
        while stack:
            y, x = stack.pop()
            for yy, xx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
                if 0 <= yy < c.shape[0] and 0 <= xx < c.shape[1] and c[yy, xx] and not lab[yy, xx]:
                    lab[yy, xx] = n
                    stack.append((yy, xx))
    if n == 0:
        return np.zeros((h, w), bool)
    box = np.zeros(c.shape, bool)
    for x, y, bw, bh in rects:
        box[max(0, y // 4):max(0, (y + bh) // 4), max(0, x // 4):max(0, (x + bw) // 4)] = True
    over = np.bincount(lab[box & (lab > 0)], minlength=n + 1)
    best = int(np.argmax(over))
    if over[best] == 0:
        return np.zeros((h, w), bool)
    m = np.repeat(np.repeat(lab == best, 4, axis=0), 4, axis=1)
    out = np.zeros((h, w), bool)
    out[:m.shape[0], :m.shape[1]] = m
    for d in range(1, 4):  # (the cells' soft edge)
        out[d:] |= out[:-d]; out[:-d] |= out[d:]; out[:, d:] |= out[:, :-d]; out[:, :-d] |= out[:, d:]
    return out


def classify(f, box=None):
    """The dev view's flat glass: blue 38 (15-70 after the screen pass), red or green = the layer's alpha (below 1 at the
    model's soft edges); counted inside the mirror panes' screen rects only (a red box on the dresser looks the same)."""
    r, g, b = (f[..., i].astype(np.int16) for i in range(3))
    flat = (b >= 15) & (b <= 70) & (np.minimum(r, g) <= 40)
    if box is not None:
        flat &= box
    navy = flat & (np.maximum(r, g) <= 8) & (b >= 20) & (b <= 55)  # (the dev view's empty glass: a picture has next to none)
    shown = flat & (g >= 100) & (r <= 40)
    hidden = flat & (r >= 100) & (g <= 40)
    return int(navy.sum()), int(shown.sum()), int(hidden.sum())


def mirror_rects(run):
    """(epoch_ms, [(x, y, w, h)]) of the mirrors (not windows) from the console's `mirrors: dev rects` lines (window px,
    top-left origin) and the capture crop's origin from the run's devCapture property."""
    crop = (0, 0)
    for line in open(os.path.join(run, "pzopt.properties")):
        m = re.search(r"crop=(\d+):(\d+):", line)
        if line.startswith("devCapture") and m:
            crop = (int(m.group(1)), int(m.group(2)))
    out = []
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        if "mirrors: dev rects" not in line:
            continue
        t = re.search(r"epoch_ms=(\d+)", line)
        rs = [(int(x) - crop[0], int(y) - crop[1], int(w), int(h)) for x, y, w, h in re.findall(r"\[mirror (-?\d+),(-?\d+) (\d+)x(\d+) @", line)]
        if t:
            out.append((int(t.group(1)), rs))
    return out


def measure(run):
    mm, stamps = load(run)
    rects = mirror_rects(run)
    times = np.array([t for t, _ in rects]) if rects else None
    rows = []
    for i in range(len(stamps)):
        f = np.asarray(mm[i])[::-1]
        box = None
        if times is not None:
            rs = rects[int(np.argmin(np.abs(times - stamps[i])))][1]
            box = np.zeros(f.shape[:2], bool)
            for x, y, w, h in rs:
                box[max(0, y):max(0, y + h), max(0, x):max(0, x + w)] = True
            if classify(f, box)[0] >= 1500:  # a dev frame: the mirror's own glass region, not the window beside it
                box = glass_region(f, rs)
        rows.append([i, f, box])
    navy = [classify(r[1], r[2])[0] for r in rows]
    picIdx = [k for k in range(len(rows)) if navy[k] < 1500]
    frames = rows
    rows = []
    for k, r in enumerate(frames):
        f, box = r[1], r[2]
        if navy[k] >= 1500 and picIdx:
            # what is red / green in the picture next to it is the scene (the dresser's red box), not the dev view
            j = min(picIdx, key=lambda q: abs(q - k))
            keep = ~colourlike(frames[j][1])
            box = keep if box is None else box & keep
        rows.append((r[0],) + classify(f, box))
    dev = [r for r in rows if r[1] >= 1500]  # a dev-view frame: the mirror's empty glass painted navy
    pic = [r for r in rows if r[1] < 1500]
    person = [r for r in dev if r[2] + r[3] >= 50]
    shown = sum(r[2] for r in person)
    hidden = sum(r[3] for r in person)
    shares = sorted(r[3] / (r[2] + r[3]) for r in person)
    worst = sorted(person, key=lambda r: -r[3] / (r[2] + r[3]))[:5]
    best = sorted(dev, key=lambda r: -(r[2] + r[3]))[:3]
    return {
        "run": os.path.basename(run.rstrip("/")),
        "frames": len(rows), "dev_view_frames": len(dev), "picture_frames": len(pic),
        "frames_with_person_on_glass": len(person),
        "person_px_mean": round((shown + hidden) / len(person)) if person else 0,
        "person_shown_px": shown, "person_hidden_px": hidden,
        "hidden_share": round(hidden / (shown + hidden), 3) if person else None,
        "hidden_share_median_frame": round(shares[len(shares) // 2], 3) if shares else None,
        "frames_mostly_hidden": sum(1 for s in shares if s > 0.5),
        "worst_frames": [{"frame": r[0], "shown": r[2], "hidden": r[3]} for r in worst],
        "_best": [r[0] for r in best], "_pic": [r[0] for r in pic],
    }, mm


def sheet(results, out):
    tiles = []
    for res, mm in results:
        row = []
        for i in res["_best"][:3]:
            # the picture frame nearest the dev frame with the most person pixels
            j = min(res["_pic"], key=lambda k: abs(k - i)) if res["_pic"] else i
            for k in (i, j):
                row.append(Image.fromarray(np.asarray(mm[k])[::-1][..., :3]))
        tiles.append(row)
    if not tiles or not tiles[0]:
        return
    w, h = tiles[0][0].size
    s = 0.5
    tw, th = int(w * s), int(h * s)
    img = Image.new("RGB", (tw * max(len(r) for r in tiles), th * len(tiles)))
    for y, row in enumerate(tiles):
        for x, t in enumerate(row):
            img.paste(t.resize((tw, th)), (x * tw, y * th))
    img.save(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", required=True)
    ap.add_argument("--after", required=True)
    ap.add_argument("--sheet")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    before, mb = measure(a.before)
    after, ma = measure(a.after)
    if a.sheet:
        sheet([(before, mb), (after, ma)], a.sheet)
    for d in (before, after):
        d.pop("_best"), d.pop("_pic")
    state = {
        "what": "A person stands in front of a dresser mirror next to a wall and walks left and right in front of it. In dev "
                "view frames the mirror's glass is painted flat: green where the person's mirror image is shown, red where "
                "the person is in the reflection layer but hidden by something the renderer thinks stands in front of them. "
                "The dresser under the glass and the floor may legitimately hide the person's lower body, but the mirror "
                "glass sits above the dresser top, so most of the person's image on the glass should be shown. The "
                "maintainer's report: the person's image is cut by a hard vertical line (only a slice, a sliver at the mirror's "
                "edge, shows; plain wall where the rest should be). BEFORE is the "
                "released occlusion test, AFTER the fix (occlusion along the person's own line of sight).",
        "fields": {"hidden_share": "hidden person px / all person px on the glass over the dev frames with a person",
                   "frames_mostly_hidden": "dev frames where more than half of the person's image is hidden",
                   "person_px_mean": "person px on the glass per such frame (shown + hidden)"},
        "before": before, "after": after,
    }
    print(json.dumps(state, indent=1))
    verdict = None
    if not a.no_jev:
        from typesafe_client import ask, noul, choice
        qs = {
            "comparable": noul("Do both runs have enough dev-view frames with the person on the glass to judge (at least "
                               "10 each) and a similar person_px_mean (within a factor of two)?"),
            "before_cut": noul("Does BEFORE show the reported bug: a substantial part of the person's image on the glass "
                               "hidden (hidden_share above 0.15), i.e. the image cut off?"),
            "after_shown": noul("In AFTER is the person's image shown whole (hidden_share below 0.05, no "
                                "frames_mostly_hidden)?"),
            "verdict": choice("Overall, is the sliver reflection fixed?",
                              {"fixed": "before cuts off a substantial part of the person, after shows it whole, comparable runs",
                               "partly_fixed": "after hides clearly less than before but still a large share",
                               "not_fixed": "after hides about as much as before",
                               "not_reproduced": "before already shows the person: the bug did not appear in these runs",
                               "invalid": "the runs do not allow the comparison"}),
        }
        verdict = ask(state, qs)
        print("jev:", json.dumps({k: (v.get("choice"), round(v.get("confidence", 0), 2)) if "choice" in v else round(v.get("noul", 0), 3)
                                  for k, v in verdict.items()}))
    if a.json:
        json.dump({"state": state, "jev": verdict}, open(a.json, "w"), indent=1)
    return 0 if verdict and verdict.get("verdict", {}).get("choice") == "fixed" else (0 if a.no_jev else 1)


if __name__ == "__main__":
    sys.exit(main())
