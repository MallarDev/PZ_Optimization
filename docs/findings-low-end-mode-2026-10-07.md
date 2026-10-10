# Low-end mode: a locked 60 fps on a 4-core laptop, and the Visuals that fit (2026-10-07)

Goal (maintainer): profile every optimization on the Dell to find the combination that holds a **locked 60 fps** on old
hardware, then profile every enhancement and keep the best-looking set that does not break the lock.

Machine: Dell laptop `diego-dell` — Core i5-6300HQ (4 cores / 4 threads), GeForce GTX 960M 4 GB (Optimus, PRIME
offload), 7.8 GB RAM with zram swap (CachyOS, swappiness 150), 1920x1080 at 60.02 Hz, KDE Wayland. The maintainer raised
the 960M's core and memory clocks and pinned it at max performance with LACT before the first run (flat 1097 MHz SM in
every run): GPU readings here are not comparable with older Dell runs, and a stock-clocked 960M has less headroom, so the
Visuals set below keeps a GPU margin (it stays under ~50 % load).

## Rig

- Queue jobs on the Dell from worktree `~/pzopt-wt/lowend` (`~/.cache/pzopt/lowend/dq.sh`): `--launcher direct`, an empty
  tab file (`-Dpzopt.userOptionsFile`), every key pinned with `--prop`, G1 (`--gc g1`: what players run from their second
  launch, GcChoice), `--prop frameCapFps=60 --option frameRate=60`, `--option tieredZombieUpdates=true`.
- Scenes: town walk south through Rosewood at 3 tiles/s at zoom 1 (25 s; 90 s / 150 s for steady state, scored after
  30 / 60 s, when the JIT has settled), the 60 km/h and 120 km/h E-W path drives at the widest zoom (full 80 s), the
  same walk in a thunderstorm and at night with a torch, Louisville (stress only).
