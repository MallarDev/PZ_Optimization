# Occluded zombie outlines: PR #48 reworked to cost next to nothing (2026-10-03)

PR #48 (novakovicdavid) adds a contour on the parts of a seen zombie that scenery hides. As submitted it worked, but
cost the frame several milliseconds with a crowd on screen. The rework keeps the feature, its options, its colour picker
and its eligibility rules, and replaces the renderer: design in `docs/plan-occluded-zombie-outlines.md`.

## Rig

Desktop (RTX 4090, 5120x2160, uncapped), `--mode bench --flag start=8170,11500 --flag time_of_day=12 --flag
weather=clear --flag route=S:1 --flag speed=0.05 --flag zoom=1 --flag crowd=60 --flag crowd_min=3 --flag crowd_max=16
--route-seconds 20 --prop uncappedFps=true --option uiRenderOffscreen=true --launcher direct` and an empty
`-Dpzopt.userOptionsFile` (Rosewood's church lot, 60 idle zombies around the player). Horde: `zoom=2.5 crowd=300
crowd_max=40`. The rework's cost comes from a within-run A/B: `--prop devOutlineAlternate=1000 --prop devOutlineTiming=true`
switches the outlines off and on every second and logs the render thread's frame interval per phase (and the hidden-part
draws per frame, the end pass's render-thread time) every 10 s; both 10 s windows after the crowd spawned are quoted.

## The PR as submitted (runs outl-pr-off / outl-pr-on)

| | fps | mean | p99 | p99.9 |
|---|---|---|---|---|
| outlines off | 725 | 1.4 ms | 2.8 ms | 3.7 ms |
| outlines on | 165 | 6.1 ms | 12.9 ms | 21.2 ms |

+4.7 ms a frame with 60 zombies. Where it went: every eligible zombie replayed into private targets (a clear, the skinned
draw and a full-screen mask pass each), a `glGetInteger` (a full driver sync on NVIDIA's threaded driver) plus a uniform
buffer re-upload around every character draw and VBO flush to toggle the material capture, every world material shader
running an `imageAtomicMin` into an opaque-depth image, windows and translucent tiles forced out of the chunk textures,
a second depth cache in every chunk texture for the plant filter, and a lighting atlas baked beside the corpse atlas.

## The rework, step by step (in-run A/B, frame ms on / off)

| build | hidden-part draws / frame | crowd of 60 | 300-zombie horde |
|---|---|---|---|
| v2: stencil marks + a second `mesh.Draw` per mesh | 120 | 1.503 / 1.420, 1.461 / 1.417 (+0.04..0.08) | |
| v3: the second draw folded into the stock draw (buffers still bound) | 120 / 441 | 1.595 / 1.550, 1.469 / 1.440 (+0.03..0.045) | 3.349 / 3.069, 3.113 / 2.955 (+0.16..0.28) |
| v6: + the occluder prefilter (zombies with nothing in front skip it) | 10 / 328 | 1.411 / 1.349, 1.348 / 1.331 (+0.02..0.06) | 3.108 / 3.026 (+0.08) |

The v6 crowd run as a whole: 744 fps, p99 2.6 ms, p99.9 3.3 ms (the PR's outlines-off run: 725 fps, p99 2.8 ms). The end
pass (atlas quads + contour) takes 8-15 us of render-thread time and ~12 us of GPU time at 5120x2160 with the GPU idling
at 600 MHz in this CPU-bound scene (`--prop gpuSections=true`, section `outline`); it is skipped on frames where no
zombie could be hidden.

## Checks

- `devOutlineView=1` paints the stencil codes: hidden red, seen zombies green, the player blue (run outl-v2-view: the
  zombies behind the church roof and behind a tree red, everything else green).
- Full-resolution capture (outl-v3-shot): amber contours on the zombies behind the church's roof and wall, only where
  hidden, never along the roof's edge.
- `scripts/build.sh` bytecode audit: 0 mismatches; `scripts/test.sh` passes; the PR's Lua colour-picker test
  (`tests/lua/outline-colour.lua`) passes.

## Video

`docs/media/occluded-outlines-off-vs-on.mp4` (poster `.jpg`): run outl-video4, 200 idle zombies round the church lot at
zoom 1, width 2, the setting switching every 2.5 s, in-game 1:1 capture at 10 fps (`harness/stitch-outlines.py`, OFF
frame beside the ON frame at the same moment of the next period). The zombies behind the church's roof and wall and in
the maple's crown keep their contour; the ones in the open are untouched.

## Not covered

DLSS / TAAU with object motion (`upscalerObjectMv`) share the stencil bits: outlines stay off there. Split screen and
the Mac (OpenGL 4.1: no stencil texturing) were not run; the Mac path switches itself off.
