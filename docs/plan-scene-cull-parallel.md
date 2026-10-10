# Experiment: parallel scene-cull classification (2026-10-06)

## Why this seam

After the Louisville 120 fps pass, the remaining ordinary heavy-view frames still grow with per-zombie work.
`IsoWorld.sceneCullZombies` is one of the serial blocks: for every loaded zombie it projects the position for each
player, checks the screen bounds, alpha / visibility and fake-dead state, then builds the with-model / without-model
lists. The relevance sort and model assignment follow it.

The first experiment deliberately moves only the read-only classification. It does **not** parallelise model creation,
list mutation, relevance sorting, `setSceneCulled`, animation blending or any other render-state write.

## Shape

1. Game thread copies the current zombie references into `pzopt.SceneCullBatch`.
2. `FrameBatch` workers evaluate the same visibility predicate into one byte per zombie.
3. `ClimbThroughWindowState` stays serial because `couldSeeHeadSquare` can reach animation bones.
4. Game thread walks the original zombie list in the original order and builds both lists from the answers.
5. Existing `zombieCullSortFast`, dynamic LOD and model-add budget run unchanged.

Key: `sceneCullParallel=true` (default off while experimental).

Exactness rig: `devSceneCullCheck=true` recomputes every worker answer on the game thread. Any mismatch uses the serial
answer for that zombie and disables the batch for later frames. While that rig is enabled, `SceneCullBatch` also logs a
`sceneCull check:` summary about every five seconds, so correctness can be checked during an ordinary play session without
the benchmark harness. The `gt_offload=` line reports the same counters in harness runs.

## Measurement

Use the Louisville 120 scene and an in-run ABBA with:

`devGtAlternate=2000, devGtAlternateKeys=sceneCullParallel`

Read `cull_classify`, `cull_sort`, `cull_commit` and whole game-thread CPU from `pzopt-gtab.out`. First gate:

- zero mismatches with `devSceneCullCheck` over a full route;
- no worker exceptions;
- `cull_classify` at least 30% lower, or >= 0.15 ms removed from heavy (>9 ms) frames.

If it clears that gate, the second experiment can hand the already-read relevance score back with the classification so
`zombieCullSortFast` no longer recomputes it on the game thread. If not, stop here rather than expanding the
thread-safety surface.
