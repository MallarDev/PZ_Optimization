#!/usr/bin/env python3
"""Is every setting of the PZ Optimization options tab readable? (2026-10-04, the 1080p report: the "Before / after clips"
switch overlapped the search line on a 1920 x 1080 window.)

Reads a run of the harness rig `options_read=1` (pzopt_harness.lua: every page of the tab, every scroll stop, the game's own
screenshots pzopt-read-<n>.png, and the console lines READWIN / READPAGE / READOVL / READOUT / READROW / READSHOT), then
  - coverage: every setting shows up on some page (READROW keys against the tab's count in the console),
  - cuts: a label the tab shortened with "..." to fit its column (READROW shown != full),
  - geometry: elements that overlap (READOVL) or leave their column, under the sidebar or the preview (READOUT),
  - pixels: every label rectangle of every screenshot read back with tesseract and compared with the text the tab drew,
and asks Jev (numbers only) whether every setting is readable. Writes <run>/readability.json and, for the labels OCR could
not read, <run>/readability-unreadable.png (crops with what OCR read).

    harness/options-readability.py <run> [--shots <dir>] [--no-jev]
    (the screenshots: <run>/shots/pzopt-read-<n>.png; copy them out of ~/Zomboid/Screenshots/ after the job)
"""
import argparse
import difflib
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

ROW = re.compile(r"READROW n=(\d+) page=(\S+) key=(\S+) x=(-?\d+) y=(-?\d+) w=(\d+) h=(\d+) text=(.*)\|shown=(.*)\|full=(.*)$")
CONT = re.compile(r"READCONT n=(\d+) page=(\S+) key=(\S+) x=(-?\d+) y=(-?\d+) w=(\d+) h=(\d+) text=(.*)$")
SIDE = re.compile(r"READSIDE page=(\S+) entry=(\S+)")
GAP = re.compile(r"READGAP n=(\d+) page=(\S+) x=(-?\d+) y=(-?\d+) w=(\d+) h=(\d+) between=(.*)$")
GAP_BRIGHT = 0.03  # share of text pixels (> 70 of 255) that makes an empty strip "not empty"
TXT = re.compile(r"READTXT n=(\d+) page=(\S+) x=(-?\d+) y=(-?\d+) w=(\d+) h=(\d+) text=(.*)$")
OVL = re.compile(r"READOVL page=(\S+) a=(\S+) b=(\S+) overlap=(\d+)x(\d+)")
OUT = re.compile(r"READOUT page=(\S+) el=(\S+) x=(-?\d+) w=(\d+) left=(-?\d+) right=(-?\d+)")
PAGE = re.compile(r"READPAGE page=(\S+) elements=(\d+) overlaps=(\d+)")
WIN = re.compile(r"READWIN x=(-?\d+) y=(-?\d+) w=(\d+) h=(\d+) screen=(\d+)x(\d+) pages=(\d+)")
TOTAL = re.compile(r"options tab PZ Optimization: (\d+) settings")
OK_RATIO = 0.80
# the English model is under ~/.local/share/tessdata on this machine (the system tessdata has none; ui-drive.py does the same)
ENV = dict(os.environ)
if "TESSDATA_PREFIX" not in ENV and os.path.isdir(os.path.expanduser("~/.local/share/tessdata")):
    ENV["TESSDATA_PREFIX"] = os.path.expanduser("~/.local/share/tessdata")


def norm(s):
    return " ".join(re.sub(r"[^a-z0-9%]+", " ", s.lower()).split())


def ocr(img):
    """One text line: grey, 3x (Lanczos), then light text -> black on white at a fixed threshold, tesseract --psm 7. Upscale
    before the threshold: thresholding the 13 px text first broke its strokes (the menu art shows through the panel)."""
    g = ImageOps.grayscale(img)
    g = g.resize((g.width * 3, g.height * 3), Image.LANCZOS)
    # light text on the dark panel -> black on white; a selected button (dark text on a white fill) the other way round
    light_bg = sum(g.get_flattened_data()) / (g.width * g.height) > 128
    g = g.point((lambda v: 0 if v < 140 else 255) if light_bg else (lambda v: 0 if v > 140 else 255))
    p = subprocess.run(["tesseract", "stdin", "stdout", "--psm", "7", "-l", "eng"], input=_png(g),
                       capture_output=True, check=False, env=ENV)
    return p.stdout.decode("utf-8", "replace").strip()


