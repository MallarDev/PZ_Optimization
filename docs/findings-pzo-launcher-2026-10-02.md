# PZO-Launcher v0.9.9.5 (prop11): what it does, and measured against stock and ours (2026-10-02)

[PZO-Launcher](https://github.com/prop11/PZO-Launcher) at `4bf9134` (2026-10-02 17:16 +0800; version 0.9.9.5,
newer than the latest release V0.9.9.4) built from source with `javac --release 25`. Its README compares itself
with this project. The 2026-09-21 measurement of V0.9.7.8 (stock within noise) is in
`docs/archive/2026-09-24/results.md`.

## How it hooks in (Linux installer, `pzo_optimizer.sh`)

`ProjectZomboid64.json`: `mainClass` → `com/pzoptimizer/PZOEntrypoint`, `PZOptimEngine.jar` on the classpath,
`-agentlib:pzo_native64`, `-Xmx` by RAM (8 GB at 30 GB), G1 IHOP 45 / reserve 15, AlwaysPreTouch, UseSuperWord,
MaxInlineLevel 15, InlineSmallCode 2500, UseNUMA. The installer adds no `-javaagent`: the native library's
`Agent_OnLoad` loads `libinstrument` and runs `PZOptimAgent.premain` from `./PZOptimEngine.jar` itself, so the
bytecode patches run on Linux and Windows. `ZomboidConfigMigrator` rewrites the JSON at boot when a flag is missing.
So "zero file modifications" in its README does not hold (jar + native lib in the game dir, launcher JSON, and
`~/Zomboid/debug-options.ini` overwritten every launch).

## What the code does to the game (42.21)

