#!/bin/bash
# Controller check of the main-menu update item with a virtual Xbox 360 pad (harness/pad.py, uinput). Run it
# through the queue:  harness/queue.sh submit cmd --install keep --label pad-check -- env OFFER=true harness/pad-check.sh
#  OFFER=true (default): the item is enabled. A activates the pad (the stock focus lands on SOLO), down x7 walks
#    MULTIPLAYER, HOST, OPTIONS, MODS, WORKSHOP, CREDITS, UPDATE; A must open the dialog; down/up scroll the notes;
#    B must close it and leave the cursor on the item (A reopens it, B closes again); down -> MOD COMPATIBILITY CHECK,
#    A opens its dialog, B closes it; down -> QUIT, A -> "Quit to desktop?", A (Yes) exits.
#  OFFER=false: the update item is greyed and has no joypad row: down x7 lands on MOD COMPATIBILITY CHECK (A opens,
#    B closes its dialog), down -> QUIT, A -> "Quit to desktop?", A exits.
# A is never sent while the update dialog is up (A there is "Update now"): the checks stop the game instead.
# Needs python-evdev and write access to /dev/uinput (the `input` group here); the GUID goes into options.ini as a
# `controller=` line for the run (backed up and restored, like run.sh --option).
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OFFER=${OFFER:-true}
OUT=${OUT:-/tmp/pad-check-$OFFER}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
FIFO=/tmp/pzopt-pad.fifo
GUID=030000005e0400008e02000010010000
scripts/pzopt.sh status | tee "$OUT/status.txt" | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
cmp -s src/lua/client/pzopt/pzopt_mainscreen_update.lua "$PZ_DIR/media/lua/client/pzopt/pzopt_mainscreen_update.lua" || { echo "installed pzopt_mainscreen_update.lua differs from src"; exit 1; }

ocr() { python3 $UI read 2>/dev/null; }
shot() { sleep "${2:-1.2}"; spectacle -b -n -f -o "$OUT/$1.png" >/dev/null 2>&1; echo "shot $1"; }
pad() { echo "$1" > "$FIFO"; sleep "${2:-0.6}"; }
GAME_PID=""
finish() {
  local code=$1
  if [[ -n "$GAME_PID" ]]; then
    for i in $(seq 1 20); do kill -0 "$GAME_PID" 2>/dev/null || break; sleep 1; done
    if kill -0 "$GAME_PID" 2>/dev/null; then echo "game still up after 20 s, killing"; pkill -f '^([^ ]*/)?ProjectZomboid64( |$)'; sleep 3; fi
  fi
  echo quit > "$FIFO" 2>/dev/null; sleep 0.5; rm -f "$FIFO"
  cp "$OUT/options.ini.orig" "$ZOMBOID/options.ini"; rm -f "$ZOMBOID/joypads/$GUID.config"
  grep -a "\[pzopt\] update\|LuaError\|Callframe\|attempt to\|ERROR: General\|pzopt_mainscreen_update" "$ZOMBOID/console.txt" | tail -30 > "$OUT/console-tail.txt"
  echo "console: $(wc -l < "$OUT/console-tail.txt") lines of interest (see $OUT/console-tail.txt)"
  echo "done ($code): $(ls $OUT | tr '\n' ' ')"
  exit "$code"
}

# the pad first, so GLFW's startup scan sees it; then the GUID as an active controller in options.ini
# (Core.loadOptions: controller=<guid>), restored on exit like run.sh --option
rm -f "$FIFO"; mkfifo "$FIFO"
python3 harness/pad.py serve "$FIFO" > "$OUT/pad.log" 2>&1 &
sleep 1.5; grep -q "pad ready" "$OUT/pad.log" || { echo "pad failed: $(cat "$OUT/pad.log")"; exit 1; }
cp "$ZOMBOID/options.ini" "$OUT/options.ini.orig"
[[ -z "$(tail -c1 "$ZOMBOID/options.ini")" ]] || echo >> "$ZOMBOID/options.ini"   # the game leaves no newline after the last option
grep -q "^controller=$GUID" "$ZOMBOID/options.ini" || echo "controller=$GUID" >> "$ZOMBOID/options.ini"

