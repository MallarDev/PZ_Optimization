#!/usr/bin/env bash
# Side-by-side of the 10-09 runs and a re-run: harness/spikes/compare.sh <suffix of the re-run>
cd "$(dirname "$0")/../.."
SUF=${1:?suffix}
for b in unc-hwy-tab unc-hwy-def unc-rw-tab unc-rw-def unc-spin-tab unc-spin-def; do
  for l in "$b" "$b-$SUF"; do
    d=$(ls -d harness/runs/"$l"-2026* 2>/dev/null | grep -E "/$l-[0-9]{8}-" | tail -1); [[ -n $d ]] || { echo "$l: no run"; continue; }
    o=$(python3 harness/analyze.py "$d" 2>/dev/null)
    fps=$(grep -m1 '^overlay:' <<<"$o" | grep -oE '[0-9.]+ fps mean')
    fr=$(grep -A1 '^overlay:' <<<"$o" | tail -1 | grep -oE 'p99 [0-9.]+ms  p99.9 [0-9.]+ms  max [0-9.]+ms')
    m=$(grep -m1 '^machine' <<<"$o" | grep -oE ': cpu_pct [0-9]+%|gpu_pct [0-9]+%' | sed 's/^: //' | tr '\n' ' ')
    sp=$(python3 harness/spikes/table.py "$d" 20 | tail -1 | cut -d';' -f1)
    printf '%-24s %-16s %-44s %-24s %s\n' "$l" "$fps" "$fr" "$m" "$sp"
  done
done