Bytecode patches (all 8 non-no-op ones apply to the 42.21 jar, checked offline by running its transformer on the
jar's classes and `javap`):

| Patch | Effect |
|---|---|
| `HumanVisual` `skinTexture` -1 → 0 | -1 means "random skin" (`getSkinTexture`); every new character gets skin 0, saved with it |
| `BaseVehicle.addKeyToGloveBox` | building keys never spawn in gloveboxes; on the key-ring branch the ring (with the car key) is never added: key lost |
| `IsoGridSquare.splatBlood` | no wall splat on a square that already has blood, every splat fully opaque |
| `IsoChunk$SanityCheck.log` | body → `return`: the RuntimeException on chunk CRC / length mismatch or load-during-save is gone |
| `ImprovedFogDrawer.render` | prefix `FogQuarterBufferGovernor.render` (own half-res fog shader, depth test off). **The patched class is invalid** when the game uses it: `ClassFormatError: Illegal local variable table length 348` (see "Errors" below), so in practice fog and everything rendered after it are skipped every frame |
| `IsoChunkMap.calculateZExtentsForChunkMap` | loop bound 169 → 13: correct, runs only on chunk-map shifts |
| `FBORenderLevels` bounds, `SpriteConfig` warn | clamp instead of throw outside -32..31; one log line removed |
| `IsoGridSquare.isWallTo(int)`, `TexturePackDevice` | no match on 42.21 (method gone, wrong package): no-ops |

Runtime classes started by `PZOEntrypoint` (about 120; most only log "success"):
- **Gameplay changes:** `VehicleTravelOptimizer` puts zombies 20-50 tiles from the car on every 4th frame and beyond 50 on
  every 8th while driving (car position up to ~500 ms stale); `CorpseAudioGovernor` sets `FliesSound.maxCorpseCount`
  25 → 12, which `BodyDamage` uses as the corpse-sickness cap (20 → 7); `EngineFeaturesTuner` zombie animation falloff
  6 → 4, blended 20 → 16; `PopTemplateGuard` trims skin lists (drops mod skins); `ContainerConfiguratorGuard` gives
  unknown ItemConfig names ids.
- **Real but small runtime changes:** `ChunkIngestionPacer` caps chunk attaches per frame; `WorldStreamerBooster`
  swaps in a single-threaded miniz decompressor; `SaveGameStreamBooster` sets WAL on the save's vehicles.db /
  players.db; `EngineThreadGovernor` wraps `MainThread.mainThreadLoop`; the native lib sets `__GL_YIELD=NOTHING`.
- **Headline features with no effect:** the AVX2 "multi-core horde" (`MultiCoreHordeGovernor`) computes per-zombie
  distances / FOV / repulsion every 250 ms into snapshot arrays only telemetry reads; `MultiCoreAnimationEngine`,
  `ModelSkinningGovernor`, `PlayerLosOptimizer`, `HordeHibernationEngine`, the culler classes: never called;
  `StreamerWake` unparks a streamer that sleeps in `Thread.sleep(140)`; `PersistentVBOGovernor` is used only by its fog.
- **Other risks:** `FileSystemWhitelistShield` adds every drive root to `ZomboidFileSystem.allowedPrefixes` (the Lua
  path check accepts any path); `EngineFeaturesTuner` sets 16 `DebugType`s to Error, which silences `System.out` and
  warnings in console.txt (our `[pzopt] harness:` lines too); `JavaModLoader` runs any jar under `~/Zomboid/mods` /
  Workshop content in premain; the updater installs downloads without a hash check; `PZOEngineBridge` runs its Lua
  from a background thread with the wrong-thread check spoofed. Exceptions are swallowed almost everywhere.

## Measured (desktop, 5120x2160, NVIDIA 615.71, Zulu 25, one run per cell)

Runs `pzo4-<bench>-stock|pzo`, `pzo3-<bench>-opt`, queue jobs 7084-7113, `--launcher direct`, every side with its own
empty options file (`-Dpzopt.userOptionsFile`, stock defaults), `hdr=false upscaler=off vrrCap=false`.
`stock` = `--prop enabled=false`; `pzo` = the same plus PZO exactly as its Linux installer sets it up (wrapper
`~/src/pzo-work/pzo-wrap.sh` via `run.sh --wrap`: JSON edits, jar + lib in the game dir for the run, removed and
`debug-options.ini` restored when the game exits; all 9 "Bytecode-patched" lines in its log); `opt` = our defaults,
uncapped. Stock cannot run uncapped (`uncappedFPS=true` is a 60 fps lock in stock), so stock and pzo run at
stock's highest cap, 244; neither gets near it. The first attempt (`pzo3-*-stock|pzo`) was at that 60 lock and is
discarded. Louisville runs verified: bake counters normal (strongMarks 6.1-6.5k), settle-window frames clean.

| bench | side | fps | frame mean / p99 / p99.9 / max ms | >33 ms | jitter | 1 % low | GPU | game thread | chunk queue wait |
|---|---|---|---|---|---|---|---|---|---|
| spin (S:450 turn=90) | stock | 85.5 | 11.7 / 38.7 / 50.0 / 79.4 | 47 | 5.5 | 26 | 68 % | 97 % | 144 ms |
| | pzo | 78.6 | 12.7 / 41.4 / 63.0 / 83.4 | 67 | 6.2 | 24 | 64 % | 97 % | 160 ms |
| | ours | **331.2** | 3.0 / 10.3 / 18.6 / 29.7 | 0 | 0.9 | 97 | 94 % | 81 % | 7.0 ms |
| 120 km/h storm | stock | 60.1 | 16.6 / 69.3 / 94.2 / 110.2 | 50 | 3.6 | 14 | 83 % | 92 % | 152 ms |
| | pzo | 66.2 | 15.1 / 70.1 / 92.0 / 153.2 | 52 | 3.5 | 14 | 76 % | 81 % | 153 ms |
| | ours | **290.0** | 3.4 / 9.7 / 12.2 / 19.9 | 0 | 1.4 | 103 | 97 % | 59 % | 4.3 ms |
| 120 km/h heavy fog | stock | 87.3 | 11.4 / 21.7 / 26.0 / 46.8 | 1 | 2.2 | 46 | 87 % | 76 % | 157 ms |
| | pzo | 104.4 | 9.6 / 19.5 / 26.2 / 56.6 | 3 | 2.0 | 51 | 78 % | 74 % | 154 ms |
| | ours | **272.6** | 3.7 / 10.0 / 12.6 / 17.7 | 0 | 1.5 | 100 | 97 % | 46 % | 4.7 ms |
| Louisville horde | stock | 27.8 | 35.9 / 97.8 / 175.0 / 193.7 | 311 | 12.3 | 10 | 32 % | 98 % | 142 ms |
| | pzo | 28.7 | 34.8 / 106.3 / 230.9 / 396.0 | 273 | 13.5 | 9 | 33 % | 98 % | 147 ms |
| | ours | **71.0** | 14.1 / 57.0 / 124.0 / 157.8 | 42 | 4.5 | 18 | 34 % | 95 % | 10.1 ms |

Boot (launch → Continue): stock 7.3-7.9 s, pzo 7.2-8.3 s, ours 5.1-6.4 s. PZO's Continue → world-ready is not
measurable here (it silences the console lines `loadtime.py` reads).

- PZO vs stock: within the ~±10 % run-to-run spread on the spin (-8 %), the storm (+10 %) and Louisville (+3 %, worse
  p99.9 / max); +20 % in heavy fog. The storm and fog "gains" are not an optimization: with PZO the fog drawer throws
  every frame and the rest of the world render (fog, rain / weather FX, renderlast, cursor, ...) is skipped (see
  "Errors"). Chunk queue wait unchanged (~150 ms): its streamer "wake" does nothing. Game thread at 97-98 % on the spin
  and Louisville exactly as stock: nothing it runs on other threads takes work off it.
- Ours vs PZO: 4.2x (spin), 4.4x (storm), 2.6x (fog), 2.5x (Louisville), tails 2-7x tighter, no >33 ms frames on
  the drives. Ours is GPU-bound (94-97 %) on the three drive/spin routes; on Louisville nothing is saturated except the
  game thread (95 %), which is the open item.
- Its README's figures are not reproduced: claimed PZO 396 / 264 / 44.8 / 486 fps on storm / fog / Louisville / spin,
  measured 66 / 104 / 29 / 79. The README's "Vanilla" column and its first "PZ_Optimization" column are copied from
  this repo's README (fog 111 / 18.9 → 256 / 8.4, Louisville 23.7 / 94.4 → 31.7 / 56.6, spin stock 114 / 31.4, chunk
  queue 180 ms, boot 7.35 → 5.00 s); its first version listed PZO's storm and fog numbers identical to ours, and commit
  `115408c` ("adjust comparison benchmark figures") lowered our column (31.7 → 28.2, 456 → 398, ...) with no new data.
  The fog / VBO code (`d908972`) was committed 8 minutes before the README with its numbers, after the V0.9.9.4 release.

