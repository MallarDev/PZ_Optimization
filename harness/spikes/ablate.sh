#!/usr/bin/env bash
# What makes the desktop's tab file slow while moving: the uncapped Rosewood drive of rerun.sh with the tab file, then
# one setting group switched back at a time (--prop wins over the tab file). docs/findings-frame-spikes-2026-10-09.md §6.
# ABL_BASE=goal: the no-* variants on the tab file with reflexSleep / vrrCap off (the 300 fps loop). ABL_BASE=enh: the no-* variants start from the tab file's Visuals keys alone (enh-only.ini) instead of the whole tab file.
# Usage: [ABL_BASE=enh] [ABL_EXTRA="<more run.sh args>"] harness/spikes/ablate.sh <suffix> "<session name>" <variant>...   (variants: see the case below; "list" prints them)
set -euo pipefail
cd "$(dirname "$0")/../.."
SUF=${1:?suffix}; NAME=${2:?session name}; shift 2
W=$HOME/src/pzo-work
U=(--prop uncappedFps=true --prop vrr=off --prop instrument=true --no-dashboard ${ABL_NOMH:+--no-mangohud})
RW=(--mode drive --flag path=8010,11204.5/8106,11204.5/8106,11600 --flag kmh=120 --flag zoom=max --route-seconds 26)
# ABL_ROUTE: rw (the short Rosewood drive above, labels abl-rw-*), hwy (the full drive-120: KY-60 east 1,200 tiles, abl-hw-*),
# rwfull (the full drive-120-south: east 96 + south 761 tiles, abl-rs-*); all at 120 km/h, uncapped
PFX=rw
case ${ABL_ROUTE:-rw} in
  hwy) RW=(--mode drive --flag path=8010,11204.5/9210,11204.5 --flag kmh=120 --flag zoom=max --route-seconds 60); PFX=hw ;;
  rwfull) RW=(--mode drive --flag path=8010,11204.5/8106,11204.5/8106,11965.5 --flag kmh=120 --flag zoom=max --route-seconds 60); PFX=rs ;;
esac
cold() { local d=$W/glcache-$1-$SUF; rm -rf "$d"; mkdir -p "$d"; COLD=(--env "__GL_SHADER_DISK_CACHE_PATH=$d"); }  # an empty driver shader cache of its own
n=0; total=$#
for v in "$@"; do
  n=$((n + 1)); extra=()
  base=(); [[ ${ABL_BASE:-tab} == enh && $v == no-* ]] && base=(--vmarg "-Dpzopt.userOptionsFile=$W/enh-only.ini")
  [[ ${ABL_BASE:-tab} == goal && $v == no-* ]] && base=(--prop reflexSleep=false --prop vrrCap=false)
  case $v in
    tab) ;;                                                     # the tab file as it is
    goal) extra=(--prop reflexSleep=false --prop vrrCap=false) ;;   # the 300 fps loop's target: the tab file, no Reflex sleep, no VRR
    goal-thr) extra=(--prop reflexSleep=false --prop vrrCap=false --env __GL_THREADED_OPTIMIZATIONS=1) ;;
    def) extra=(--vmarg "-Dpzopt.userOptionsFile=$W/empty-options.ini") ;;
    enh) extra=(--vmarg "-Dpzopt.userOptionsFile=$W/enh-only.ini") ;;   # only its Visuals keys
    opt) extra=(--vmarg "-Dpzopt.userOptionsFile=$W/opt-only.ini") ;;   # everything but its Visuals keys
    cold-on) cold on; extra=("${COLD[@]}") ;;   # first launch after an update: every shader compiled anew
    cold-off) cold off; extra=("${COLD[@]}" --prop shaderWarmup=false) ;;
    no-hdr) extra=(--prop hdr=false --prop hdrAuto=false) ;;
    no-hdr-wl) extra=(--prop hdr=false --prop hdrAuto=false --env JAVA_TOOL_OPTIONS=-Dzomboid.wayland=1) ;;   # SDR in the native Wayland window HDR uses
    no-ppl) extra=(--prop pixelLight=false) ;;
    no-relief) extra=(--prop relief=false) ;;
    no-sun) extra=(--prop sunShadows=false) ;;
    no-ao) extra=(--prop ambientOcclusion=false) ;;
    no-godrays) extra=(--prop godRays=false) ;;
    no-sway) extra=(--prop foliageSway=false) ;;
    no-ssr) extra=(--prop reflections=false) ;;
    no-mirrors) extra=(--prop mirrors=false) ;;
    no-carglass) extra=(--prop carGlass=false) ;;
    no-sprite) extra=(--prop spriteFilter=stock) ;;
    no-grade) extra=(--prop colorGrading=false --prop darknessFloorPct=0) ;;
    no-overlay) extra=(--prop overlay=false) ;;
    no-blood) extra=(--prop bloodWet=false) ;;
    no-cloud) extra=(--prop cloudShadows=false) ;;
    no-entity) extra=(--prop entityShadows=false) ;;
    no-chars) extra=(--prop sunShadowCharacters=false) ;;
    no-far) extra=(--prop sunShadowFar=false) ;;
    no-meshes) extra=(--prop sunShadowMeshes=false) ;;
    no-suntrees) extra=(--prop sunShadowTrees=false) ;;
    no-outlines) extra=(--prop occludedZombieOutlines=false) ;;
    feat-*) extra=(--vmarg "-Dpzopt.userOptionsFile=$W/$v.ini") ;;   # the defaults + one Enhancement as the tab file sets it ($W/feat-<name>.ini)
    list) sed -n 's/^    \([a-z-]*\)) .*/\1/p' "$0"; exit 0 ;;
    *) echo "unknown variant $v" >&2; exit 2 ;;
  esac
  harness/queue.sh submit run --name "$NAME" --intent "what slows the tab file while moving ($SUF): Rosewood drive, $v" \
    --progress "$n/$total" --resource frame-pacing,gpu,render-thread,game-thread -- --label "abl-$PFX-$v-$SUF" "${RW[@]}" "${U[@]}" "${base[@]}" "${extra[@]}" ${ABL_EXTRA:-} | grep -E '^job'
done