- **Lock metric** — `harness/lockstat.py` `rep%`: with vsync on, the share of the display's refreshes that showed the
  previous frame again, from `present.txt` (each frame's on-glass time) and the panel's refresh in `vrr.txt`. A locked 60
  is 0 %. Also: `late%` (frame time > 1.05 x 16.67 ms), `miss%` (> 19.2 ms), frames over 25 / 33 / 50 ms, p99, loads.
- New tools: `harness/lockstat.py` (above), `harness/liveab.py` (per-window table of a `--flag live_set` run, paired
  against the neighbouring base windows), `harness/spike-wall.py --event cpu --below N` (async-profiler CPU recordings,
  late frames vs a baseline), harness flag `options_profile=<button, _ for space>` (presses a preset in game, logs what
  it set, applies it).

## Where the four cores go

Shipped defaults, town walk (51-54 fps, 34-48 % of frames late): game thread 86 % of a core, the game's native **Lighting
Thread 72 %** (its update takes ~50-70 ms whatever `lightFPS` is, it simply runs flat out), render thread 21 %, World
Streamer 5-17 %, FMOD ~25 %, JIT 25 % (C1, the first minutes), machine 90 %. GPU 35-47 %: the GTX 960M is not the wall.

## Performance: what moved the lock

| config (vsync on) | town walk rep% | 60 km/h drive rep% | 120 km/h |
|---|---|---|---|
| stock | 42.3 | 56.8 (vsync off) | — |
| shipped defaults | 13.0 | — | — |
| low-end preset (09-21) | 17.6 (vsync off) | 22.8 (vsync off) | 35.5 (vsync off) |
| + render distance 13 | 5.2-5.5 (steady 3.2-3.5) | 15.8-16.5 (full 10.4) | — |
| + render distance 11 | 3.9 (steady 1.8) | 8.7 / 16.2 | — |
| **+ render distance 9** | 3.4 (steady **1.6**) | **6.4 / 6.8** | 5.0 |

`chunkGridWidth` is the one lever: the native lighting pass, every chunk bake and every arriving chunk's hand-off scale
with the grid's area. 13 halved the lighting thread (70 -> 44 % of a core); 9 also cut what arrives per chunk row crossed
while driving (the late drive frames carry +11 ms of game-thread work over normal ones, all of it chunk arrival: bakes
+3.4 ms, `IsoChunk.doLoadGridsquare` +1.8 ms, new-chunk lighting +1.25 ms). Vanilla's own `IsoChunkMap.CalcChunkWidth`
picks 13 for a 1280x720 window and 9 for a ~960x540 one (and the game ships 13 / 11 / 9 debug presets), so these are
vanilla configurations; 9 still covers a 1080p screen at the widest zoom (plus one chunk). The cost is simulation radius:
zombies, vehicles and world sounds are live 36 tiles around the player instead of 76.

Measured and rejected on top of that (each against its own base, ±3 points run-to-run on 25 s runs):
- G1 stays: Shenandoah (both modes) and generational ZGC cost the CPU this machine lacks (walk 22-39 % late vs 15 %).
- `gcHeap` 2048 / 2560 / 3072 vs auto (3584 here): no change. The machine swaps to zram (280k pages out in 25 s, the GC
  threads 4.9k major faults, G1 young pauses 44-190 ms), but a smaller heap did not change it.
- every frame-worker parallel key off: worse (walk 19 vs 15 %, drive 44 vs 37 %) — they still pay on four cores.
- `jitMode=tiered` (C2): worse early (177 ms spikes), equal walking in steady state, worse driving (13.3 vs 10.4 %).
- `persistentVbo=false`: worse. `threadNice` on the lighting thread, `slackWork` + `lootDefer` + `zombieSpawnSpread`,
  `chunkHandoffSlack`, `bakeArrivalQuota=2`, `bakeFrameBudget=3`, `lightingRebakeBudget=2` + `lightingRebakeMs=500`,
  `soundTickHz=30`, `treesInChunkTexture=false`, `uiRenderFPS` 60 vs 30, `lightFPS` 5 / 15 vs 10: noise.
- `bakeTimeGuardPct=70`: much worse driving (70 % late: the deferred bakes pile up).
- `presentPacing=off`: same repeats, more late frames; no frame limiter (vsync back-pressure only): ~2 points fewer
  repeats, more latency — left at the defaults.
- `instrument=false` and the game-thread sampler off: same numbers (the harness's own sampling is not the cost).
- Stock Display options at their lowest (no skybox, low water / puddles / fog, no puddles, no reflections, no corpse
  shadows, 2D ground items): within noise.
- Louisville (2,000 zombies): 9 fps, game thread 89 %. Out of reach for a 4-core i5; not a lock target.

## Visuals: what fits

Each enhancement alone on the render-distance-11 base (90 s walk, scored after 30 s; base 2.8-3.0 % repeats) and on the
render-distance-9 base (full 60 km/h drive; base 6.4-6.8 %):

| enhancement | walk rep% | drive rep% | GPU on the drive | verdict |
|---|---|---|---|---|
| sharp sprite filtering | 3.3 | 6.2 | 46 % | in |
| colour grading | 2.8 | 7.0 | 43 % | in |
| god rays | 3.3 | 7.5 | 45 % | in |
| remembered places (memory tint) | 2.4 | 7.3 | 45 % | in |
| reflections (water, puddles) | 3.6 | 7.1 | 47 % (render thread 34 %) | out in the set (below) |
| mirrors and windows | 2.9 | 6.7 | 45 % | in |
| foliage sway | 3.1 | 8.8 | 51 % | out (+2 driving) |
| relief | 3.4 | 8.3 | 44 % | out (+2 driving) |
| wet blood | 3.8 | 9.4 | 44 % | out (+2.5 driving) |
| ambient occlusion | 3.2 | 14.4 | 79 % | out (baked into every arriving chunk) |
| per-pixel light | 4.0 | — | 43 % walking | out |
| sun shadows | 5.6 | — | — | out |
| occluded zombie outlines | 5.0 | — | — | out (game thread +6 points) |

The ten that were cheap walking, all on at once, broke the drive (22 % repeats, GPU 78 %): costs add up, so the set started
from the six that were free on both scenes and lost reflections in validation (below). Toggling AO / sun shadows live re-bakes every chunk (a 3.3 s / 0.45 s freeze on this
laptop, once).

## The modes (Options > PZ Optimization, home page presets)

- **Low-end hardware (4 cores or less)**: master switch on, `chunkGridWidth=9`, `workers=1`, `loadWorkers=2`,
  `treeBakeMaxChunksPerSec=24`; Display: vsync on, frame limit 60, lighting 10/s, UI 30/s, texture compression.
- **Low-end hardware + best look at 60**: the same plus sharp sprite filtering, colour grading, god rays, memory tint,
  mirrors and windows (the Visuals master switch on, every other Visuals setting back to off).
- **Low-end hardware + FSR 1.0 upscaling**: the first set plus FSR 1.0 at 67 % (for GPU-bound low-end machines; on this
  laptop the GPU is not the wall).
Profiles can now set Visuals keys (`enhancements` table) and pick stock combos by label (`stockLabels`).

## Final validation

Vsync on, 60 cap, the modes' exact settings pinned. rep% = display refreshes that repeated a frame (0 = locked); walks
scored over their last 90 s, the others after 30 s. Runs `dell-le-F-*`, `dell-le-FL*-*`, `dell-le-Sv-*`.

| scene | stock | Low-end hardware | + best look at 60 (5 Visuals) |
|---|---|---|---|
| town walk, 150 s | 29.9 % (42 fps, p99 72 ms) | **1.3 / 1.3 %** (59.2 fps, p99 26 ms) | 2.1 % |
| night, torch | — | 1.5 / 1.8 % | 1.8 % |
| thunderstorm | — | 3.9 % | 4.1 % |
| 60 km/h drive, full path | 36.8 % (38 fps, p99 73 ms) | 6.3 / 6.8 % (56 fps, p99 32 ms) | 6.7 / 8.3 % |
| 120 km/h drive | — | 5.6 % | 6.7 % (all six) |

Machine load in the locked walk: CPU 59 %, GPU 34 %, game thread 55 % of a core — the headroom that absorbs most bursts.
What still repeats a frame: one ~200 ms frame per minute of walking (below), G1 pauses (44-190 ms young / mixed, the heap
partly in zram), and while driving the chunk-arrival frames.

The six Visuals were free one by one but cost ~2 points together on the drive (render thread 27 -> 34 %), at night one run
of six showed 4.3 % and the trims (without god rays 1.0 %, without memory tint 1.7 %) did not reproduce it; the drive cost
followed reflections (the only one that raised the render thread alone: 34 % in its single run). Without reflections (runs `dell-le-FLnr-*`) the drive's render thread is back at 28 % and every scene is within the
noise of the performance mode, so the shipped look set is five: sharp sprite filtering, colour grading, god rays, memory
tint, mirrors and windows. Water / puddle reflections are the first one to add on a machine with a spare core.

## Open

- One ~210-260 ms frame ~55 s into every walk (same place / game time in every run, not a GC): unidentified.
- What remains on the drive is chunk arrival on the game thread; moving the hand-off / bake preparation off it is the
  next code-level step for 4-core machines.
