#!/usr/bin/env bash
# The explicit-GC fixture mod (2026-10-05): calls Lua's collectgarbage() every GC_FIXTURE_SECS (3) seconds of play, as
# "memory cleaner" mods do. Kahlua maps collectgarbage() to System.gc(), a stop-the-world Full GC on G1 unless the JVM
# runs with -XX:+ExplicitGCInvokesConcurrent (gcExplicitConcurrent). Written as a Workshop-style item under the steamcmd
# cache that run.sh searches, so `--mod pzopt-gc-fixture` finds it; logs `[pzopt-gcfix] collectgarbage N ms`.
set -eu
SECS=${GC_FIXTURE_SECS:-3}
DIR=${PZOPT_WORKSHOP_EXTRA:-$HOME/.cache/pzopt-workshop/steamapps/workshop/content/108600}/pzopt-gcfix/mods/pzopt-gc-fixture/42
mkdir -p "$DIR/media/lua/client"
cat > "$DIR/mod.info" <<'EOF'
name=pzopt gc fixture
id=pzopt-gc-fixture
description=Test fixture for PZ_Optimization: calls collectgarbage() every few seconds. Not a gameplay mod.
EOF
cat > "$DIR/media/lua/client/pzopt_gcfix.lua" <<EOF
local every = $SECS * 1000
local nextAt = nil
local n = 0
Events.OnTick.Add(function()
    local now = getTimestampMs()
    if nextAt == nil then nextAt = now + every return end
    if now < nextAt then return end
    nextAt = now + every
    n = n + 1
    local t0 = getTimestampMs()
    collectgarbage()
    print("[pzopt-gcfix] collectgarbage " .. n .. " " .. (getTimestampMs() - t0) .. " ms")
end)
EOF
echo "gc fixture: $DIR (every $SECS s)"
