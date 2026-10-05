# Louisville 120 plan B: zombie postupdate movement on the workers (2026-10-06, branch `lou120-postupdate`)

Plan: docs/plan-louisville-120-structural.md, part B. Scene: `--bench louisville` capped at 120, `--option
tieredZombieUpdates=true`, `--flag settle=5`, empty options file, plus `entityUpdateParallel`, `zombieSimLodTiles=10`,
`zombieSimLodSteps=4`, `zombieLodDynamic`, `zombieLodMin3d=32`, `slackMaxWaitFrames=600` (`~/.cache/pzopt/rep.sh`'s
COMMON). Desktop (9800X3D, RTX 4090). Key `postupdateParallel` (default off), rig `devPostupdateCheck`.

## Result

- **Exact**: 0 differences in 72,209 movements recomputed by the stock body (`devPostupdateCheck`, one computed zombie in
  seven, runs lp-b2check1 / lp-b2check2), 0 task exceptions in every run.
- **Gain**: postupdate section 0.773 -> 0.711 ms a frame (-0.062 ms; in-run ABBA A/B at 230 ms, lp-b2ab1): the bucket loop
  0.472 -> 0.359 ms, the batch before it +0.066 ms (scan 10 us, vehicle bounds 3 us, the batch 54 us).
- **Whole runs**, 3 + 3 alternating capped runs (lp-rep-*; a1 and a3 discarded: 53 / 63 % machine CPU against 33-39 % for
  the others, something else ran beside them; re-run as a4 / a5):

| config | fps | p99 | p99.9 | > 9 ms | > 10 ms | > 12 ms | > 16.7 ms |
|---|---|---|---|---|---|---|---|
| A, key off (a2, a4, a5) | 113.8 | 20.9 ms | 38.9 ms | 13.9 % | 8.9 % | 4.9 % | 1.78 % |
| B, `postupdateParallel` (b1-b3) | 114.7 | 19.5 ms | 39.3 ms | 13.3 % | 8.1 % | 4.4 % | 1.46 % |

  In the direction of the section gain and inside the run-to-run spread (> 9 ms: A 13.5-14.3 %, B 12.5-14.4 %).
- The plan's gate for B2 (section -50 %, kill under -0.2 ms) is **not met**: the plan sized B from the whole postupdate
  section, but the movement it moves is only ~0.14 ms of it (B0 below). The key stays off; the maintainer decides.

## B0: what the postupdate section is (census, lp-census1, an A/A)

New `pzopt-gtab.out` columns after `cellRender`: `pu_loop` (the bucket loop), `pu_move` (the zombies'
`IsoMovingObject.postupdate`, one call in eight timed, scaled; with the key on also the batch before the loop), `pu_flush`
(ActionEval / animator flush), counts `pu_zombies`, `pu_moved` (square changes), `pu_collided`. `harness/gtab.py` now
takes the section names from the file's header.

| ms a frame | frames <= 8.6 ms | 9-12 ms | > 12 ms |
|---|---|---|---|
| postupdate | 0.64 | 0.87 | 0.98 |
| - bucket loop | 0.38 | 0.52 | 0.61 |
| - of it the zombie movement | 0.14 | 0.18 | 0.19 |
| - flush (transitions, animators) | 0.26 | 0.35 | 0.37 |
| performRenderTiles | 1.61 | 2.33 | 3.48 |
| chunkMapUpdate | 0.04 | 0.60 | 2.28 |
| popmanUpdate | 0.01 | 0.04 | 1.64 |
| lightingUpdate | 0.12 | 0.43 | 0.80 |
| schedUpdate | 1.31 | 1.62 | 2.09 |
| sceneCull | 0.28 | 0.50 | 0.92 |

~228 zombies a frame go through postupdate (tiered updates put the far ones on 1/16 rate), ~5 change square and ~5
collide. The rest of the loop is the ActionEval snapshot of the side-effect callbacks (game thread by design), the animals'
postupdate and animators, `clearAttackVars`. The > 12 ms frames are chunk hand-off / population / lighting bursts that
neither part A nor part B touches: a locked 120 needs a third item for them.

## Design (pzopt.PostupdateBatch)

- Before the bucket loop the eligible zombies of this frame's buckets (alive, on a square, not reused, not in a vehicle,
  not grappled / grappling, no reanimated player, no animation-player swap or ragdoll pending, not climbing) run the stock
  `IsoMovingObject.postupdate` on the frame workers, each from its own saved fields.
- Every place where stock touches shared state or calls out is a hazard: on a worker it throws a stackless bail, the task
  restores the fields and the zombie runs stock inline at its place. Hazards: `collideWith` (special objects, Lua event),
  the fence climb / thump-target checks after a `DoCollide` collision, tree noises / rustle and the foliage push queue,
  virtualisation, a vehicle whose polygon meets the move's bounds (otherwise the vehicle resolution is skipped: with no
  obstacle `CollideWithObstacles.resolveCollision` returns its input).
- `setMovingSquare` is latched and committed at the zombie's place in the loop; the static scratch vector is per thread.
- Bails: 4.8 % of the tasks (per route ~8-13k near a vehicle, 4-6k fence / wall collisions, ~0.5k special objects).
  The first version bailed on any vehicle within 16 squares: 34 % of the tasks downtown, no gain.

## Why it is small

- The movement is ~0.6 us a zombie on the game thread; on a worker it costs about twice that (the zombie's objects are
  cache-cold there), the workers start ~20 us after the dispatch (they park at once, `frameSpinUs=0`), and the game thread
  takes ~30 % of the tasks itself. The batch costs ~55-65 us of the ~140 us it removes from the loop.
- Folding the movement into the entity-update flight (no extra dispatch) runs into `CollisionManager.ResolveContacts`
  (between update and postupdate, it writes the impulses the movement reads) and the world objects processed after the
  scheduler's update; overlapping the batch with the loop itself puts the game thread's loop (Lua collide events, the
  callback snapshots) beside the workers' writes. Neither was worth its risk for the ~0.06 ms left.

## Method notes

- devGtAlternate=1150 with ABBA aliases with the 4 s turn on this route: the census A/A (a key nothing reads) put every
  "on" half +0.3 ms in performRenderTiles and +0.4 ms in renderInternal. At 230 ms the halves balance (wall 8.97 vs 8.99 ms).
  Use ~230 ms periods on the spinning routes.
- Check `harness/queue.sh result <label> | grep ^machine` before using a run: `cpu_pct` well above the game's share means
  something else ran (builds outside the queue are the usual cause; build through `queue.sh submit cmd`).
- Bake counters (strongMarks 5.9-6.2k) and the settle-window frames were normal in every run used here.
