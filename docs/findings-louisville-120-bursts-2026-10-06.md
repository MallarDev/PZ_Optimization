# Louisville 120, third item: the burst frames (2026-10-06, worktree `lou120-post`)

Scene: `--bench louisville` capped at 120, empty options file, `--option tieredZombieUpdates=true`, `--flag settle=5`, and
this time **every 2026-10-05 key on** (`slackWork`, `lootDefer`, `zombieSpawnSpread`, `animalLosSnapshot`,
`zombieModelAddBudgetUs=500`) plus `entityUpdateParallel`, `zombieSimLodTiles=10/4`, `zombieLodDynamic`, `zombieLodMin3d=32`,
`postupdateParallel` (`~/.cache/pzopt/rep-l3.sh`). Desktop.

## Where the frames over 12 ms come from

With the 10-05 keys off (plan B's runs) the > 12 ms frames carried chunkMap 2.3 ms, popman 1.6 ms and lighting 0.8 ms on
average. With them on the chunk hand-off and spawn bursts mostly go: 118.2 fps, > 9 ms 10.1 %, > 12 ms 2.0 %, > 16.7 ms 0.4 %
(l3-census1). Its 50 frames over 12 ms, classified one by one from a 2 ms CPU profile (`~/.cache/pzopt/heavyclass.py`), the
GC log and the GPU sections:

| kind | frames | what |
|---|---|---|
| no event | 24 | 12-14 ms of ordinary game-thread work while the camera faces the horde (tile render, update, LOS, cull) |
| GC | 3-4 | G1 young pauses of 20-38 ms (one every ~7 s; ~2 GB live old generation), 3 of the 7 frames over 16.7 ms |
| GPU-bound | ~10 | bake bursts (4 arrivals + 12 overdue levels granted in one frame), swap -> GPU done 11-17 ms |
| chunk unload + zombie reuse | 6 | a chunk row leaving the grid: up to 10 ms of `resetForReuse` plus 1-7 ms of chunk removal |
| spawn / hand-off / loot | ~10 | 2-6 ms each, what the 10-05 keys leave |

## Zombie reuse: `zombieReuseSpread` (new, default off)

`VirtualZombieManager.update` resets every zombie removed in a frame before it goes into the reuse pool; a grid shift
downtown removes dozens: `vzmUpdate` spikes of 11.2 and 10.5 ms (frames of 22 and 28 ms, l3-reuse-false). With the key the
resets wait in a queue and run oldest first under 300 us a frame (l3-reuse-true: 2,238 resets in the route, 21 us each,
backlog up to 317, worst `vzmUpdate` frame 0.6 ms). Intended difference: the reset's random draws come later.

## GC

Single runs (all keys on): G1 default 117.5 fps, p99.9 33 ms, max 39 ms, 6 pauses up to 32 ms; G1 with
`-XX:MaxGCPauseMillis=8` 118.0 fps, p99.9 24 ms, max 30 ms, 16 pauses up to 11 ms; generational Shenandoah 117.3 fps, p99.9 28 ms,
max 35 ms, 428 % vs 361 % of a core. 3 + 3 runs of the 8 ms target with zombieReuseSpread (l3rep): max 40.8 -> 28.1 ms,
p99.9 27.5 -> 23.3 ms, but > 12 ms 2.08 -> 2.72 % and > 9 ms 12.1 -> 13.4 %: shorter pauses come more often and each still
misses a 120 fps frame. A pause target is a trade of hitches for misses (the launcher setting `gcPauseMs`), not a lock.
`run.sh --gc shenandoah` (with `--vmarg -XX:ShenandoahGCMode=generational`) is the rig.

## Chunk unload

Every grid shift's cost is the row removal (`chunk shift` lines: ground scroll, requests, buffers, cell cache and lighting
scroll together < 0.1 ms): 19 shifts in the route, 1-7.5 ms each. Of 148 chunk removals over 0.2 ms (51 ms in all): the
per-square clean-up 32.6 ms (rain, water, puddles, rooms, zones, objects to meta, adjacency, softClear; up to 2.2 ms for a
three-level chunk), the zombie population's removal 13.8 ms (Java: the per-zombie list removals; the native `n_loadChunk` call
costs nothing measurable), vehicles / render frees 3.1 ms. Deferring either is not safe as is: zombies left in a removed chunk
walk off the grid and leave the population, and `ChunkSaveWorker` reads the chunk right after its removal.

## Bakes

All bakes go through the scheduler (11,221 GPU bake sections = the recorded bakes; a grant bakes ~1.65 sections, the upper
half follows). The bursts are frames granting the 4-level arrival quota plus 12 overdue levels: at a 120 cap the adaptive
budget sits at its minimum (2) because frames use > 90 % of the interval, levels go overdue, and the overdue tier takes 12 at
once. `bakeFrameBudgetHard=6`: frames with swap -> GPU done > 6 ms 17 -> 4 a route, but > 12 ms 2.3 % either way
(`bakeSmooth=true` the same): with every key on the GPU bursts no longer decide which frames miss.

## What is left

The misses that remain are game-thread work in the heavy view direction (the 24 "no event" frames, and every class above
sits on top of a 9-12 ms frame): part A's tile recording and the per-zombie work. Tools: section columns `vzmUpdate`,
`chunkPos`, `nativeUnload`, `bake_t0..3`, `bake_offered`; `~/.cache/pzopt/heavydiff.py`, `heavyclass.py`, `frametree.py`,
`gpusec.py`.