## Errors in the PZO runs (follow-up, 2026-10-02 23:00)

| run | console `ERROR` lines | of which `IsoWorld.renderInternal> Exception thrown` |
|---|---|---|
| spin stock / pzo | 20 / 3,107 | 0 / 3,084 |
| storm120 stock / pzo | 22 / 157 | 0 / 154 |
| fog120 stock / pzo | 22 / 2,213 | 0 / 2,210 |
| Louisville stock / pzo | 20 / 1,889 | 0 / 1,866 |

PZO also lowers 16 log types to Error-only, so the real count of anything below Error is hidden; these are the
Error-level ones.

- **Every frame, from the first in-game frame:** `java.lang.ClassFormatError: Illegal local variable table length 348
  in method 'void zombie.iso.weather.fog.ImprovedFogDrawer.render()'` at `ImprovedFog.getDrawer` ←
  `FBORenderCell.renderFog` ← `performRenderTiles` ← ... ← `IsoWorld.renderInternal`, which catches and logs it.
  Its fog patch inserts a prefix into `render()` (the class grows by 98 bytes) and the method's LocalVariableTable no
  longer fits the new code (the likely cause; HotSpot rejects the class when it is initialised). Reproduced outside the game on the **stock 42.21 jar** (`~/src/pzo-work/probe/AgentLoad.java`:
  `-javaagent:PZOptimEngine.jar`, initialise + instantiate `ImprovedFogDrawer` → the same ClassFormatError; without
  the agent it loads), so every PZO user on 42.21 has it, not only our installed classes. (Merely loading the class
  without initialising it passes, which is why a plain load test looks fine.)
- Consequence: everything after `renderFog` in the frame is skipped: the object outlines, the tail of
  `renderTilesInternal` (the per-frame shadow / floor list clears, `playerCutawaysDirty`), `FBORenderCell.renderInternal`'s
  `renderlast` pass, chop-tree indicators and `DoBuilding`, and in `IsoWorld.renderInternal` the gizmos, iso cursor,
  vocals and **`renderWeatherFX`** (rain / snow), area highlights. Recorded storm runs `pzo5-storm-rec-stock|pzo`:
  stock draws rain, PZO draws none (`docs/media/pzo-storm-no-rain-stock-vs-pzo.jpg`, 1:1 crops at +32 s). That missing
  work is where its +10 % storm / +20 % fog came from (recorded pair: 29.1 → 32.7 fps).
