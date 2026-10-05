# Louisville horde at a locked 120 fps (2026-10-05, branch `lou120`)

Goal (maintainer): the `louisville` bench (downtown, population max, ~2,200 zombies, slow spinning walk) locked at 120 fps.
Desktop (9800X3D, RTX 4090, 5120x2160, zoom 2.5), optimized build at defaults (empty options file), `--gc g1`,
`--flag settle=5`, `--option tieredZombieUpdates=true` (the game's default; the desktop's options.ini has it false),
capped with `--prop frameCapFps=120`. "Miss" = a presented frame over 9 ms (route window, overlay log).

## Result

| configuration | fps (cap 120) | p99 | p99.9 | frames > 9 ms | > 12 ms |
|---|---|---|---|---|---|
| start of the day, defaults, tiered updates on, capped (l120-cap1 class) | 112-115 | 19-22 ms | 36-46 ms | 14-16 % | 4-5 % |
| end of the pass, 3 runs (l120-r3-b*) | 117.9 | 13.7 ms | 25.6 ms | 11.9 % | 2.2 % |
| same, with the slack forcing rule (l120-sec7) | 118.1 | 13.8 ms | 24.2 ms | 11.0 % | 2.0 % |

Uncapped the same scene went 71 -> ~170 fps over the pass (entityUpdateParallel, tiered updates, LOD, animal snapshot).
Not locked yet: ~11 % of frames land at 9-12 ms, mostly while the turning camera faces the dense side of the horde
(miss rate 2 % to 20 % by the 4 s turn's phase): every per-zombie section grows together (LOS, cull, character draws,
full-rate updates, animation), i.e. workload, not stalls (GC, SMT siblings and render-thread waits were ruled out).

## Defaults in the release

On (exact, no behaviour change): `profilerIdleFast`, `statsNoBox`, `stateMachineNoIter`, `worldgenPatternCache`.
Off until the maintainer decides (each changes timing or ordering slightly; the numbers above used them on):
`animalLosSnapshot`, `lootDefer`, `zombieSpawnSpread`, `slackWork` (with `chunkHandoffSlackWork`), `zombieModelAddBudgetUs`
(500 when on), plus the existing keys the runs set: `entityUpdateParallel=true`, `zombieSimLodTiles=10`,
`zombieSimLodSteps=4`, `zombieLodDynamic=true`, `zombieLodMin3d=32`, `slackMaxWaitFrames=600` (now the default).

## What moved the numbers

- **The game's own tiered zombie updates** (`tieredZombieUpdates`, stock default true; the desktop's options.ini had it
  false): 71 -> 135 fps uncapped. Off-screen zombies drop to 1/16 rate.
- **entityUpdateParallel** (existing key, off by default): -2 to -4 ms a frame (in-run A/B, two runs).
- **zombieSimLodTiles=10, zombieSimLodSteps=4** (existing experiment key, stock's time-compensated buckets past 10 / 20 /
  40 / 80 tiles): -0.4 ms (scheduler + postupdate sections).
- **animalLosSnapshot** (new): animal LOS 0.80 -> 0.25 ms a frame (section timer, exact A/B).
- **zombieLodDynamic** with target 120 and `zombieLodMin3d=32`: the GPU-bound horde seconds (6 ms of 3D zombie draws)
  went; uncapped 141 -> 172 fps.
- **slackWork + chunkHandoffSlackWork + lootDefer**: chunk hand-offs (1-50 ms each downtown) and far loot rolls run in
  the frame's slack; chunk-map p99 2.8 -> 0.2 ms. With **zombieModelAddBudgetUs** the frames over 12 ms halved in
  repeated runs (2.4 vs 4.7 %).
- Exact trims: profilerIdleFast, statsNoBox, stateMachineNoIter, worldgenPatternCache, the sampled dev census.
  Allocation 120 -> 73 MB/s.

## Measured and rejected

- ZGC instead of G1: 119 vs 142 fps uncapped, more misses.
- G1 short-pause tuning (MaxGCPauseMillis=4, mixed count 16): pauses <= 11 ms instead of 29, more of them, misses the same.
- coreIsolate=2 (game / render on reserved physical cores): no change.
- bakeTimeGuardPct=55: 118 -> 88 fps (deferred levels cost more than the bakes).
- bakeFrameBudget 4 / bakeArrivalQuota 1: no better than the defaults 8 / 4.
- Spawns in the slack: one crash (zero direction vector in createZombieOutsideWorld); spawns stay in-frame.

## Method notes

- `devGtAlternate` A/Bs on this route were biased: an A/A placebo (a key nothing reads) read 0.6 ms / 7 points better
  "on" with plain alternation (every on half precedes its off half on a route that gets heavier). ABBA ordering
  (`devGtAbba`) removes the trend, but a 25 s run still carries +-5 points of miss-rate noise: whole-frame decisions need
  3+3 alternating whole runs (`~/.cache/pzopt/rep.sh` style); section timers are the reliable per-change metric.
- Section timers now cover logic / render / FinishAnimation and their main parts (`pzopt-gtab.out`, 19 columns).
- The desktop's `~/Zomboid/Lua/layout.ini` gained `charinfowindow visible=true` during the evening (another session or
  the maintainer): the open character panel costs ~0.55 ms of Lua UI a frame in every later run.

## What is left for a full lock

The remaining misses are the heavy view directions' extra per-zombie work. The candidates are structural: recording the
tile / translucent / character draw lists on the frame workers (parallel command lists; the translucent pass alone is ~1 ms
a frame), moving the postupdate's movement / collision onto the workers like entityUpdateParallel does for the update,
and per-zombie render costs (renderlast walk, model texture creation on promotion).
