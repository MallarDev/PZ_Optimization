-- pzopt: luaGcNoop (2026-10-05, the Java memory pass, docs/findings-gc-heap-2026-10-05.md). The game's Lua
-- collectgarbage() ("collect", "step" or no option) is Kahlua's BaseLib.collectgarbage, which calls System.gc(): a
-- stop-the-world Full GC of the whole Java heap on G1, ~270-290 ms of frozen game per call with a 4 GB heap (fixture mod
-- harness/compat/make-gc-fixture.sh); with -XX:+ExplicitGCInvokesConcurrent the pause is short but the calling game
-- thread still waits for the whole concurrent cycle. Lua's memory is the Java heap, which the collector manages on its
-- own, so a mod's request only forces that freeze. While the key luaGcNoop is on (Options > Optimizations, default on)
-- those calls return 0 at once; "count" and every other option go to the game's function. Shared and in the game's own
-- Lua tree, so it is installed before any mod's file runs.

local GAME_COLLECTGARBAGE = collectgarbage

local function enabled()
    local ok, v = pcall(function() return getPerformance():getPzoptOption("luaGcNoop") end)
    return ok and v == "true"
end

if GAME_COLLECTGARBAGE and enabled() then
    collectgarbage = function(opt, ...)
        if opt == nil or opt == "collect" or opt == "step" then
            return 0
        end
        return GAME_COLLECTGARBAGE(opt, ...)
    end
end