( cd "$PZ_DIR/.." && JAVA_TOOL_OPTIONS="-Dzomboid.steam=0 -Dpzopt.devUpdateOffer=$OFFER" exec setsid ./projectzomboid.sh </dev/null >"$OUT/game.log" 2>&1 ) &
sleep 3; GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
for i in $(seq 1 45); do sleep 2; ocr | grep -qi "options" && break; done
sleep 4; shot menu 0

pad a 1.2; shot activated 0.3                       # activation: the stock focus lands on OPTIONS
for i in 1 2 3 4 5 6 7; do pad down 0.5; done; shot after-7-down 0.3   # SOLO -> MULTIPLAYER, HOST, OPTIONS, MODS, WORKSHOP, CREDITS, UPDATE (or COMPATIBILITY CHECK when greyed)
# the compatibility check row: A opens its dialog (title "...mod compatibility check"), B closes it, down -> QUIT
compat_row() {
  pad a 1.2; shot compat-open 0.3
  if ocr | grep -qi "compatibility check"; then echo "PASS: A on the compatibility item opened its dialog"; else echo "FAIL: no compatibility dialog"; finish 2; fi
  pad b 1.2
  if ocr | grep -qi "Checked at this launch"; then echo "FAIL: B did not close the compatibility dialog"; finish 2; else echo "PASS: B closed the compatibility dialog"; fi
  pad down 0.5
}
pad a 1.2; shot after-a 0.3
ocr > "$OUT/after-a-ocr.txt"
if [[ "$OFFER" == "true" ]]; then
  if grep -qi "Later" "$OUT/after-a-ocr.txt"; then echo "PASS: 7 downs + A opened the update dialog"; else echo "FAIL: no update dialog after 7 downs + A"; finish 2; fi
  pad down 0.4; pad down 0.4; pad up 0.4; shot dialog-scrolled 0.3
  pad b 1.2; shot after-b 0.3
  ocr > "$OUT/after-b-ocr.txt"
  if grep -qi "Later" "$OUT/after-b-ocr.txt"; then echo "FAIL: B did not close the dialog"; finish 2; else echo "PASS: B closed the dialog"; fi
  pad a 1.2; shot after-b-a 0.3
  if ocr | grep -qi "Later"; then echo "PASS: the cursor stayed on the item (A reopened the dialog)"; else echo "FAIL: A after B did not reopen the dialog"; finish 2; fi
  pad b 1.2
  if ocr | grep -qi "Later"; then echo "FAIL: second B did not close the dialog"; finish 2; fi
  pad down 0.5; shot on-compat 0.3
  compat_row; shot on-quit 0.3
  pad a 1.2; shot quit-confirm 0.3
  ocr > "$OUT/quit-ocr.txt"
  if grep -qi "Later" "$OUT/quit-ocr.txt"; then echo "FAIL: down from the item did not reach QUIT (update dialog opened)"; pad b 1.0; finish 2; fi
  if grep -qi "desktop" "$OUT/quit-ocr.txt"; then echo "PASS: down from the item reached QUIT (Quit to desktop? is up)"; else echo "FAIL: no quit confirm after down + A"; finish 2; fi
else
  if grep -qi "Later" "$OUT/after-a-ocr.txt"; then echo "FAIL: the greyed item took the focus (update dialog opened)"; pad b 1.0; finish 2; fi
  if grep -qi "compatibility check" "$OUT/after-a-ocr.txt"; then echo "PASS: greyed item skipped, 7 downs + A opened the compatibility dialog"; else echo "FAIL: no compatibility dialog after 7 downs + A"; finish 2; fi
  pad b 1.2
  if ocr | grep -qi "Checked at this launch"; then echo "FAIL: B did not close the compatibility dialog"; finish 2; fi
  pad down 0.5; shot on-quit 0.3
  pad a 1.2
  ocr > "$OUT/quit-ocr.txt"
  if grep -qi "desktop" "$OUT/quit-ocr.txt"; then echo "PASS: down from the compatibility item reached QUIT"; else echo "FAIL: no quit confirm after down + A"; finish 2; fi
fi
pad a 1.0                                            # Yes: the game exits through the controller
finish 0
