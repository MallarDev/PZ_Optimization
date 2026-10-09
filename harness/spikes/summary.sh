#!/usr/bin/env bash
# One line per run label (newest run of each): fps, frame tail, frames > 20 ms, machine CPU / GPU, game / render thread.
# Usage: harness/spikes/summary.sh <label>...
cd "$(dirname "$0")/../.."
printf '%-24s %8s %7s %7s %7s %6s %8s %8s %s\n' run fps p99 p99.9 max '>20ms' cpu gpu 'game/render'
for l in "$@"; do
  d=$(ls -d harness/runs/"$l"-2026* 2>/dev/null | grep -E "/$l-[0-9]{8}-" | tail -1); [[ -n $d ]] || { echo "$l: no run"; continue; }
  o=$(python3 harness/analyze.py "$d" 2>/dev/null)
  ov=$(grep -A3 '^overlay:' <<<"$o")
  fps=$(head -1 <<<"$ov" | grep -oE '[0-9.]+ fps mean' | cut -d' ' -f1)
  p99=$(sed -n 2p <<<"$ov" | grep -oE 'p99 [0-9.]+' | cut -d' ' -f2)
  p999=$(sed -n 2p <<<"$ov" | grep -oE 'p99.9 [0-9.]+' | cut -d' ' -f2)
  mx=$(sed -n 2p <<<"$ov" | grep -oE 'max [0-9.]+' | cut -d' ' -f2)
  th=$(sed -n 4p <<<"$ov" | grep -oE '(game|render)_load [0-9]+' | cut -d' ' -f2 | paste -sd/)
  cpu=$(grep -m1 '^machine' <<<"$o" | grep -oE '): cpu_pct [0-9]+' | cut -d' ' -f3)
  gpu=$(grep -m1 '^machine' <<<"$o" | grep -oE 'gpu_pct [0-9]+' | cut -d' ' -f2)
  sp=$(python3 harness/spikes/table.py "$d" 20 | tail -1 | grep -oE '^[0-9]+')
  printf '%-24s %8s %7s %7s %7s %6s %7s%% %7s%% %s\n' "$l" "$fps" "$p99" "$p999" "$mx" "$sp" "$cpu" "$gpu" "$th"
done
