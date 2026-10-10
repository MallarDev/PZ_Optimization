#!/usr/bin/env bash
# The Mac pond walk as the stock game (2026-10-06, maintainer: the pond clip's "before" is the stock game, not the build on
# OpenGL 2.1): capture run + uncaptured numbers run, Jev walking round the pond as in pond-runs.sh. The stock game boots
# slower, so quit-after and the capture are longer than in pond-runs.sh.
set -u
cd "$(dirname "$0")/.."
mkdir -p build/showcase   # the Jev directors' logs
COMMON=(--mode bench --template Sandbox/pzopt-template-pond --flag start=8174,11692 --flag explore=circle --flag director=jev
        --flag circle_center=8179,11692 --flag circle_radius=5 --flag circle_lead=20 --flag circle_spots=0 --flag circle_laps=1 --flag zombies=off
        --flag zoom=1 --flag route=S:1 --flag speed=0.02 --quit-after 110 --prop instrument=true --prop hdr=false --prop overlay=false
        --prop enabled=false --prop macGlCore=false)
first=1
for spec in "pondmac-stock-cap:cap" "pondmac-stock:"; do
  IFS=: read -r label cap <<<"$spec"
  extra=()
  [[ -n "$cap" ]] && extra+=(--prop "devCapture=4,45,60,50")
  python3 harness/explore-director.py --machine mac --log "build/showcase/director-$label.log" --wait 1800 \
      > "build/showcase/director-$label.out" 2>&1 &
  dpid=$!
  opts=(--machine mac --intent "Stock-game pond walk on the Mac (enabled=false, OpenGL 2.1), Jev walking round the pond: $label; the pond clip's before side + numbers" \
        --progress "mac pond stock: $label" --resource visual-parity,gpu --size 190)
  (( first )) && opts+=(--install opt)
  first=0
  harness/queue.sh submit run "${opts[@]}" --wait -- --label "$label" "${COMMON[@]}" "${extra[@]}" 2>&1 | grep -E "^status=|run_dir=|route_complete|crashed|overlay:"
  wait $dpid
  echo "$label director: $(tail -1 build/showcase/director-$label.out)"
done
echo ALL DONE
