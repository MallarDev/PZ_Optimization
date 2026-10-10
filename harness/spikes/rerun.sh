#!/usr/bin/env bash
# The six uncapped runs of docs/findings-frame-spikes-2026-10-09.md §4 again (Enhancements on = the desktop's tab file,
# off = an empty options file), through the queue. Usage: harness/spikes/rerun.sh <label suffix, e.g. clean> "<session name>"
set -euo pipefail
cd "$(dirname "$0")/../.."
SUF=${1:?suffix}; NAME=${2:?session name}
EMPTY=${PZOPT_EMPTY_OPTIONS:-$HOME/src/pzo-work/empty-options.ini}
[[ -f $EMPTY ]] || : > "$EMPTY"
U=(--prop uncappedFps=true --prop vrr=off --prop instrument=true --no-dashboard)
HWY=(--mode drive --flag path=8010,11204.5/8610,11204.5 --flag kmh=120 --flag zoom=max --route-seconds 25)
RW=(--mode drive --flag path=8010,11204.5/8106,11204.5/8106,11600 --flag kmh=120 --flag zoom=max --route-seconds 26)
SP=(--mode bench --flag route=S:450 --flag turn=90 --flag zoom=max --route-seconds 15)
n=0
sub() { # label intent route-array-name [extra...]
  local label=$1 what=$2 arr=$3; shift 3; local -n R=$arr; n=$((n + 1))
  harness/queue.sh submit run --name "$NAME" --intent "frame-spike re-run after a clean restart ($SUF): $what" \
    --progress "$n/6" --resource frame-pacing,gpu,render-thread,game-thread -- --label "$label-$SUF" "${R[@]}" "${U[@]}" "$@" | grep -E '^job'
}
sub unc-hwy-tab "highway, tab file" HWY
sub unc-hwy-def "highway, defaults" HWY --vmarg "-Dpzopt.userOptionsFile=$EMPTY"
sub unc-rw-tab "Rosewood, tab file" RW
sub unc-rw-def "Rosewood, defaults" RW --vmarg "-Dpzopt.userOptionsFile=$EMPTY"
sub unc-spin-tab "spin, tab file" SP
sub unc-spin-def "spin, defaults" SP --vmarg "-Dpzopt.userOptionsFile=$EMPTY"
