#!/usr/bin/env bash
# The Mac OpenGL 2.1 vs 4.1 pond walk (2026-10-06): four Mac runs from the maintainer's save, Jev walking round the pond.
set -u
cd "$(dirname "$0")/.."
mkdir -p build/showcase   # the Jev directors' logs
COMMON=(--mode bench --template Sandbox/pzopt-template-pond --flag start=8174,11692 --flag explore=circle --flag director=jev
        --flag circle_center=8179,11692 --flag circle_radius=5 --flag circle_lead=20 --flag circle_spots=0 --flag circle_laps=1 --flag zombies=off
        --flag zoom=1 --flag route=S:1 --flag speed=0.02 --quit-after 70 --prop instrument=true --prop hdr=false --prop overlay=false)
first=1
for spec in "pondmac-legacy-cap:false:cap" "pondmac-core-cap:true:cap" "pondmac-legacy:false:" "pondmac-core:true:"; do
  IFS=: read -r label core cap <<<"$spec"
  extra=(--prop "macGlCore=$core")
  [[ -n "$cap" ]] && extra+=(--prop "devCapture=4,30,60,50")
  python3 harness/explore-director.py --machine mac --log "build/showcase/director-$label.log" --wait 1800 \
      > "build/showcase/director-$label.out" 2>&1 &
  dpid=$!
  opts=(--machine mac --intent "Mac OpenGL 2.1 vs 4.1 clip from the maintainer's pond save, Jev walking round the pond: $label" \
        --progress "mac pond clip: $label" --resource visual-parity,gpu --size 150)
  (( first )) && opts+=(--rebind --install opt)
  first=0
  harness/queue.sh submit run "${opts[@]}" --wait -- --label "$label" "${COMMON[@]}" "${extra[@]}" 2>&1 | grep -E "^status=|run_dir=|route_complete|crashed|overlay:"
  wait $dpid
  echo "$label director: $(tail -1 build/showcase/director-$label.out)"
done
echo ALL DONE
