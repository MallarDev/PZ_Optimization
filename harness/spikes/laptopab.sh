#!/usr/bin/env bash
# The 300 fps loop's changes on a laptop (flip / mac): a still-shot picture pair and a short Rosewood drive, the new keys
# off vs on, with the desktop tab file's Visuals as props on an empty options file. Each laptop runs under its own queue
# session (PZQ_SESSION=<this session>-<machine>), so the calling session stays bound to the desktop.
# Usage: harness/spikes/laptopab.sh <machine> <suffix> [drive runs per side, default 2]
# SHOT_EXTRA (env): more run.sh args for the picture pair, e.g. "--prop cloudShadows=false" (the cloud field drifts in
# real time, so two launches never share it: it moved sunlit walls and the grass by 5 % between the 2026-10-09 m2 / f1 shots).
set -euo pipefail
cd "$(dirname "$0")/../.."
M=${1:?machine}; SUF=${2:?suffix}; N=${3:-2}
export PZQ_SESSION="${CLAUDE_CODE_SESSION_ID:-spike}-$M"
case $M in
  mac) EMPTY=/Users/diegovillalobos/pzopt-defaults.ini; SHOT=(--flag shot_at=${SHOT_AT:-3}) ;;
  *) EMPTY=/home/diego/pzopt-defaults.ini; SHOT=(--shot-at ${SHOT_AT:-3}) ;;
esac
KEYS=${KEYS:-"tileVertexDepth tileStateFold texParamCache pplRemap sunShadowStaticVehicles carGlassNoGet shaderWarmup"}
VIS=(--prop pixelLight=true --prop sunShadows=true --prop sunShadowFar=true --prop foliageSway=true --prop spriteFilter=sharp
  --prop carGlass=true --prop carOccupant=impostor --prop ambientOcclusion=true --prop reflections=true --prop mirrors=true
  --prop godRays=true --prop relief=true --prop colorGrading=true --prop bloodWet=true --prop occludedZombieOutlines=true)
COMMON=(--prop enabled=true --prop instrument=true --prop reflexSleep=false --vmarg "-Dpzopt.userOptionsFile=$EMPTY")
[[ $M != mac ]] && COMMON+=(--launcher direct)   # (run-mac.sh always launches directly and takes no --launcher)
keys() { local v=$1 a=(); for k in $KEYS; do a+=(--prop "$k=$v"); done; printf '%s\n' "${a[@]}"; }
mapfile -t ON < <(keys true); mapfile -t OFF < <(keys false)
first=1
sub() { # label intent args...
  local label=$1 intent=$2; shift 2
  local name=(); [[ $first == 1 ]] && name=(--name "spike-rerun-$M") && first=0
  harness/queue.sh submit run --machine "$M" --install opt "${name[@]}" --intent "300 fps loop changes on the $M: $intent" \
    --progress "$label" --resource gpu,render-thread,game-thread -- --label "$label" "$@" | grep -E '^job' || true
}
# SIDES (env): which picture-pair sides to run (default "off on"); with KEYS (env) = the keys the "on" side switches on
# (the rest of the default list stays off there), e.g. a bisect: KEYS="<all but one>" SIDES=on.
ALL="tileVertexDepth tileStateFold texParamCache pplRemap sunShadowStaticVehicles carGlassNoGet shaderWarmup"
for side in ${SIDES:-off on}; do
  [[ $side == none ]] && continue   # SIDES=none: the drives only
  if [[ $side == on ]]; then K=("${ON[@]}"); for k in $ALL; do [[ " $KEYS " == *" $k "* ]] || K+=(--prop "$k=false"); done; else K=("${OFF[@]}"); fi
  sub "lab-$M-shot-$side-$SUF" "picture pair ($side)" --mode bench --flag start=8147,11507 --flag zoom=1 --flag route=E:0 --flag speed=1 \
    "${SHOT[@]}" ${SHOT_EXTRA:-} --flag hold=12 --flag wind=0 --flag zombies=off --prop overlay=false "${VIS[@]}" --prop foliageSway=false "${COMMON[@]}" "${K[@]}"
done
# N=0: the picture pair only (the player stands still: route E:0 with a hold, so both shots frame the same view)
for i in $(seq 1 "$N"); do
  for side in off on; do
    if [[ $side == on ]]; then K=("${ON[@]}"); else K=("${OFF[@]}"); fi
    sub "lab-$M-drive-$side-$SUF" "Rosewood drive ($side, $i/$N)" --mode drive --flag path=8010,11204.5/8106,11204.5/8106,11600 \
      --flag kmh=120 --flag zoom=max --prop uncappedFps=true --prop vrr=off "${VIS[@]}" "${COMMON[@]}" "${K[@]}"
  done
done
