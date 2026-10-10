# Entity-update parallel evaluation on MallarDev hardware (2026-10-09)

This branch changes **no entity-update behaviour** from upstream. It adds only `devEntityUpdateLog`, a five-second
console summary of the existing `pzopt.UpdateBatch` counters so a normal save can exercise the off-by-default
`entityUpdateParallel` path without the benchmark harness.

Baseline from the preceding scene-cull A/B on the Ryzen 5 5600:

- ~214 zombie updates / frame;
- `schedUpdate` ~1.42 ms mean, ~1.37 ms median;
- `postupdate` ~1.24 ms mean;
- game-thread CPU ~6.93 ms mean.

That makes the scheduler update a much larger target than the rejected ~20 us scene-cull predicate.

## Phase 1: conservative live-play safety

```properties
updateCheck=false
entityUpdateParallel=true
entityUpdateSafeStates=true
entityUpdatePipeline=false
entityUpdateLuaReplay=true
emitterDefer=true
physicsDefer=true
devEntityUpdateLog=true
```

Play a representative session: walking/driving, dense zombies, combat, shooting if available, enter/leave buildings,
and quit normally. Grep:

```sh
grep 'entity update check:' ~/Zomboid/console.txt | tail -20
grep -E 'entityUpdateParallel:|wrong thread|double free|SIGSEGV|EXCEPTION' ~/Zomboid/console.txt | tail -100
```

The periodic line includes batched entities, Lua captured/replayed, emitter and Bullet deferred/drained counters,
pathfind escapes, skipped torn surface properties, moving-square deferrals and the failure latch. Expected invariants:
captured == replayed; each deferred counter == its drained counter; no `FAILED`; no worker exception.

## Phase 2: performance A/B

After Phase 1 is clean:

```properties
updateCheck=false
entityUpdateParallel=true
entityUpdateSafeStates=true
entityUpdatePipeline=false
devEntityUpdateLog=false
devGtAlternate=2300
devGtAlternateKeys=entityUpdateParallel
```

Use 2300 ms rather than 2000 ms so a repeating 4 s camera turn cannot permanently alias one side with one phase.
Compare `schedUpdate`, game-thread CPU and frame time.

Acceptance gate for this machine: >= 0.20 ms reduction in paired `schedUpdate`, no regression in p99 frame time, and
no safety/compatibility counters out of balance.

## Phase 3 only if Phase 2 pays

Upstream's September tests found the original broad PR filter safe after `physicsDefer`, `emitterDefer`, Lua replay,
lighting deferral and pathfinding fixes, while `entityUpdateSafeStates=true` gives up a large part of the gain on some
machines. Do not try it until the conservative path pays on this machine. Then A/B `entityUpdateSafeStates=false` as a
separate experiment; keep `entityUpdatePipeline=false` initially because its benefit is machine/scene dependent.
