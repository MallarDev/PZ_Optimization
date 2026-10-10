#!/usr/bin/env python3
"""The before / after video of the three mirror reports (2026-10-08, docs/findings-mirror-rooms-2026-10-08.md).

From the runs' own 10 fps devCapture crops (1:1 screen px of the room pair), the same Jev walk before (devMirrorsSkip, the old
ways) and after (the defaults), side by side:
  1. the player in the room behind the hall's wall, the hall not seen yet: before, the hall's mirror shows him and its
     unseen room; after, the stock glass (full frame + the north-east mirror enlarged)
  2. walking in the hall: the north-east mirror tracked and enlarged (the people pass off, so only the room's reflection
     changes): before, the hidden-floor stand-ins jump; after, steady
then a card with the measured numbers. Frames composed here, a lossless SDR intermediate, harness/encode-av1-hdr.sh makes
the AV1 HDR (PQ / BT.2020) video and its poster. Run it as a queue media job.

  python3 harness/mirrors/stitch-rooms.py --full-before <run> --full-after <run> --static-before <run> --static-after <run>
      --out docs/media/mirror-rooms-before-after.mp4 [--preview /tmp/dir]
"""
import argparse, os, re, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H, FPS = 1920, 1080, 10
OX, OY = 1660, 330  # the captures' crop origin on the 5120x2160 screen
FONT = "/usr/share/fonts/noto/NotoSans-Regular.ttf"
FONTB = "/usr/share/fonts/noto/NotoSans-Bold.ttf"
NE = "8131,11546,2"


def font(size, bold=False):
    return ImageFont.truetype(FONTB if bold else FONT, size)


class Run:
    def __init__(self, path):
        self.path = path
        cap = os.path.join(path, "capture")
        lines = open(os.path.join(cap, "index.txt")).read().split("\n")
        kv = dict(p.split("=") for p in lines[0].split())
        self.w, self.h = int(kv["w"]), int(kv["h"])
        st = np.array([int(x) for x in lines[1:] if x.strip()])
        n = os.path.getsize(os.path.join(cap, "frames.rgba")) // (self.w * self.h * 4)
        self.st = st[:n]
        self.mm = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r", shape=(n, self.h, self.w, 4))
        self.txt = open(os.path.join(path, "console.txt"), errors="replace").read()
        self.rects = []
        for line in self.txt.split("\n"):
            if "dev rects" not in line:
                continue
            m = re.search(r"epoch_ms=(\d+)", line)
            r = re.search(r"\[\w+ (-?\d+),(-?\d+) (\d+)x(\d+) @" + NE + r"\]", line)
            if m and r:
                self.rects.append((int(m.group(1)), tuple(map(int, r.groups()))))
        self.rt = np.array([a for a, _ in self.rects])

    def event(self, pattern):
        m = re.search(pattern + r".*?epoch_ms=(\d+)", self.txt)
        return int(m.group(1))

    def frame_at(self, t):
        i = int(np.argmin(np.abs(self.st - t)))
        return Image.fromarray(np.asarray(self.mm[i])[::-1][..., :3].copy())

    def rect_at(self, t):
        j = int(np.argmin(np.abs(self.rt - t)))
        x, y, w, h = self.rects[j][1]
        return x - OX, y - OY, w, h


def header(img, title, sub=None):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, 64], fill=(14, 14, 18))
    d.text((24, 10), title, font=font(30, True), fill=(240, 240, 240))
    if sub:
        d.text((W - 24 - d.textlength(sub, font=font(22)), 18), sub, font=font(22), fill=(180, 180, 190))


def label(img, x, y, text, color):
    d = ImageDraw.Draw(img)
    f = font(28, True)
    tw = d.textlength(text, font=f)
    d.rectangle([x, y, x + tw + 24, y + 44], fill=(0, 0, 0))
    d.text((x + 12, y + 4), text, font=f, fill=color)


def card(lines, frames):
    img = Image.new("RGB", (W, H), (14, 14, 18))
    d = ImageDraw.Draw(img)
    y = 180
    for i, (text, size, bold, col) in enumerate(lines):
        f = font(size, bold)
        d.text(((W - d.textlength(text, font=f)) / 2, y), text, font=f, fill=col)
        y += size + 28
    return [img] * frames