- 5 one-off load-time `IsoPropertyType ... Property Name not found: WindowShape / ladderN|E|S|W` lines in the PZO spin
  and Louisville runs only (`TilePropertyAliasMap.register`; the game falls back to the plain name). Cause not traced.
- `JavaModLoader` put three jars of mods that are disabled in the mod manager on the classpath (`pzmulticore-agent.jar`,
  and our `pzopt-compat-fixture` mod's two jars); no entrypoint was invoked and none holds `zombie.*` classes, so the
  measurements are not affected.

## Install check (2026-10-02 23:15)

- The runs above used a jar built from PZO's source head (0.9.9.5, unreleased). Its fog patch (`d908972`, 08:38 UTC)
  came after the newest release, V0.9.9.4 (06:59 UTC). The released jar has no `FogQuarterBufferGovernor`, so players
  today have neither the fog `ClassFormatError` nor the missing rain.
- PZO's own `pzo_optimizer.sh` (V0.9.9.4) was run against a sandbox copy of the game dir (fake HOME, pseudo-terminal:
  without a terminal it re-launches itself in Konsole). Its launcher JSON matches the wrapper's except two details, now
  matched in `pzo-wrap.sh`: the jar goes first on the classpath (before `.`), and `-agentlib:pzo_native64` is used, with
  the library copied into the game dir and `natives/` (it was `-agentpath`). The source-head wrapper is kept as
  `pzo-wrap-head.sh`.
- The Workshop half (item 3787481250, last updated 2026-09-11, 33k lifetime subscribers) is byte-identical to the
  local `~/Zomboid/mods/MPOptimizer` (1.4.3). A player's full install = the release engine + this mod.
- Re-run `pzo6-<bench>-full` (V0.9.9.4 + Workshop mod, installer-exact, `--option textureCompression=false` so run.sh
  restores options.ini) against a fresh `pzo6-<bench>-stock`. Load check: native lib loaded (`System.loadLibrary`), 8
  "Bytecode-patched" lines (no fog), mod loaded (it overrides stock shaders), no ClassFormatError; the only exceptions
  are the five `Property Name not found` lines.

