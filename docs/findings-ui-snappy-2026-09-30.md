# UI snappiness pass (2026-09-30)

Maintainer's objective: the UI instant and snappy in every interaction (opening the inventory, transferring items,
switching containers, opening screens, menus and items), no input lag; secondary: the UI under 1 % of a frame. Work in
worktree `../PZ_Optimization-ui`, branch `ui-snappy` (on origin/master 979c840).

## Rigs

- `pzopt.UiProfile` (key `uiProfile`, on in instrumented runs): exact nanoTime of `UIManager.update` every frame (split in
  sections: list upkeep, mouse buttons, click / wheel, mouse move, world pick + OnMouseMove, element updates, tooltip) and
  of `UIManager.render` per UI frame and per top-level element (draw commands, "same as the previous render"), per Lua
  element type the prerender / render / update call time (C lines), per top-level element update time (tick vs not).
  `Zomboid/pzopt-ui.out`; `harness/uiprof.py <run> [--all] [--calls N]` (steady state, per-scene table, interactions).
- Harness UI rig `harness/mod/pzopt-harness/42/media/lua/client/pzopt_harness_ui.lua`:
  - `--flag ui_script=interactions` (reps `ui_reps` 3, `ui_step_ms` 1200): inventory toggle key, container switches,
    transfer one item, item tooltip, item / world context menus, crafting / building windows, health / skills panels,
    map (waits for it: `map_shown`), pause menu; each through the key event or the function the click calls, timed in
    µs (`getPerformance():pzoptMicros()`), marked with `pzoptUiMark` (M lines + the next second's F / R stamps), and the
    input it stands for signalled (`pzoptUiInput`).
  - `--flag ui_script=scenes` (8 s each, `ui_scene_ms`): HUD only, inventory open, mouse sweeping over the inventory
    (`pzoptUiMouse` moves the game's mouse through the showcase aim override), HUD, crafting window, map.
  - `ui_bag_items` (60) / `ui_floor_items` (40) set up a bag and floor loot; `ui_lua_prof=Table,...` wraps every function
    of those tables with the µs clock (refuses ISUIElement / ISPanel / ISPanelJoypad / ISBaseObject / ISButton: wrapping
    them left the game on the main menu).
- Common args (`/tmp/ui-args.sh`): bench save standing still (`route=S:1 speed=0.01`), zoom max, 240 cap
  (`--prop frameCapFps=240 --prop uncappedFps=false`), a defaults options file
  (`--vmarg -Dpzopt.userOptionsFile=/tmp/pzopt-inv-defaults.ini`, the desktop's tab file turns on DLSS / AO / shadows),
  `--option uiRenderOffscreen=true --option uiRenderFPS=60 --flag inventory=open --flag zombies=off`.
- `devUiDiff=Type,...` (D lines: what changed between two renders of an element), `devUiRetainedCheck`, `devMapStreetCheck`.

## Baseline (runs `ui-off`, `sc-off`; 240 cap)

| scene | UI % of frame (update + render) | main costs |
|---|---|---|
| HUD | 3.98 | dashboard 50 us/frame, equipped item 36, watermark 22, element updates 36 |
| inventory open | 9.75 | inventory pages 279 us/frame |
| mouse over inventory | 9.07 | inventory 221, mouse-move handlers 44 |
| crafting window | 16.6 | 557 |
| world map | 39.6 | 1,615 (6.6 ms per UI frame) |

Opening screens: crafting 14 ms handler (33 ms the first time) + 15 ms first render, building 9 + 8 ms, world context menu
2.2 ms, item menu 1.4-1.8 ms, map 1.04 s (the ISReadWorldMap timed action: gameplay, not changed) + a 190-200 ms frame.
UI reaction after an action: waits for the next 60 Hz UI render.

## Changes and measurements

1. **uiRetained** (`pzopt.UiRetained`, UIManager / GameWindow / GameKeyboard / Event hooks): each top-level element's draw
   commands are recorded when its Lua runs and replayed (copied into the UI render state) while nothing can have changed
   it. Fresh on: first render, unreplayable commands (models, generic drawers such as the map, shader starts with frame
   data, FBO switches), any click / wheel / key / controller (all elements), UI Lua events (container update, clothing,
   equip, XP, level up, damage, window containers: all elements), hover in / out and mouse moves over it (60 Hz), capture,
   geometry change, inventory `renderDirty`, the stock rate while its output changed in the last 250 ms / 4 renders, 300 ms
   after input, cheap elements (< 10 us) always; otherwise a timer that doubles to 250 ms while the output stays identical.
   The UI renders at least at the stock rate and in the same frame as input. The command hash covers only the fields a
   command type uses (pooled TextureDraws keep leftovers of their previous use: a quad's a/b/c, a stencil mask's b/c).
   GLState.startFrame() at every element boundary keeps recordings self-contained.
   - Stale-replay check (`devUiRetainedCheck`, interactions): 641 -> 373 -> 94 -> 35 of ~22,000 checked replays while the
     rules were added; the rest are passive changes 50-250 ms late (bounded by uiRetainedMaxMs).
   - UI per frame: HUD 3.98 -> 1.13 %, inventory open 9.75 -> 1.8 %, crafting 16.6 -> 3.1 %, hover 9.07 -> 6.8 %.
   - Latency: every interaction reaches the UI in the same frame (2.0-2.5 ms after its handler, runs `inter-late`) once
     the decision moved to just before the render phase (after the Lua ticks).
2. **mapStreetMemo** (WorldMapStreet / WorldMapStreets overrides, `pzopt.MapStreets`): per street, the UI-space length per
   view stamp and the translated name per language; the visible-street sort by one index pass instead of indexOf in the
   comparator. Map 1,379 -> 817 us/frame (6.36 -> 3.78 ms per UI frame). Decompiler fix in
   `WorldMapStreet.countUpsideDownCharacters` (the jar multiplies by (double)(float)(180/PI)).
3. **mapStreetCache** (StreetRenderData override): the whole street-label layout reused while the view, the combined
   streets, every renderer option, the map style and the language are unchanged. Map 817 -> 294 us/frame (1.43 ms per UI
   frame); `devMapStreetCheck` 300 reuses, 0 differed.
4. **luaIndexCache** (KahluaTableImpl: PZ's rawget walks the metatable chain; class tables cache that walk per key until any
   class table changes) and **luaInternConstants** (LuaCompiler: string constants interned): exact, no measurable change
   (the lookups are direct hits in large tables: memory latency, not chain walks).
5. **luaSkipEmpty** (UIElement override: prerender / render / update calls to a one-instruction Lua function skipped):
   exact, no measurable change. UIElement decompiler fixes: onConsumeMouseWheel, getUIName, isKeyConsumed.

6. **uiRetainedMaxMs 250 -> 1000** (a static element's longest refresh interval): inventory open 76 -> 57 us/frame; with
   the event / input / table-write triggers the stale check stays at 10 of 23,825 replays (0.04 %), oldest 517 ms.
7. **uiLuaFast** (`media/lua/client/pzopt/pzopt_ui_fast.lua`, installed at game start only while the functions are the
   vanilla ones): a hidden inventory window skips its controls row re-arrange (70-100 us of its 110-150 us update tick;
   re-arranged when shown), the item list's dragged pass does only its row count / food aging / scroll height with no
   drag. HUD element updates 29.7 -> ~20 us/frame (HUD 0.98-1.22 -> 0.86-1.14 %).
8. **uiTickStagger** (`pzopt.UiTicks`): every top-level element's 100 ms Lua update on its own phase. Averages equal;
   the worst UI-update frame 711 -> 435 us (inventory), 614 -> 412 (hover), 440 -> 356 (HUD), 1,341 -> 1,136 (crafting).
9. **uiRetainedChildren** + a value-change write counter on every Lua table (`KahluaTableImpl.pzoptWrites`): child
   elements recorded / replayed too, an element whose Lua table changed renders fresh. Map 6.35 -> 4.06 % (the symbol
   buttons), hover 6.79 -> 5.91 %; stale check 21 of 131,003 (0.016 %).
10. Tried and dropped: a crafting-layout memo keyed on the subtree's table writes (the recipe panel's layout writes
    positions back and forth and rebuilds small tables every tick, so the counter always moves; `ISCraftRecipePanel:
    updateTitleWidget` recomputes a ~0.7 ms table layout ten times a second).

11. **The transfer hitch** (items dropped or looted -> the next hot save serialises the map metadata on the game thread
    through `MainThread.invokeOnMainThread`, `devHotsaveTiming`): first hot save of a session metagrid 16.5 ms (grid part
    10.7, zones 4.4, animal zones 0.8; the same code 2-3 ms warm) + entities 1 ms. **hotsaveWarmup** (`pzopt.HotsaveWarmup`,
    GameLoadingState right after IsoWorld.init): the loader thread runs the read-only serialisers three times into a
    private scratch buffer (36 ms of loading screen): the first hot save's metagrid 16.5 -> 5.8 ms (runs `warm-on` /
    `warm-off`). Tried and dropped: **hotsaveParallel** (grid columns and zone records serialised in ranges on a pool and
    concatenated): byte-identical to the sequential output (devHotsaveCheck on the hot and the quit save) but slower
    cold (13.6 vs 10.8 ms: cold copies on every worker, pool start-up).
12. Final same-batch comparison (runs `final-stock` vs `final-scenes`, 240 cap): HUD 3.31 -> 1.27 % (steady HUD
    2.11-2.25 -> 0.87-1.05 %), inventory open 9.43 -> 1.66 %, mouse over the inventory 10.12 -> 6.54 %, crafting
    16.04 -> 2.46 %, map 36.17 -> 3.75 %; the map-open frame 190-200 -> 97 ms; every interaction on screen in the same
    frame (2.2-2.6 ms after its handler); stale check 22 of 125,982; frames of both recordings (`final-rec*`) show the
    same windows, rows, labels and buttons.

13. **mapVisitedFast** (WorldMapVisited override): the map's visited-area texture is rebuilt a row at a time into a
    byte array and bulk-put (stock re-derived the row span per texel through getWidthInCells and did four checked puts
    per texel; the first open after a load rebuilds the whole 4,008 x 4,008 texture). `devMapVisitedCheck`: 16 M texels,
    0 differ. The first map open's UI render 95.9 -> 65.5 ms (the long frame 97 -> 67 ms; runs `visit-stock` /
    `visit-fast`); later opens had no long frame. The "50 ms frame before the map shows" was the rig's 1 s trace ending
    before the map's mark (the ISReadWorldMap action takes 1.04 s): now 2 s. The rest of the first open is first-use work
    (street lookup, the map image zip, the first label layout).

## Where the rest goes (runs `calls-skip`, `luaprof2`, `inter-prof`)

- UIManager.update ~36-45 us/frame: element Lua updates at 10 Hz (inventory pages 110-150 us per call even hidden,
  hotbar ~100 us, character window ~40 us), world pick + OnMouseMove ~6 us.
- Inventory: ISInventoryPane:prerender 365-500 us per render; renderdetails runs twice per render (the dragged-items pass
  walks every item with nothing dragged); mouse moves call onMouseMoveOutside / onMouseMove on the pane every frame and
  onMouseOutButton on every container button (2,160 calls/s).
- Kahlua: 54 % of the UI time is the interpreter (call frames, table lookups), 16 % reflective Java calls, 11 % boxing
  numbers (BoxedStaticValues.toDouble from arithmetic).
- Crafting open: HandcraftLogic.setRecipes -> filterRecipeList -> createCachedRecipeInfo -> CraftRecipeData.canPerform for
  every recipe against the inventory.

## Idea list (status)

done: retained UI, same-frame UI on input, map label memos + cache, Lua index cache, interned constants, empty-call skip.
next: vanilla-identical Lua fast paths for the inventory (skip the no-drag dragged pass, hoist lookups, per-row caches),
mouse-move handler cost, staggered 10 Hz element updates (frame consistency), faster Lua->Java invoker (method handles),
number boxing, crafting recipe checks in parallel / cached between opens, building window open, context menus, map first
frame (WorldMapVisited texture) and map drawer, the transfer hitch (IsoMetaGrid hot save on the game thread), tooltip,
text measurement cache, a Lua-to-JVM compiler for hot UI functions. Rejected: running the Lua UI on another thread (Kahlua
and the UI Lua are single-threaded game state).
