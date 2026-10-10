# Chunk-burst evaluation on Ryzen 5 5600 (2026-10-10)

This branch starts from upstream master and adds one measurement-only key:

```properties
devGtRecord=true
```

It writes the normal `~/Zomboid/pzopt-gtab.out` section rows with a fixed configuration and never flips an
optimization key. This exists because the entity-update live test was clean while continuously ON, but both 2300 ms
ON/OFF runs produced user-visible black flashes; stateful simulation features should not be validated by changing
execution mode every few seconds.

## Baseline evidence

The 4-worker entity-update A/B run (60 fps cap) contained 27,250 recorded frames. After the first 10 s and switch guards:

- wall mean ~20.05 ms in both phases (~49.9 fps equivalent);
- `chunkMapUpdate`: mean 0.53 ms, p99 6.12 ms, p99.9 15.51 ms, max 81.56 ms;
- `vzmUpdate`: mean 0.03 ms, p99.9 2.52 ms, max 28.97 ms;
- `popmanUpdate`: mean 0.05 ms, p99.9 6.14 ms, max 18.28 ms;
- `lightingUpdate`: mean 0.73 ms, p99 4.31 ms, max 12.20 ms.

In the worst 1% wall frames, `chunkMapUpdate` averaged ~6.2 ms and lighting ~3.4 ms. That makes chunk/population
bursts a better tail-latency target than another sub-ms zombie-update microbatch.

## Method

Do not use `devGtAlternate` for this pass. Use fixed runs with `devGtRecord=true` and copy
`~/Zomboid/pzopt-gtab.out` after each run. Use a disposable/copy save: loot/spawn/reuse spreading intentionally changes
when random draws and zombie resets happen.

### Run A: fixed baseline

```properties
updateCheck=false
entityUpdateParallel=false
frameThreads=8

slackWork=false
lootDefer=false
zombieSpawnSpread=false
zombieReuseSpread=false
zombieModelAddBudgetUs=0

devGtRecord=true
devGtAlternate=0
```

Keep the game at the same 60 fps cap. Drive/walk through the same dense route for several minutes, crossing enough chunk
boundaries to produce hand-offs/unloads.

### Run B: bounded burst smoothing

Same route/save copy, with:

```properties
updateCheck=false
entityUpdateParallel=false
frameThreads=8

slackWork=false
lootDefer=true
zombieSpawnSpread=true
zombieReuseSpread=true
zombieModelAddBudgetUs=0

devGtRecord=true
devGtAlternate=0
```

This deliberately leaves `slackWork` off for the first comparison: the current scene averages slower than its 60 fps
cap, so there is little limiter slack to consume. First measure the bounded per-frame queues that work even while the
game is below cap.

Primary gates:

- lower p99/p99.9/max of `chunkMapUpdate`, `vzmUpdate`, `popmanUpdate` and wall time;
- no increase in ordinary-frame median/mean large enough to trade constant slowdown for fewer spikes;
- no visible missing-nearby zombie / loot correctness problem.

If Run B helps, test `slackWork=true` + its default `chunkHandoffSlackWork=true` as a separate Run C. Upstream measured
chunk-map p99 2.8 -> 0.2 ms with slack hand-off + far loot in a near-cap Louisville route, but this Ryzen run has much
less spare limiter time, so that result must not be assumed here.
