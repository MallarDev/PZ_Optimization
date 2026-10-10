# Scene-cull parallel experiment result (2026-10-09)

Branch: `scene-cull-parallel`. Target: Project Zomboid 42.21, jar `4a0e9546ec`.

## Correctness

The first playtest ran with:

```properties
sceneCullParallel=true
devSceneCullCheck=true
```

The final sampled line reported:

- 26,094 scene-cull frames;
- 6,188,217 zombie classifications (~237 per frame);
- 6,188,217 serial recomputations;
- 0 mismatches;
- 0 worker failures;
- 0 serial ClimbThroughWindow fallbacks in this run.

That is strong evidence that the copied read-only predicate is exact for ordinary play, but it does not establish
performance.

## Performance A/B

Second run:

```properties
sceneCullParallel=true
devSceneCullCheck=false
devGtAlternate=2000
devGtAlternateKeys=sceneCullParallel
```

Machine: Ryzen 5 5600 (6C/12T), RX 6650 XT, Linux, Java 25. The build used 8 `frameThreads`.
Analysis ignored the first 10 s and 150 ms around each 2 s switch. The ABBA-shaped contiguous phase blocks were paired
(on block against the mean of its two neighboring off blocks), 14 usable on blocks.

Direct section result:

| section | paired on - off | SE | direction |
|---|---:|---:|---|
| `cull_classify` mean | **+11.0 us/frame** | 0.7 us | slower |
| `cull_classify` median | **+4.0 us/frame** | 0.3 us | slower |
| `sceneCull` mean | **+14.9 us/frame** | 1.4 us | slower |
| `sceneCull` median | **+8.6 us/frame** | 0.7 us | slower |

After startup, the raw phase means were about 0.032 ms for parallel classification versus 0.021 ms serial. Roughly
10% of parallel frames exceeded 50 us in `cull_classify`, versus under 0.4% of serial frames. Whole-frame/game-thread
differences were inside run noise; the directly instrumented section is enough to reject the change.

## Decision

Do not merge `sceneCullParallel`.

The serial classification is already too cheap (~20 us/frame on this workload) for a blocking `FrameBatch`
dispatch/join to pay for itself. Adding relevance-score calculation to the same batch is unlikely to recover enough:
the complete existing `cull_sort` section is only ~13-15 us/frame, including the primitive sort. Even eliminating all of
its game-thread work would at best put the experiment around break-even before accounting for extra worker reads.

Keep the branch/code only as a reproducible negative result. A future revisit would need a materially larger unit of work
that can be dispatched asynchronously or fused with a batch already in flight, rather than another blocking batch around
`sceneCullZombies`.
