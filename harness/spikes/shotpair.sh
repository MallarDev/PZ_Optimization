#!/usr/bin/env bash
# Picture check of the 300 fps loop in one scene: the same still shot with the loop's keys off and on (zombies off, no
# wind / sway animation), compared pixel by pixel (both shots: +2 s and +4 s into the hold). Usage:
#   harness/spikes/shotpair.sh <name> [run.sh scene args...]      e.g. night --flag time_of_day=1 --flag torch=on
# KEYS (env) overrides the keys switched on in the second run.
set -euo pipefail
cd "$(dirname "$0")/../.."
N=${1:?name}; shift
KEYS=${KEYS-"tileVertexDepth tileStateFold texParamCache pplRemap pplVisRebakeFilter sunShadowStaticVehicles"}
on=(); off=(); for k in $KEYS; do on+=(--prop "$k=true"); off+=(--prop "$k=false"); done
base=(--mode bench --flag zoom=1 --flag route=S:30 --flag speed=1 --shot-at 3 --flag wind=0 --flag zombies=off
  --prop foliageSway=false --prop overlay=false --prop reflexSleep=false --prop instrument=true --no-dashboard)
# REF=<name>: reuse that pair's keys-off run as the reference (only the keys-on side runs)
sides="off on"; [[ -n ${REF:-} ]] && sides="on"
for side in $sides; do
  if [[ $side == on ]]; then extra=("${on[@]}"); else extra=("${off[@]}"); fi
  harness/queue.sh submit run --name spike-rerun --intent "300 fps loop picture pair $N ($side)" --progress "pair $N $side" \
    --resource gpu,render-thread --wait -- --label "pair-$N-$side" "${base[@]}" "$@" "${extra[@]}" > /dev/null || true
done
a=$(ls -d harness/runs/pair-"${REF:-$N}"-off-2026* | tail -1); b=$(ls -d harness/runs/pair-"$N"-on-2026* | tail -1)
for f in shot-game.png shot2-game.png; do
  python3 - "$a/$f" "$b/$f" "$N" <<'PY'
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert('RGB'); b = Image.open(sys.argv[2]).convert('RGB')
h = ImageChops.difference(a, b).convert('L').histogram(); n = sum(h)
print(f'{sys.argv[3]} {sys.argv[1].split("/")[-1]}: keys off vs on >8: {100 * sum(h[9:]) / n:.3f} %  >32: {100 * sum(h[33:]) / n:.3f} %')
PY
done
