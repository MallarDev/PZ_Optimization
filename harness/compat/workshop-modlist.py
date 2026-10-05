#!/usr/bin/env python3
"""A loadable Build 42 mod list from Workshop downloads, for run.sh --mod (the many-mods heap test, 2026-10-05).

Usage: workshop-modlist.py [--items ids.txt] CONTENT_DIR...   (CONTENT_DIR = .../workshop/content/108600)
Prints one `--mod <id>` per line, requirements before the mods that need them. Per Workshop item the mods with a
Build 42 folder (mods/<m>/42/mod.info or 42.x) are candidates; an item with several is taken by its first mod
(alphabetical; sub-mods are usually optional variants or patches) plus every mod another chosen mod requires. A mod
whose requirement is not downloaded is left out (reported on stderr), as are mods that declare one another incompatible.
"""
import re, sys
from pathlib import Path


def read_info(path):
    kv = {}
    for line in path.read_text(errors="replace").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            kv.setdefault(k.strip().lower(), v.strip())
    return kv


def ids(v):
    return [x.strip().lstrip("\\").strip() for x in re.split(r"[,;]", v or "") if x.strip().lstrip("\\").strip()]


def main():
    args = sys.argv[1:]
    items_filter = None
    if args and args[0] == "--items":
        items_filter = [l.split()[0] for l in Path(args[1]).read_text().splitlines() if l.strip()]
        args = args[2:]
    mods = {}  # id -> (item, info)
    per_item = {}
    for content in map(Path, args):
        for item in sorted(content.iterdir()):
            if not item.is_dir():
                continue
            for info in sorted(item.glob("mods/*/42*/mod.info")):
                if not re.fullmatch(r"42(\.\d+)*", info.parent.name):
                    continue
                kv = read_info(info)
                mid = kv.get("id")
                if mid and "/" not in mid and "\\" not in mid and mid not in mods:  # run.sh copies a mod to Zomboid/mods/<id>
                    mods[mid] = (item.name, kv)
                    per_item.setdefault(item.name, []).append(mid)
    order = items_filter or list(per_item)
    chosen, missing = [], []

    def add(mid, chain=()):
        if mid in chosen:
            return True
        if mid not in mods or mid in chain:
            return False
        for r in ids(mods[mid][1].get("require")):
            if not add(r, chain + (mid,)):
                missing.append((mid, r))
                return False
        chosen.append(mid)
        return True

    for item in order:
        if item in per_item:
            add(sorted(per_item[item])[0])
    bad = set()
    for m in chosen:
        for x in ids(mods[m][1].get("incompatible")):
            if x in chosen and x not in bad:
                bad.add(m)
    for m, r in missing:
        print(f"skip {m}: requires {r} (not downloaded)", file=sys.stderr)
    for m in sorted(bad):
        print(f"skip {m}: declares an incompatible mod in the list", file=sys.stderr)
    final = [m for m in chosen if m not in bad]
    print(f"{len(final)} mods from {len({mods[m][0] for m in final})} Workshop items", file=sys.stderr)
    for m in final:
        print(f"--mod {m}")


if __name__ == "__main__":
    main()
