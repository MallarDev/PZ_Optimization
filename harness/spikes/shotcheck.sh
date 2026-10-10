#!/usr/bin/env bash
# Picture check of the 300 fps loop: the Rosewood house still shot (no wind, no sway) with extra props, compared with the
# stock-path reference shot (tvd-shot-ns-false). Usage: harness/spikes/shotcheck.sh <label> [--prop k=v]...
set -euo pipefail
cd "$(dirname "$0")/../.."
L=${1:?label}; shift
harness/queue.sh submit run --name spike-rerun --intent "300 fps loop picture check: $L" --progress "shot $L" --resource gpu,render-thread --wait -- \
  --label "$L" --mode bench --flag start=8147,11507 --flag zoom=1 --flag route=S:30 --flag speed=1 --shot-at 3 --flag wind=0 --flag zombies=off \
  --prop foliageSway=false --prop overlay=false --prop reflexSleep=false --prop instrument=true --no-dashboard "$@" > /dev/null || true
a=$(ls -d harness/runs/${SHOT_REF:-tvd-shot-ref3}-2026* | tail -1); b=$(ls -d harness/runs/"$L"-2026* | tail -1)
python3 - "$a/shot-game.png" "$b/shot-game.png" <<'PY'
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert('RGB'); b = Image.open(sys.argv[2]).convert('RGB')
h = ImageChops.difference(a, b).convert('L').histogram(); n = sum(h)
print(f'picture vs stock path: >8: {100 * sum(h[9:]) / n:.3f} %  >32: {100 * sum(h[33:]) / n:.3f} %  (same-path reference pair: 0.015 % / 0.003 %)')
PY
