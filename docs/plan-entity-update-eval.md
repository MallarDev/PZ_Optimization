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

## Phase 1 result (2026-10-10)

Passed on the Ryzen 5 5600 live save with the conservative whitelist. The run reached frame ~135k and exercised
10.27M batched entity updates without an UpdateBatch failure latch, worker exception, wrong-thread report or native crash.
Lua replay stayed exactly balanced at 11.149M captured / 11.149M replayed. Lighting deferral was exercised (408 side
effects). The player deliberately triggered ragdoll around frame 100k and then threw a Molotov into a large horde and
enabled a vehicle siren around frame 130k; ballistics/ragdoll deferral remained zero, which is expected with
`entityUpdateSafeStates=true`: burning/ragdolling/combat-near-player zombies are excluded from worker eligibility.

The emitter counters ended `emitterDeferred=651593`, `emitterDrained=651989`. This is a telemetry-counter race, not a
drain mismatch: `emitterDeferred++` is a plain long increment performed concurrently by worker threads and loses
increments, while `emitterDrained++` runs serially on the game thread. Treat drained as authoritative. Do not change
the counter to an atomic in the performance branch because that would add contention to the path being measured.

The generic exception grep also found Firearms translation-format warnings and one Lua `onMouseUp` argument-count error;
none carried an entity-update/wrong-thread/native-crash signature.

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

## Phase 2 result (2026-10-10, 8 frame workers)

The 2300 ms ABBA run produced 41 usable paired on-windows after a 10 s warm-up and 150 ms switch guards.

- all pairs: `schedUpdate` on-off **-0.375 +/- 0.118 ms/frame** (SE);
- high-zombie pairs (>=500 updates/frame): `schedUpdate` **-0.500 +/- 0.111 ms/frame**;
- whole game-thread CPU: no clear win (-0.19 ms/frame in the high-zombie subset, noise ~=0.32 ms);
- wall/frame time: effectively flat (+0.02 ms/frame in the high-zombie subset);
- process CPU increased by about 5 ms/frame in the high-zombie subset, as expected when work moves to workers.

The run also had three extreme `renderInternal` stalls above 35 ms (87.7, 75.2 and 57.4 ms), all in the
entity-update-ON phase. The last one is at shutdown; the first two are gameplay frames. The player reported roughly
3-4 brief black flashes, so worker pressure / render-thread starvation is now a test target rather than enabling the
broader zombie filter.

Fire observations are not currently attributed to entityUpdateParallel. The log shows many
`IsoFireManager.Remove unknown fire, ignoring` messages during chunk unload/reload, including while the A/B phase is
OFF. Build 42 has independent reported fire persistence / Molotov bugs, so a fixed-phase control is required before
calling this a batch regression.

### Phase 2b: worker-count control

Keep the same conservative A/B, but set:

```properties
frameThreads=4
entityUpdateParallel=true
entityUpdateSafeStates=true
entityUpdatePipeline=false
devGtAlternate=2300
devGtAlternateKeys=entityUpdateParallel
```

The Ryzen 5 5600 has six physical cores. Eight FrameBatch workers plus game/render/other engine threads can oversubscribe
the cores; four workers leave a core budget for the game and render threads. Acceptance: retain >=0.20 ms scheduler gain,
remove the >35 ms render stalls / black flashes, and avoid a frame-time-tail regression.

## Phase 3 only if Phase 2 pays

Upstream's September tests found the original broad PR filter safe after `physicsDefer`, `emitterDefer`, Lua replay,
lighting deferral and pathfinding fixes, while `entityUpdateSafeStates=true` gives up a large part of the gain on some
machines. Do not try it until the conservative path pays on this machine. Then A/B `entityUpdateSafeStates=false` as a
separate experiment; keep `entityUpdatePipeline=false` initially because its benefit is machine/scene dependent.