Results (same settings as the table above; stock / full at stock's 244 cap, ours uncapped from `pzo3-*-opt`):

| bench | stock (`pzo6`) | PZO full install (`pzo6`) | ours |
|---|---|---|---|
| spin | 86.5 fps, p99 40.2 / p99.9 57.4 ms, 48 > 33 ms | 79.7 fps, 39.1 / 51.2 ms, 48 | 331.2 fps, 10.3 / 18.6 ms, 0 |
| 120 km/h storm | 54.4 fps, 69.4 / 109.8 ms, 66 | **42.8 fps**, 85.6 / 130.8 ms, 118 | 290.0 fps, 9.7 / 12.2 ms, 0 |
| 120 km/h heavy fog | 81.5 fps, 21.8 / 31.7 ms, 2 | 90.2 fps, 19.9 / 25.8 ms, 1 | 272.6 fps, 10.0 / 12.6 ms, 0 |
| Louisville | 26.5 fps, 108.2 / 180.4 ms, 325 | 26.4 fps, 94.3 / 205.3 ms, 324 | 71.0 fps, 57.0 / 124.0 ms, 42 |

- Spin -8 %, Louisville equal, fog +11 % (no fog patch in the release; not traced, inside about one run-to-run spread),
  storm **-21 %** with twice the frames over 33 ms. GPU 65-85 %, game thread at 95-97 % on spin / storm as stock.
- Errors: 23-25 console ERROR lines per run, the stock baseline is 20-22 (the extra are the five property-name lines);
  no ClassFormatError; rain renders in the storm (recording `pzo6-storm120-full`, 1:1 crop at +45 s).
- Louisville runs verified: strongMarks 6.2k / 6.8k, settle-window frames clean.

## Similarities with this project (2026-10-02)

PZO's history: 247 commits from 2026-08-28. Ours: public from 2026-09-15 13:31 UTC. Their history has a gap from
09-17 16:59 to 09-30 07:37 UTC, after which the commit timezone changes from +01:00 to +08:00.

- **Before us, or independent:** updater (08-28), JIT tuner (08-30), power / EcoQoS shielding (08-29), frame-drop
  diagnostics and HUD (08-31), horde animation LOD (08-31), depth-shader uniform caching (08-29), the WorldStreamer
  140 ms sleep bypass (09-06), ChunkIngestionPacer, VehicleTravelOptimizer, the multicore / AVX2 classes (09-01 to
  09-06), ZombieBuddy compatibility, the dedicated-server engine.
- **After us, named after our features:** 29 classes in four commits on 09-30 08:03-10:59 UTC (`09e702f`, `50eb324`,
  `c693622`, `93c2444`), the first 2 h after our `71efcf9` release (uiRetained, uiTickStagger, mapStreetCache), plus fog
  and persistent VBO on 10-02 (`d908972`). Counterparts and our dates: uiRetained (09-30), bakeScheduler / bakeMipLevels /
  fliesToggleFix (09-25), vehicleSmooth / driveLookSmooth / cameraScreenPixels (09-26), zoomRetain (09-22), playerLosFast
  (09-22), vehicleCull (09-22), kidsRoomMemo (09-25), zoneEdgePrefilter (09-23), scriptParserFast (09-19), pngPaethFast
  (09-22), soundTickHz (09-24), animClipCache (09-19), cursorLatch / reflexSleep / vrrCap (09-24), upscaler (09-22),
  foliageSway (09-28), gcMode (09-23), texCompress (09-25), packIndex (09-19), fogPass (09-21), persistentVbo (09-18).
  Most of these classes are not called by anything (see the audit above).
- **Copied code (checked by hand):**
  - `FogQuarterBufferGovernor` shaders: 111 of their 115 shader source lines are identical to `pzopt.FogPass` (09-21),
    including our own vertex layout (`aRect` / `aExtra`, `vDepth = clamp(aExtra.x ...)`), the `fogScale` uniform and
    the composite, not only the stock `fog.frag` noise.
  - `AnimationClipCache.MAGIC = 0x505A4143 // "PZAC"`: identical constant and comment to `pzopt.AnimClipCache`.
  - `TexturePackIndexGovernor`: our `PackIndex` structure (magic "PZPI" → "PZTI", the `endByStart` map, the header
    with size / mtime / build key, `.idx` files).
  - Further matches found by the comparison pass: the PNG Paeth filter (`FastTextureDecompressor` ≈ `PngFilters`), UI
    tick and script-parser lines, `MARGIN_TILES = 8`, `SLOTS = 256`, the `remembered` identity set.
- **Copied text:** "identity ... beside the (tracking) stack" (our Config javadoc, 09-22), "every tile sprite drawn with
  the tile-depth shader" (our UniformCache), "510 ... / 20 blended" (zombieLodDynamic), "526 MB ... keyed by size,
  mtime and the build" (PackIndex), "2,200 .X files through jassimp ... thread-seconds" (README), "+11 %" for G1 on
  few cores (GcChoice), "~80 %" of the map frame (MapStreets).
- **Attribution and licensing:** their only mention of us is the README comparison (`f3b76cb`, 10-02 08:49 UTC), which
  credits "quarter-resolution fog rendering"; no source file or commit names us. Their LICENSE file is GPL-3.0 while the
  README says MIT. This repository has no LICENSE file.

## Maintainer's own install (2026-10-02 23:50): the game does not start

On the cleaned, Steam-verified stock install, PZO V0.9.9.4's `pzo_optimizer.sh` produced a launcher JSON with both
`-XX:+UseZGC` (the stock 42.21 JSON's collector) and its own `-XX:+UseG1GC`; the JVM stops with "Error occurred during
initialization of VM / Multiple garbage collectors selected" before any game or PZO code runs (`projectzomboid.sh.log`
ends at "using jvm"). The Linux branch's filter (`pzo_optimizer.sh:575`) drops `-XX:+UseG1GC` but not `-XX:+UseZGC`;
only the macOS branch's list (`:295`) has it. So every stock Linux Build 42 install should fail the same way; our earlier
runs did not, because the desktop's tuned JSON already used G1. PZO cannot repair it itself (its migrator runs inside the
JVM that fails to start). Fix applied for the maintainer: removed `-XX:+UseZGC` (the written JSON kept as
`/games/pzopt-moved-from-game-20261002/ProjectZomboid64.json.pzo-installer-as-written`); the bundled JVM then starts with
the rest of PZO's flags and loads `-agentlib:pzo_native64`.
