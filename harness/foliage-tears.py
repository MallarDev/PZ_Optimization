#!/usr/bin/env python3
"""Vertical tears in foliage (2026-10-02, the report "whenever an entity walks through foliage there are visual artifacts
vertically"), measured on a `devCapture` sequence, and Jev's verdict on them.

A tear pixel is a vertical line: the horizontal luma step |L(x+1) - L(x)| summed over a vertical run of --run rows stands
out against the same sum 3 px to the left and right (a single column, not a textured area), inside green foliage on both
sides. It is transient when the frames --gap before and after, aligned to this one by phase correlation (the camera
follows the walking player), have no line within 2 px of it: grass blades, fence posts and the shed are lines in every
frame and cancel out; a tear that travels with a walker does not. A box round the player (screen centre) is masked:
the walking legs are vertical lines too.

Usage: foliage-tears.py <run> [--control <run>] [--before <run>] [--context "..."] [--json out] [--heat png] [--png dir]
Prints transient tear px per frame (mean, p90, max), frames with >= --frame-px, and the strongest frames. With
--control (the same walk with the feature under test off) Jev answers whether the test run shows the tears.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent / "ppl"))


def load(run):
    import capture
    frames, stamps = capture.load(run)
    return frames, stamps


def luma(f):
    f = f.astype(np.float32)
    return f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114


def green(f):
    f = f.astype(np.int16)
    return (f[..., 1] > f[..., 0] + 4) & (f[..., 1] > f[..., 2] + 8)


def vbox(a, n):
    c = np.cumsum(np.pad(a, ((1, 0), (0, 0))), axis=0)
    out = np.zeros_like(a)
    h = n // 2
    out[h:a.shape[0] - (n - h) + 1] = c[n:] - c[:-n]
    return out


def lines(f, run, thresh):
    L = luma(f)
    gx = np.abs(L[:, 1:] - L[:, :-1])
    s = vbox(gx, run) / run
    side = np.maximum(np.roll(s, 3, axis=1), np.roll(s, -3, axis=1))
    g = green(f)[:, :-1]
    gl, gr = np.roll(g, 2, axis=1), np.roll(g, -2, axis=1)
    m = (s - side > thresh) & (s > 2 * thresh) & vbox(gl.astype(np.float32), run).__gt__(run * 0.7) & vbox(gr.astype(np.float32), run).__gt__(run * 0.7)
    return m


def shift_of(a, b):
    """Integer (dy, dx) such that b shifted by it matches a (phase correlation on the centre)."""
    h, w = a.shape
    cy, cx = h // 2, w // 2
    ah, aw = min(h, 512), min(w, 1024)
    A = a[cy - ah // 2:cy + ah // 2, cx - aw // 2:cx + aw // 2]
    B = b[cy - ah // 2:cy + ah // 2, cx - aw // 2:cx + aw // 2]
    win = np.outer(np.hanning(ah), np.hanning(aw))
    FA, FB = np.fft.fft2((A - A.mean()) * win), np.fft.fft2((B - B.mean()) * win)
    r = FA * np.conj(FB)
    r /= np.abs(r) + 1e-6
    c = np.abs(np.fft.ifft2(r))
    dy, dx = np.unravel_index(np.argmax(c), c.shape)
    if dy > ah // 2:
        dy -= ah
    if dx > aw // 2:
        dx -= aw
    return int(dy), int(dx)


def shifted(m, dy, dx):
    out = np.zeros_like(m)
    h, w = m.shape
    ys, yd = (slice(0, h - dy), slice(dy, h)) if dy >= 0 else (slice(-dy, h), slice(0, h + dy))
    xs, xd = (slice(0, w - dx), slice(dx, w)) if dx >= 0 else (slice(-dx, w), slice(0, w + dx))
    out[yd, xd] = m[ys, xs]
    return out


def dilate_x(m, r):
    out = m.copy()
    for k in range(1, r + 1):
        out |= np.roll(m, k, axis=1) | np.roll(m, -k, axis=1)
    return out


def movers(frames, a):
    """Static camera only: walkers = non-green blobs that differ from the run's median frame. Per walker, the
    horizontal luma step on its own column in the rows just above it against the columns 20-40 px to either side
    (a push seam runs straight up from the walker's column; grass texture has no preferred column)."""
    n, h, w = frames.shape[:3]
    med = np.median(frames[:: max(1, n // 31)].astype(np.int16), axis=0)
    B = 8
    ratios, per_frame = [], []
    for i in range(0, n, a.mover_every):
        f = frames[i].astype(np.int16)
        moving = np.abs(f - med).sum(-1) > 150
        red = (f[..., 0] > f[..., 1] + 40) & (f[..., 0] > f[..., 2] + 30) & (f[..., 0] > 90)
        white = (f.min(-1) > 150) & (f.max(-1) - f.min(-1) < 40)
        m = moving & (red | white)  # hens: red or white plumage, away from where the median frame has it
        for r in a.mask:  # the performance overlay's digits are white and change every frame
            x, y, mw, mh = (int(v) for v in r.split(","))
            m[y:y + mh, x:x + mw] = False
        gh, gw = h // B, w // B
        grid = m[:gh * B, :gw * B].reshape(gh, B, gw, B).sum((1, 3)) >= 6
        seen = np.zeros_like(grid)
        L = luma(frames[i])
        gx = np.abs(L[:, 1:] - L[:, :-1])
        frame_r = []
        for y0 in range(gh):
            for x0 in range(gw):
                if not grid[y0, x0] or seen[y0, x0]:
                    continue
                stack, cells = [(y0, x0)], []
                seen[y0, x0] = True
                while stack:
                    y, x = stack.pop()
                    cells.append((y, x))
                    for yy, xx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
                        if 0 <= yy < gh and 0 <= xx < gw and grid[yy, xx] and not seen[yy, xx]:
                            seen[yy, xx] = True
                            stack.append((yy, xx))
                ys = [c[0] for c in cells]
                xs = [c[1] for c in cells]
                bw, bh = (max(xs) - min(xs) + 1) * B, (max(ys) - min(ys) + 1) * B
                if not (a.mover_min <= max(bw, bh) <= a.mover_max):
                    continue  # a hen-sized walker, not the player or a fluttering flower
                cx = (min(xs) + max(xs) + 1) * B // 2
                top = min(ys) * B
                r0, r1 = top - a.mover_rows, top - 4
                if r0 < 0 or cx - 44 < 0 or cx + 44 >= w - 1:
                    continue
                prof = gx[r0:r1, cx - 44:cx + 44].mean(0)  # index 44 = the walker's column
                centre = prof[44 - 6:44 + 7].max()
                side = np.median(np.concatenate([prof[4:24], prof[64:84]]))
                frame_r.append(float(centre / max(side, 1e-3)))
        ratios += frame_r
        per_frame.append(len(frame_r))
    r = np.array(ratios) if ratios else np.zeros(1)
    return {
        "walker_samples": len(ratios),
        "walker_seam_ratio_median": round(float(np.median(r)), 2),
        "walker_seam_ratio_p90": round(float(np.percentile(r, 90)), 2),
        "walker_seam_share_over_2x": round(float((r > 2.0).mean()), 3),
        "walkers_per_sampled_frame": round(float(np.mean(per_frame)), 1) if per_frame else 0.0,
    }


def measure(run, a):
    frames, stamps = load(run)
    n, h, w = frames.shape[0], frames.shape[1], frames.shape[2]
    sc = w / 5120.0 if w > 2000 else 1.0
    mask = np.ones((h, w - 1), bool)
    if a.player_mask != "none" and a.player_mask != "auto":  # w,h in capture px round the centre (feet at 3/4 of h)
        mw, mh = (int(v) for v in a.player_mask.split(","))
        px, py = w // 2, h // 2
        mask[max(0, py - mh * 3 // 4):py + mh // 4, max(0, px - mw // 2):px + mw // 2] = False
    elif a.player_mask == "auto" and w > 2000:  # a 50 % full-screen capture: the player stands in the centre
        px, py = w // 2, h // 2
        mask[max(0, py - int(260 * sc)):py + int(80 * sc), max(0, px - int(110 * sc)):px + int(110 * sc)] = False
    if a.movers:
        res = {"run": str(run), "frames": int(n), "size": f"{w}x{h}"}
        res.update(movers(frames, a))
        return res
    L = [None] * n
    M = [None] * n

    def get(i):
        if M[i] is None:
            L[i] = luma(frames[i])
            M[i] = lines(frames[i], a.run, a.thresh)
        return M[i]

    per = []
    heat = np.zeros((h, w - 1), np.float32)
    g = a.gap
    for t in range(g, n - g):
        mt = get(t) & mask
        keep = mt.copy()
        for o in (t - g, t + g):
            mo = get(o)
            dy, dx = shift_of(L[t], L[o])
            keep &= ~dilate_x(shifted(mo, dy, dx), 2)
        per.append(int(keep.sum()))
        heat += keep
        for i in range(max(0, t - g - 1), t - g + 1):
            if i != t - g:
                L[i] = M[i] = None
    per = np.array(per)
    res = {
        "run": str(run), "frames": int(n), "size": f"{w}x{h}",
        "fps": round((n - 1) / max(1e-3, (stamps[-1] - stamps[0]) / 1000.0), 1),
        "tear_px_per_frame_mean": round(float(per.mean()), 1) if len(per) else 0.0,
        "tear_px_per_frame_p90": round(float(np.percentile(per, 90)), 1) if len(per) else 0.0,
        "tear_px_per_frame_max": int(per.max()) if len(per) else 0,
        "frames_with_tears": int((per >= a.frame_px).sum()),
        "frames_with_tears_pct": round(100.0 * float((per >= a.frame_px).mean()), 1) if len(per) else 0.0,
        "worst_frames": [int(i + g) for i in np.argsort(per)[::-1][:6]],
    }
    if a.heat:
        from PIL import Image
        hm = np.clip(heat / max(1.0, np.percentile(heat[heat > 0], 99) if (heat > 0).any() else 1.0) * 255, 0, 255).astype(np.uint8)
        Image.fromarray(hm).save(Path(a.heat).with_name(Path(a.heat).stem + "-" + Path(run).name + ".png"))
    if a.png:
        from PIL import Image
        d = Path(a.png)
        d.mkdir(parents=True, exist_ok=True)
        for i in res["worst_frames"][:3]:
            Image.fromarray(np.ascontiguousarray(frames[i])).save(d / f"{Path(run).name}-f{i:04d}.png")
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("test")
    ap.add_argument("--control")
    ap.add_argument("--before")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--heat")
    ap.add_argument("--png")
    ap.add_argument("--run", type=int, default=24, help="vertical run (rows) of a line")
    ap.add_argument("--thresh", type=float, default=10.0, help="mean luma step a line stands out by")
    ap.add_argument("--gap", type=int, default=6, help="frames to the aligned neighbours")
    ap.add_argument("--frame-px", type=int, default=40, help="tear px for a frame to count as torn")
    ap.add_argument("--player-mask", default="auto", help="auto (50 %% full-screen capture), none, or w,h in capture px round the centre")
    ap.add_argument("--movers", action="store_true", help="static-camera mode: seam ratio on the columns of small walkers (hens)")
    ap.add_argument("--mover-every", type=int, default=2, help="sample every Nth frame")
    ap.add_argument("--mover-min", type=int, default=12, help="walker blob size range, capture px")
    ap.add_argument("--mover-max", type=int, default=72)
    ap.add_argument("--mover-rows", type=int, default=70, help="rows above the walker to read")
    ap.add_argument("--mask", action="append", default=[], help="x,y,w,h in capture px where no walker is looked for (HUD, overlay); repeatable")
    a = ap.parse_args()
    test = measure(a.test, a)
    print("test:", json.dumps(test))
    control = measure(a.control, a) if a.control else None
    before = measure(a.before, a) if a.before else None
    if control:
        print("control:", json.dumps(control))
    if before:
        print("before:", json.dumps(before))
    out = {"test": test, "control": control, "before": before}
    if control:
        from typesafe_client import ask, choice, noul, fmt  # noqa: E402
        state = {
            "setup": "Frame sequences read back from the game while the player walks circles through tall grass and bushes "
                     "(Jev directs the walk; chickens wander in the yard). A tear pixel is a straight vertical line inside "
                     "foliage (green on both sides) that is absent from the frames 0.2 s before and after once the camera's "
                     "motion is aligned out: grass blades, fence posts and buildings are in every frame and cancel; a seam "
                     "that travels with a walker does not. The player's own body is masked. `control` = the same walk with the "
                     "feature under test off (its count is the noise floor: wind sway, animation); `test` = the build under test; "
                     "`before` (optional) = the build the player reported. " + a.context,
            "report": "whenever an entity (the player, chickens) walks through foliage there are vertical visual artifacts",
            "test": test, "control": control, "before": before,
        }
        if a.movers:
            state["setup"] += (" Walker mode (static camera): every hen-sized walker found in a sampled frame is a sample; "
                               "walker_seam_ratio = the horizontal luma step on the walker's own column in the 70 rows above "
                               "it divided by the same step 20-40 px to its sides (grass texture: ~1; a vertical seam rising "
                               "from the walker: well above 1). walker_seam_share_over_2x = the share of samples above 2.")
            metric = ("walker_seam_ratio_median / _p90 and walker_seam_share_over_2x (compare with control; a median ratio "
                      "within ~0.15 of control and a similar share is parity)")
        else:
            metric = ("tear_px_per_frame_mean / _p90 and frames_with_tears_pct; a ratio under ~1.5x of control is noise, "
                      "several times control is the artifact")
        questions = {
            "test_has_tears": noul("Does `test` show clearly more vertical seams in the foliage than `control`? Judge " + metric + "."),
            "kind": choice({"question": "What best describes `test` against `control`?",
                            "note": "Judge " + metric + "."},
                           {"tears": "test is several times control: walkers tear vertical seams through the foliage",
                            "parity": "test is at control's level: no tears beyond the noise floor",
                            "inconclusive": "too few frames or contradicting numbers"}),
        }
        if before:
            questions["before_has_tears"] = noul("Does `before` show clearly more vertical seams in the foliage than `control`? Judge " + metric + ".")
        log = []
        answers = ask(state, questions, log=log)
        print("jev:")
        print("  " + fmt(answers).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        out.update({"answers": answers, "typesafe": log[0]})
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
