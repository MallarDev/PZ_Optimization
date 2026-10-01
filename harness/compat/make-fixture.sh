#!/usr/bin/env bash
# Mod compatibility test fixture (2026-10-01, docs/findings-mod-compat-2026-10-01.md): writes the Lua mod
# Zomboid/mods/pzopt-compat-fixture, enabled per run with `run.sh --mod pzopt-compat-fixture`.
#  1. media/lua/client/ISUI/ISInventoryPage.lua = the game's own file (copied from this install, never committed) plus
#     a wrapper of ISInventoryPage:update that counts its calls. A mod that replaces a vanilla file at its path loads in
#     the vanilla slot, before pzopt_ui_fast.lua, so the old "still the function this file saw" check took the mod's
#     update for vanilla and replaced it (the counter froze at 0); now uiLuaFast leaves it to the mod.
#  2. PzoptFixtureHud: a HUD panel that draws a grid plus a number that changes every ~1.5 s from a Lua upvalue (no
#     table write the retained UI could see). With uiRetainedMods=true and devUiRetainedCheck=true its replays go stale
#     between changes; with the default (false) it renders at the stock rate.
#  3. Every 10 s: "[pzopt-fixture] page_updates=N update_origin=<getPzoptLuaOrigin> hud_value=V" in console.txt.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../../scripts/pz-env.sh"
MOD="$ZOMBOID/mods/pzopt-compat-fixture"
rm -rf "$MOD"; mkdir -p "$MOD/42/media/lua/client/ISUI"
cat > "$MOD/42/mod.info" <<'INFO'
name=PZ Optimization compatibility fixture
id=pzopt-compat-fixture
description=Test fixture of harness/compat/make-fixture.sh; not for players.
versionMin=42.0
INFO
cp "$MOD/42/mod.info" "$MOD/mod.info"
{
  cat "$PZ_DIR/media/lua/client/ISUI/ISInventoryPage.lua"
  cat <<'LUA'

-- pzopt-compat-fixture: a mod's change to the replaced vanilla file
PzoptFixture = PzoptFixture or { pageUpdates = 0 }
local pzoptFixtureUpdate = ISInventoryPage.update
function ISInventoryPage:update()
    PzoptFixture.pageUpdates = PzoptFixture.pageUpdates + 1
    return pzoptFixtureUpdate(self)
end
LUA
} > "$MOD/42/media/lua/client/ISUI/ISInventoryPage.lua"
cat > "$MOD/42/media/lua/client/pzopt_compat_fixture.lua" <<'LUA'
require "ISUI/ISPanel"
PzoptFixture = PzoptFixture or { pageUpdates = 0 }
PzoptFixtureHud = ISPanel:derive("PzoptFixtureHud")
local ticks = 0
local function value() return math.floor(ticks / 90) end
function PzoptFixtureHud:render()
    for i = 0, 59 do
        self:drawRect(4 + (i % 12) * 14, 24 + math.floor(i / 12) * 14, 12, 12, 0.6, (i % 3) / 3, 0.4, 0.6)
    end
    self:drawText("fixture " .. tostring(value()), 4, 4, 1, 1, 1, 1, UIFont.Small)
end
Events.OnGameStart.Add(function()
    local hud = PzoptFixtureHud:new(80, 200, 180, 100)
    hud:initialise()
    hud:addToUIManager()
end)
Events.OnTick.Add(function()
    ticks = ticks + 1
    if ticks % 600 == 0 then
        print("[pzopt-fixture] page_updates=" .. tostring(PzoptFixture.pageUpdates)
            .. " update_origin=" .. getPerformance():getPzoptLuaOrigin(ISInventoryPage.update)
            .. " hud_value=" .. tostring(value()))
    end
end)
LUA
echo "fixture mod written: $MOD"
