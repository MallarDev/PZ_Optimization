#!/usr/bin/env bash
# End-to-end check of pzopt.GcChoice (gcMode, 2026-09-23): with the launcher JSON on ZGC, a normal (non-harness) launch of
# the installed game must rewrite it to G1 + the -Dpzopt.gc=g1 marker for the next launch, and the uninstallers' reset_gc
# must put ZGC back; with the heap keys (gcHeap auto = 4096 here / GC_CHECK_HEAP=<MB>, gcHeapFixed, gcPreTouch, 2026-10-05) the -Xmx / -Xms / pre-touch flags
# go in with their marker and reset_gc gives the launcher's own -Xmx back. The JSON is backed up first and restored on exit (shared machine). Run through the queue:
#   harness/queue.sh submit cmd --install opt --label <l> -- harness/gcchoice-check.sh
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/gcchoice-check}; rm -rf "$OUT"; mkdir -p "$OUT"
JSON="$PZ_DIR/ProjectZomboid64.json"
scripts/pzopt.sh status | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
cp "$JSON" "$OUT/launcher.json.orig"
[[ -f "$JSON.pzopt-backup" ]] && cp "$JSON.pzopt-backup" "$OUT/launcher-backup.orig"
GAME_PID=""
cleanup() {
  [[ -n "$GAME_PID" ]] && kill "$GAME_PID" 2>/dev/null; sleep 2
  cp "$OUT/launcher.json.orig" "$JSON"
  if [[ -f "$OUT/launcher-backup.orig" ]]; then cp "$OUT/launcher-backup.orig" "$JSON.pzopt-backup"; else rm -f "$JSON.pzopt-backup"; fi
  echo "launcher JSON restored"
}
trap cleanup EXIT
python3 - "$JSON" <<'PY'
import json,sys
p=sys.argv[1]; j=json.load(open(p))
def z(a): return ["-XX:+UseZGC" if x=="-XX:+UseG1GC" else x for x in a if not x.startswith("-Dpzopt.gc=")]
j["vmArgs"]=z(j["vmArgs"])
for v in j.values():
    if isinstance(v,dict) and "vmArgs" in v: v["vmArgs"]=z(v["vmArgs"])
json.dump(j,open(p,"w"),indent="\t")
PY
grep -q '"-XX:+UseZGC"' "$JSON" || { echo "could not prepare a ZGC launcher JSON"; exit 1; }
( cd "$PZ_DIR/.." && JAVA_TOOL_OPTIONS="-Dzomboid.steam=0 -Dpzopt.updateCheck=false -Dpzopt.gcHeap=${GC_CHECK_HEAP:-auto} -Dpzopt.gcHeapFixed=true -Dpzopt.gcPreTouch=true" exec setsid ./projectzomboid.sh </dev/null >"$OUT/game.log" 2>&1 ) &
sleep 3; GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
for i in $(seq 1 45); do sleep 2; grep -q "gc: running" "$HOME/Zomboid/console.txt" 2>/dev/null && break; done
sleep 2; kill "$GAME_PID" 2>/dev/null; GAME_PID=""; sleep 3
grep -a "gc: running" "$HOME/Zomboid/console.txt" | tail -1
cp "$JSON" "$OUT/launcher.json.after"
FAILS=0
grep -q '"-XX:+UseG1GC"' "$OUT/launcher.json.after" && ! grep -q '"-XX:+UseZGC"' "$OUT/launcher.json.after" && echo "PASS: switched to G1" || { echo "FAIL: not switched"; FAILS=$((FAILS+1)); }
grep -q '"-Dpzopt.gc=g1"' "$OUT/launcher.json.after" && echo "PASS: marker" || { echo "FAIL: no marker"; FAILS=$((FAILS+1)); }
XMX0=$(grep -o '"-Xmx[0-9]*[kKmMgG]"' "$OUT/launcher.json.orig" | tail -1)
WANT=${GC_CHECK_HEAP:-auto}; [[ $WANT == auto ]] && WANT=4096   # auto with this desktop's mod-less default.txt / last save
grep -q "\"-Xmx${WANT}m\"" "$OUT/launcher.json.after" && grep -q "\"-Xms${WANT}m\"" "$OUT/launcher.json.after" && grep -q '"-XX:+AlwaysPreTouch"' "$OUT/launcher.json.after" \
  && grep -q '"-Dpzopt.heap=' "$OUT/launcher.json.after" && echo "PASS: heap flags + marker" || { echo "FAIL: heap flags"; FAILS=$((FAILS+1)); }
eval "$(sed -n '/^reset_gc() {/,/^}/p' scripts/pzopt.sh)"
reset_gc "$JSON"
grep -q '"-XX:+UseZGC"' "$JSON" && ! grep -q 'pzopt.gc' "$JSON" && echo "PASS: reset_gc puts ZGC back" || { echo "FAIL: reset_gc"; FAILS=$((FAILS+1)); }
grep -q "$XMX0" "$JSON" && ! grep -q '"-Xms' "$JSON" && ! grep -q 'pzopt.heap' "$JSON" && ! grep -q "${WANT}m" "$JSON" \
  && echo "PASS: reset_gc puts the launcher's own heap back ($XMX0)" || { echo "FAIL: reset_gc heap"; FAILS=$((FAILS+1)); }
echo "fails=$FAILS"
exit $FAILS