def _png(im):
    import io
    b = io.BytesIO()
    im.save(b, "PNG")
    return b.getvalue()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--shots")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    run = Path(a.run)
    shots = Path(a.shots) if a.shots else run / "shots"
    rows, ovl, out, pages, win, total, side, hdr, gaps = [], [], [], {}, None, None, set(), [], []
    for line in open(run / "console.txt", errors="replace"):
        m = ROW.search(line)
        if m:
            rows.append(dict(n=int(m[1]), page=m[2], key=m[3], x=int(m[4]), y=int(m[5]), w=int(m[6]), h=int(m[7]),
                             text=m[8].strip(), shown=m[9].strip(), full=m[10].strip(), cont=False))
            continue
        m = CONT.search(line)
        if m:
            rows.append(dict(n=int(m[1]), page=m[2], key=m[3], x=int(m[4]), y=int(m[5]), w=int(m[6]), h=int(m[7]),
                             text=m[8].strip(), shown=m[8].strip(), full=m[8].strip(), cont=True))
            continue
        m = GAP.search(line)
        if m:
            gaps.append(dict(n=int(m[1]), page=m[2], x=int(m[3]), y=int(m[4]), w=int(m[5]), h=int(m[6]), between=m[7].strip()))
            continue
        m = TXT.search(line)
        if m:
            hdr.append(dict(n=int(m[1]), page=m[2], key="header", x=int(m[3]), y=int(m[4]), w=int(m[5]), h=int(m[6]),
                            text=m[7].strip(), shown=m[7].strip(), full=m[7].strip(), cont=False))
            continue
        m = SIDE.search(line)
        if m:
            side.add(m[2].replace("_", " "))
            continue
        m = OVL.search(line)
        if m:
            ovl.append(dict(page=m[1], a=m[2], b=m[3], w=int(m[4]), h=int(m[5])))
            continue
        m = OUT.search(line)
        if m:
            out.append(dict(page=m[1], el=m[2], x=int(m[3]), w=int(m[4]), left=int(m[5]), right=int(m[6])))
            continue
        m = PAGE.search(line)
        if m:
            pages[m[1]] = dict(elements=int(m[2]), overlaps=int(m[3]))
            continue
        m = WIN.search(line)
        if m:
            win = dict(x=int(m[1]), y=int(m[2]), w=int(m[3]), h=int(m[4]), screen=f"{m[5]}x{m[6]}", pages=int(m[7]))
            continue
        m = TOTAL.search(line)
        if m:
            total = int(m[1])
    if not rows:
        sys.exit("no READROW lines: was the run made with --flag options_read=1?")

    # pixels: every label of every screenshot
    cache = {}

    def check(r):
        f = shots / f"pzopt-read-{r['n']}.png"
        if f not in cache:
            cache[f] = Image.open(f).convert("RGB") if f.exists() else None
        im = cache[f]
        if im is None:
            return dict(r, ocr=None, ratio=None)
        if r["key"] == "header":  # a button: just inside its frame (a margin outside a white button thresholds to a black frame)
            box = (r["x"] + 3, r["y"] + 3, r["x"] + r["w"] - 3, r["y"] + r["h"] - 3)
        else:
            box = (r["x"] - 4, r["y"] - 2, r["x"] + r["w"] + 4, r["y"] + r["h"] + 2)
        crop = im.crop((max(0, box[0]), max(0, box[1]), min(im.width, box[2]), min(im.height, box[3])))
        text = ocr(crop)
        ratio = difflib.SequenceMatcher(None, norm(text), norm(r["text"])).ratio()
        return dict(r, ocr=text, ratio=round(ratio, 3), crop=crop)

    with ThreadPoolExecutor(max_workers=os.cpu_count() or 8) as ex:
        checked = list(ex.map(check, rows))
        hdr_checked = list(ex.map(check, hdr))
    hdr_failed = [r for r in hdr_checked if r["ratio"] is not None and r["ratio"] < OK_RATIO]

    def gap_share(g):
        f = shots / f"pzopt-read-{g['n']}.png"
        if f not in cache:
            cache[f] = Image.open(f).convert("RGB") if f.exists() else None
        if cache[f] is None:
            return None
        px = list(ImageOps.grayscale(cache[f].crop((g["x"], g["y"], g["x"] + g["w"], g["y"] + g["h"]))).get_flattened_data())
        # 70: the dim hint text (~100) counts, the panel over the menu art (<= 55 in the strips) does not; calibrated on
        # runs settings-read-1080d (hint spilling: 144 of 146 strips over 3 %) and -1080e (fixed: 0 of 146)
        return sum(1 for v in px if v > 70) / max(1, len(px))
    gap_bad = []
    for g in gaps:
        share = gap_share(g)
        if share is not None and share > GAP_BRIGHT:
            gap_bad.append(dict(g, bright_share=round(share, 3)))
    keys = sorted({r["key"] for r in rows})
    best = {}
    for r in checked:
        k = r["key"] + (" (line 2+)" if r["cont"] else "")
        if r["ratio"] is not None and (k not in best or r["ratio"] > best[k]["ratio"]):
            best[k] = r
    cut = sorted({r["key"]: (r["shown"], r["full"]) for r in rows if not r["cont"] and r["shown"] != r["full"]}.items())
    wrapped = sorted({r["key"] for r in rows if r["cont"]})
    unreadable = sorted((k for k, r in best.items() if r["ratio"] < OK_RATIO), key=lambda k: best[k]["ratio"])
    worst_any = sorted((r for r in checked if r["ratio"] is not None and r["ratio"] < OK_RATIO), key=lambda r: r["ratio"])
    card = {
        "screen": win,
        "settings_in_tab": total,
        "coverage": {"settings_seen": len(keys), "missing_count": (total - len(keys)) if total else None},
        "cut_labels": {"count": len(cut), "examples": [{"key": k, "shown": s, "full": f} for k, (s, f) in cut[:12]]},
        "wrapped_labels": {"count": len(wrapped), "note": "names on two or more lines, read back line by line"},
        "sidebar_cut": {"count": len(side), "entries": sorted(side)},
        "overlaps": {"total": len(ovl), "pages_with_overlaps": sorted({o["page"] for o in ovl})[:20],
                     "examples": ovl[:12]},
        "outside_column": {"total": len(out), "examples": out[:12]},
        "ocr": {"label_images_read": sum(1 for r in checked if r["ratio"] is not None),
                "name_lines_read": len(best), "pass_ratio": OK_RATIO,
                "name_lines_whose_best_read_failed": len(unreadable),
                "median_ratio": sorted(r["ratio"] for r in best.values())[len(best) // 2] if best else None,
                "worst": [{"key": k, "shown": best[k]["shown"], "ocr": best[k]["ocr"], "ratio": best[k]["ratio"]}
                          for k in unreadable[:15]],
                "single_reads_below_pass": len(worst_any)},
        "header_texts": {"read": sum(1 for r in hdr_checked if r["ratio"] is not None), "failed": len(hdr_failed),
                         "note": "the header's View / Simple / Advanced / Everything / Before-after clips / status texts, read "
                                 "back by OCR on every page: text drawn over them makes them fail",
                         "worst": [{"page": r["page"], "text": r["text"], "ocr": r["ocr"], "ratio": r["ratio"]}
                                   for r in sorted(hdr_failed, key=lambda r: r["ratio"])[:10]]},
        "header_gaps": {"checked": len(gaps), "not_empty": len(gap_bad), "bright_limit": GAP_BRIGHT,
                        "note": "the empty strips between the search line's elements: text spilling out of an element shows here",
                        "examples": [{"page": g["page"], "between": g["between"], "bright_share": g["bright_share"]} for g in gap_bad[:8]]},
        "pages_audited": len(pages),
    }
    if unreadable:
        tiles = [best[k] for k in unreadable[:40]]
        tw = max(t["crop"].width for t in tiles) * 2 + 20
        sheet = Image.new("RGB", (tw + 700, 52 * len(tiles) + 10), (20, 20, 24))
        d = ImageDraw.Draw(sheet)
        for i, t in enumerate(tiles):
            c = t["crop"].resize((t["crop"].width * 2, t["crop"].height * 2))
            sheet.paste(c, (10, 10 + 52 * i))
            d.text((tw, 14 + 52 * i), f"{t['key']}  ratio {t['ratio']}  ocr: {t['ocr'][:60]}", fill=(255, 220, 120))
        sheet.save(run / "readability-unreadable.png")
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, choice, fmt, noul
        q = {
            "every_setting_shown": noul(
                "Does every setting of the tab appear on some page (`coverage.settings_seen` equals `settings_in_tab`, "
                "`coverage.missing_count` 0)?"),
            "nothing_overlaps": noul(
                "Is the layout clean at this screen size: no two elements overlap (`overlaps.total` 0), nothing sits "
                "outside its column (`outside_column.total` 0), the header's texts read back clean (`header_texts.failed` 0) and "
                "the strips between them hold no stray text (`header_gaps.not_empty` 0)?"),
            "labels_whole": noul("Is every setting's name shown whole, never cut with '...' (`cut_labels.count` 0; a name wrapped "
                                 "onto a second line counts as whole), and every sidebar category name too (`sidebar_cut.count` 0)?"),
            "labels_legible": noul(
                "Did OCR read every setting's name back from the screenshots (`ocr.name_lines_whose_best_read_failed` 0; a "
                "wrapped name is read line by line, so `ocr.name_lines_read` is larger than the number of settings), or only a "
                "few short names whose `ocr` text clearly is the name with a character misread?"),
            "verdict": choice("Is every setting readable at this screen size?", {
                "readable": "every setting is shown, whole (a name wrapped onto two lines is whole), legible and nothing overlaps",
                "minor_issues": "every setting is legible but a few names are cut or a few elements touch",
                "unreadable": "some settings are missing, overlapped or cannot be read",
            }),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    json.dump(card, open(run / "readability.json", "w"), indent=1, default=str)
    print(f"wrote {run / 'readability.json'}")


if __name__ == "__main__":
    main()
