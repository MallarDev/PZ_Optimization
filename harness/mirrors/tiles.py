#!/usr/bin/env python3
"""List tile definitions matching a property (default IsMirror) from the game's .tiles files.

usage: tiles.py [--prop IsMirror] [--all]   prints sprite name + selected properties
"""
import struct, sys, glob, os, argparse

GAME = os.environ.get("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid")

def read(path):
    data = open(path, "rb").read()
    pos = 4
    def i32():
        nonlocal pos
        v = struct.unpack_from("<i", data, pos)[0]; pos += 4; return v
    def s():
        nonlocal pos
        e = data.index(b"\n", pos); v = data[pos:e].decode("latin-1").rstrip("\r"); pos = e + 1; return v
    assert data[:4] == b"tdef"
    i32()
    for _ in range(i32()):
        name = s().strip(); image = s(); i32(); i32(); i32()
        for m in range(i32()):
            props = {}
            for _ in range(i32()):
                k = s(); v = s(); props[k] = v
            yield f"{name}_{m}", props

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prop", default="IsMirror")
    ap.add_argument("--keys", default="Facing,CustomName,GroupName,MoveType,Material2,Material3,MaterialType")
    a = ap.parse_args()
    keys = a.keys.split(",")
    for f in sorted(glob.glob(os.path.join(GAME, "media", "*.tiles"))):
        for n, p in read(f):
            if a.prop in p:
                print(n, " ".join(f"{k}={p[k]}" for k in keys if k in p))

if __name__ == "__main__":
    main()
