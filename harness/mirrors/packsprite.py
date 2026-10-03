#!/usr/bin/env python3
"""Extract named sprites from the game's .pack files into PNGs (full frame, offsets applied).

usage: packsprite.py OUTDIR NAME [NAME ...]   (default pack Tiles2x.pack; --pack Tiles1x.pack)
"""
import struct, sys, os, io, argparse
from PIL import Image

GAME = os.environ.get("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid")

def pages(path, want):
    f = open(path, "rb")
    def i32(): return struct.unpack("<i", f.read(4))[0]
    def s(): return f.read(i32()).decode("latin-1")
    ver = 0
    if f.read(4) == b"PZPK":
        ver = i32()
    else:
        f.seek(0)
    for _ in range(i32()):
        name = s(); n = i32(); i32()
        subs = []
        for _ in range(n):
            e = s(); vals = [i32() for _ in range(8)]
            subs.append((e, vals))
        hit = [x for x in subs if x[0] in want]
        if ver >= 1:
            ln = i32(); start = f.tell()
            if hit:
                png = f.read(ln)
            else:
                f.seek(start + ln); png = None
        else:
            raise SystemExit("version 0 pack not supported")
        if hit:
            img = Image.open(io.BytesIO(png)).convert("RGBA")
            for e, (x, y, w, h, ox, oy, fw, fh) in hit:
                frame = Image.new("RGBA", (fw, fh))
                frame.paste(img.crop((x, y, x + w, y + h)), (ox, oy))
                yield e, frame

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out"); ap.add_argument("names", nargs="+")
    ap.add_argument("--pack", default="Tiles2x.pack")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    got = set()
    for e, img in pages(os.path.join(GAME, "media", "texturepacks", a.pack), set(a.names)):
        img.save(os.path.join(a.out, e + ".png")); got.add(e)
    for n in a.names:
        if n not in got: print("missing", n, file=sys.stderr)

if __name__ == "__main__":
    main()