def seg_rooms(rb, ra, slow=2):
    """The player behind the hall's wall: full frames + the NE mirror enlarged, from his arrival on the spot until he walks
    off to the hall (the camera moves then), at half speed."""
    tb = rb.event(r"mirror walk: arrived at mirror 1 ")
    ta = ra.event(r"mirror walk: arrived at mirror 1 ")
    stay = min(rb.event(r"mirror walk: to mirror 2 ") - tb, ra.event(r"mirror walk: to mirror 2 ") - ta) + 300
    out = []
    pw = (W - 60) // 2
    for k in range((stay + 500) * FPS * slow // 1000):
        img = Image.new("RGB", (W, H), (14, 14, 18))
        header(img, "The player in the room behind the hall's wall: the hall not seen yet", "Jev walk, Rosewood, same frames before | after")
        for col, (run, t0, name, c) in enumerate(((rb, tb, "BEFORE", (255, 120, 110)), (ra, ta, "AFTER", (120, 230, 140)))):
            t = t0 - 500 + k * 1000 // (FPS * slow)
            fr = run.frame_at(t)
            x0 = 20 + col * (pw + 20)
            full = fr.resize((pw, pw * fr.height // fr.width), Image.LANCZOS)
            img.paste(full, (x0, 76))
            # the north-east mirror enlarged under it
            rx, ry, rw, rh = run.rect_at(t)
            cx, cy = rx + rw // 2, ry + rh // 4  # (the mirror's upper part: the next building hides the rest from here)
            zw, zh = 300, 116
            box = (max(0, cx - zw // 2), max(0, cy - zh // 2), max(0, cx - zw // 2) + zw, max(0, cy - zh // 2) + zh)
            zoom = fr.crop(box).resize((pw, H - (76 + full.height) - 30), Image.NEAREST)
            yz = 76 + full.height + 10
            img.paste(zoom, (x0, yz))
            d = ImageDraw.Draw(img)
            # where the zoom is in the full frame
            s = pw / fr.width
            d.rectangle([x0 + box[0] * s, 76 + box[1] * s, x0 + box[2] * s, 76 + box[3] * s], outline=(255, 220, 80), width=2)
            label(img, x0 + 10, 86, name, c)
            cap = "his reflection in the hall's mirror, the unseen hall reflected" if col == 0 else "plain glass: another room's person and an unseen room are not reflected"
            f = font(22, True)
            d.rectangle([x0, yz, x0 + d.textlength(cap, font=f) + 20, yz + 36], fill=(0, 0, 0))
            d.text((x0 + 10, yz + 4), cap, font=f, fill=(255, 220, 80))
        out.append(img)
    return out


def tracked(run, t, prev, zw=300, zh=320):
    """The NE mirror's surroundings, centred on its rect, refined on the wall round it against the previous crop."""
    fr = run.frame_at(t)
    a = np.asarray(fr).astype(np.float32).mean(2)
    rx, ry, rw, rh = run.rect_at(t)
    cx, cy = rx + rw // 2, ry + rh // 2
    if prev is not None:
        pa, (pcx, pcy) = prev
        best = None
        for dy in range(-16, 17):
            for dx in range(-16, 17):
                x, y = cx + dx - zw // 2, cy + dy - zh // 2
                if x < 0 or y < 0 or x + zw > a.shape[1] or y + zh > a.shape[0]:
                    continue
                c = a[y:y + zh, x:x + zw]
                m = np.ones_like(c, bool)
                m[zh // 2 - rh // 2 - 4:zh // 2 + rh // 2 + 4, zw // 2 - rw // 2 - 4:zw // 2 + rw // 2 + 4] = False
                e = np.abs(c - pa)[m].mean()
                if best is None or e < best[0]:
                    best = (e, dx, dy)
        if best is not None and best[0] < 12:
            cx, cy = cx + best[1], cy + best[2]
    x = int(min(max(0, cx - zw // 2), a.shape[1] - zw)); y = int(min(max(0, cy - zh // 2), a.shape[0] - zh))
    crop = fr.crop((x, y, x + zw, y + zh))
    return crop, (np.asarray(crop).astype(np.float32).mean(2), (cx, cy))


def seg_flicker(sb, sa, before_s=0, after_s=12):
    tb = sb.event(r"mirror walk: arrived at mirror 2 ")
    ta = sa.event(r"mirror walk: arrived at mirror 2 ")
    out = []
    pw = (W - 60) // 2
    pb = pa_ = None
    for k in range((before_s + after_s) * FPS):
        img = Image.new("RGB", (W, H), (14, 14, 18))
        header(img, "Walking in the hall: the north-east mirror, tracked and enlarged", "people in mirrors off: the room's reflection alone")
        for col, (run, t0, name, c) in enumerate(((sb, tb, "BEFORE", (255, 120, 110)), (sa, ta, "AFTER", (120, 230, 140)))):
            t = t0 - before_s * 1000 + k * 1000 // FPS
            crop, st = tracked(run, t, pb if col == 0 else pa_)
            if col == 0:
                pb = st
            else:
                pa_ = st
            x0 = 20 + col * (pw + 20)
            h = H - 76 - 70
            z = crop.resize((int(h * crop.width / crop.height), h), Image.NEAREST)
            img.paste(z, (x0 + (pw - z.width) // 2, 76))
            label(img, x0 + 10, 86, name, c)
            d = ImageDraw.Draw(img)
            cap = "the hidden floor re-sampled every 30 frames: the grey area jumps" if col == 0 else "re-drawn only when the room changes: steady while walking"
            d.text((x0 + 10, H - 56), cap, font=font(24, True), fill=(255, 220, 80))
        out.append(img)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--full-before", required=True)
    ap.add_argument("--full-after", required=True)
    ap.add_argument("--static-before", required=True)
    ap.add_argument("--static-after", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--preview")
    a = ap.parse_args()
    fb, fa, sb, sa = Run(a.full_before), Run(a.full_after), Run(a.static_before), Run(a.static_after)
    frames = []
    frames += card([("Mirrors: three reports, before and after", 54, True, (240, 240, 240)),
                    ("1. a mirror reflected a person standing in another room, behind its wall", 32, False, (210, 210, 215)),
                    ("2. mirrors in rooms the player had not discovered showed their reflection", 32, False, (210, 210, 215)),
                    ("3. the reflection flickered while the player walked", 32, False, (210, 210, 215)),
                    ("Jev walks the player from the room behind into the mirrors' room and back, same walk both times", 26, False, (160, 160, 170))], 4 * FPS)
    frames += seg_rooms(fb, fa)
    frames += seg_flicker(sb, sa)
    frames += card([("Measured over the same Jev walk (harness/mirrors/room-judge.py)", 40, True, (240, 240, 240)),
                    ("person mirrored from another room: 11,494 frames  ->  0", 32, False, (210, 210, 215)),
                    ("unseen mirrors showing a reflection: 11  ->  0", 32, False, (210, 210, 215)),
                    ("reflection re-draws over the walk: 7,443  ->  537", 32, False, (210, 210, 215)),
                    ("flicker while walking (NE / NW / SW mirror): 2.33 / 0.96 / 1.38  ->  1.38 / 0.63 / 1.03", 32, False, (210, 210, 215)),
                    ("frame time 3.4 ms mean both, p99 5.1 -> 4.9 ms; game thread 69 -> 65 % of a core", 28, False, (160, 160, 170)),
                    ("Jev: fixed", 36, True, (120, 230, 140))], 5 * FPS)
    if a.preview:
        os.makedirs(a.preview, exist_ok=True)
        for i in (4 * FPS + 10, 4 * FPS + 60, len(frames) - 13 * FPS - 50, len(frames) - 5 * FPS - 1):
            if i < len(frames):
                frames[i].save(os.path.join(a.preview, f"f{i:04d}.png"))
        print(len(frames), "frames; previews in", a.preview)
        return
    tmp = a.out + ".sdr.mp4"
    p = subprocess.Popen(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                          "-c:v", "libx264", "-crf", "8", "-preset", "slow", "-pix_fmt", "yuv420p", "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", tmp], stdin=subprocess.PIPE)
    for f in frames:
        p.stdin.write(f.tobytes())
    p.stdin.close()
    p.wait()
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    subprocess.run([os.path.join(here, "encode-av1-hdr.sh"), tmp, a.out], check=True)
    os.remove(tmp)


if __name__ == "__main__":
    main()
