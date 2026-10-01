#!/usr/bin/env python3
"""Which methods of the overridden game classes we edited, and which boolean pzopt.Config keys each one reads.

pzopt.ModCompat (mod compatibility, 2026-10-01) scans the Java mods of a launch (-javaagent jars, ZombieBuddy mods'
javaJarFile) for patches of the classes we shadow. A patch of a method that carries no `// pzopt:` edit meets
stock-equivalent bytecode (scripts/bytecode-audit.py guarantees it), so only the edited methods matter; for those it
switches off the features that live in the method (the boolean keys it reads, directly as Config.X or through a
pzopt class), so a mod never runs against half of one of our features.

Output: build/classes/pzopt/override-methods.properties, one line per edited method (overloads merged):
    zombie/iso/WorldStreamer#threadLoop=centerFirstLoad,wake
an empty value = an edit without a switch of its own (a decompiler fix, an always-on hook): reported, never switched.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "src/pzopt/pzopt/Config.java"
OVERRIDES = ROOT / "src/overrides"
PZOPT = ROOT / "src/pzopt/pzopt"

BOOL_DECL = re.compile(r'static\s+(?:final\s+|volatile\s+)*boolean\s+([A-Z][A-Z0-9_]*)\s*=\s*bool\(\s*"(\w+)"')
LIVE_BOOL = re.compile(r'^\s*([A-Z][A-Z0-9_]*)\s*=\s*bool\(\s*"(\w+)"', re.M)
# switches that are not one feature: the master switches, measurement and dev rigs, `parallel` (every pool checks it)
SKIP_KEYS = {"enabled", "enhancementsEnabled", "profilerEnabled", "instrument", "dev", "inputLog", "parallel"}
# a pzopt class read by most edits (guards, logs, counters) says nothing about which feature a method holds
SKIP_CLASSES = {"Config", "Overrides", "Log", "Guard", "BuildInfo", "Harness", "HarnessFlags", "Stats", "LoadTrace",
                "GameThreadProfile", "Overlay", "UiProfile", "LuaEventProfile", "GpuSections", "PoolStats", "FrameCap"}
# a pzopt class that reads more keys than this is a hub (many features); a method calling it names none of them
MAX_CLASS_KEYS = 8
CONTROL = {"if", "for", "while", "switch", "catch", "synchronized", "try", "do", "else", "return", "new", "throw"}


def config_fields():
    text = CONFIG.read_text()
    fields = {}
    for rx in (BOOL_DECL, LIVE_BOOL):
        for m in rx.finditer(text):
            key = m.group(2)
            if key not in SKIP_KEYS and not key.startswith("dev"):
                fields[m.group(1)] = key
    return fields


def strip(text):
    """The source with comments, strings and chars blanked (same length, newlines kept), and the marker lines."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            j = min(n, j + 1)
            out.append(c + " " * (j - i - 2) + c if j - i >= 2 else " " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def methods(path):
    """(class internal name, method name, raw body text) for each method body of the file, inner classes as Outer$Inner."""
    raw = path.read_text(errors="replace")
    code = strip(raw)
    pkg = re.search(r'^\s*package\s+([\w.]+)\s*;', code, re.M)
    prefix = pkg.group(1).replace(".", "/") + "/" if pkg else ""
    stack = []  # entries: ("class", name) | ("method", name, start) | ("other",)
    last = 0  # start of the current statement / header
    i = 0
    out = []
    while i < len(code):
        c = code[i]
        if c == "{":
            header = code[last:i]
            enclosing = next((s for s in reversed(stack) if s[0] in ("class", "method")), None)
            cm = re.search(r'\b(class|interface|enum|record)\s+(\w+)', header)
            mm = re.search(r'(\w+)\s*\([^;{}]*\)\s*(?:throws\s+[\w.,\s<>]+)?\s*$', header)
            if cm and not mm or cm and enclosing is None:
                stack.append(("class", cm.group(2)))
            elif mm and enclosing is not None and enclosing[0] == "class" and mm.group(1) not in CONTROL \
                    and not re.search(r'\bnew\s', header) and "=" not in header.split("(")[0]:
                stack.append(("method", mm.group(1), i))
            else:
                stack.append(("other",))
            last = i + 1
        elif c == "}":
            top = stack.pop() if stack else ("other",)
            if top[0] == "method":
                classes = [s[1] for s in stack if s[0] == "class"]
                if classes and not any(s[0] == "method" for s in stack):
                    out.append((prefix + "$".join(classes), top[1], raw[top[2]:i + 1]))
            last = i + 1
        elif c == ";":
            last = i + 1
        i += 1
    return out


def config_methods(ref):
    """Config's own static methods that read a key (effectiveWake() -> wake), as scripts/option-classes.py counts them."""
    src = strip(CONFIG.read_text())
    via = {}
    for m in re.finditer(r'static\s+[\w<>\[\]]+\s+(\w+)\s*\([^)]*\)\s*(?:throws[^{]*)?\{', src):
        depth, i = 1, m.end()
        while depth and i < len(src):
            depth += {"{": 1, "}": -1}.get(src[i], 0)
            i += 1
        keys = {ref[f] for f in re.findall(r'\b([A-Z][A-Z0-9_]*)\b', src[m.end():i]) if f in ref}
        if 0 < len(keys) <= 4:
            via[m.group(1)] = keys
    return via


def reads(code, ref, cfg):
    keys = {ref[f] for f in re.findall(r'\bConfig\.([A-Z][A-Z0-9_]*)\b', code) if f in ref}
    for f in re.findall(r'\bConfig\.(\w+)\s*\(', code):
        keys |= cfg.get(f, set())
    return keys


def pzopt_class_keys(ref, cfg):
    keys = {}
    for path in sorted(PZOPT.glob("*.java")):
        if path == CONFIG or path.stem in SKIP_CLASSES:
            continue
        found = reads(strip(path.read_text(errors="replace")), ref, cfg)
        if 0 < len(found) <= MAX_CLASS_KEYS:
            keys[path.stem] = found
    return keys


def main():
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/classes/pzopt"
    fields = config_fields()
    cfg = config_methods(fields)
    via = pzopt_class_keys(fields, cfg)
    result = {}
    for path in sorted(OVERRIDES.rglob("*.java")):
        for cls, name, body in methods(path):
            if "// pzopt" not in body:
                continue
            code = strip(body)
            keys = reads(code, fields, cfg)
            for c in re.findall(r'\bpzopt\.([A-Z]\w*)\b', code):
                keys |= via.get(c, set())
            result.setdefault(cls + "#" + name, set()).update(keys)
    lines = ["# generated by scripts/override-methods.py: edited override methods -> the boolean keys they read (pzopt.ModCompat)"]
    for m in sorted(result):
        lines.append("%s=%s" % (m, ",".join(sorted(result[m]))))
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "override-methods.properties").write_text("\n".join(lines) + "\n")
    switched = sum(1 for v in result.values() if v)
    print("override-methods: %d edited methods, %d with switches -> %s" % (len(result), switched, out_dir / "override-methods.properties"))
    if "-v" in sys.argv[2:]:
        for m in sorted(result):
            print("  %s: %s" % (m, " ".join(sorted(result[m]))))


if __name__ == "__main__":
    main()
