# Light from the torch itself (`torchSource`, 2026-10-03)

Goal (maintainer, 2026-10-03): an Enhancements key that makes the light of a carried flashlight come from the light source
instead of from the player, at virtually no cost; every idea implemented, profiled one by one.

## What stock does

`IsoGameCharacter.TorchInfo.set(IsoPlayer, InventoryItem)` puts every light a player carries (hand torch, lantern, lighter,
the webbing angle-head flashlight, a weapon's light) at the player's own `x, y, z`, pointing along the look vector. The
native lighting (`LightingJNI.updateTorch`) lights squares from there; our per-pixel consumers then guess the lamp's height:
pixelLight 0.55 levels, the torch capsule shadows 1.35 squares, the god-ray airlight 0.45 levels. Two lights a player
carries sit on the same spot: pixelLight merged them and kept the stronger (a lantern in the other hand disappeared under
the torch's cone).

## Design (`pzopt.TorchSource`)

1. **The lens of the drawn model.** The item's model instance is found as the game draws it (`primaryHandModel` /
   `secondaryHandModel`, the attached model whose `attachmentNameSelf` is the item's attached location, a weapon's light
   part among its sub-models) and placed exactly as `AnimatedModel.transformToParent` places it (the parent's bone, the
   parent's attachment, the item's own attachment, the mesh transform, the scale), then mapped to the world as
   `Model.vectorToWorldCoords` maps bones (x negated, rotated by the rendered angle, 1.5 squares and 0.6124 levels a unit).
   No item model has a lens attachment, so the lens is the mesh box's **support point along the beam** from the box
   centre (h(d) = sum |d . a_i| over the box's world half-axes): a hand torch's front end, the angle head's front face, a
   gun light's front; a light without a cone (lantern, lighter, candle) shines from the box centre.
2. **Three positions for three consumers.**
   - the native (per square, what the gameplay sees) gets the lens's x / y in `TorchInfo.x / y`; `z` stays the holder's
     level;
   - the per-pixel consumers (pixelLight's beam, its torch shadow mask march, the capsule torch shadows, the god-ray
     airlight) read the exact lens and its real height (`TorchSource.x / y / height`);
   - the direction stays the look vector (`torchSourceAim=look`) or follows the item's long axis (`item`).
3. **The height without a shader cost.** pixelLight's light table had no free channel; the lamp's height above `la.z`
   rides in the fraction of the kind slot (`pplLc.w = kind + height / 4`, kinds 0 / 1 / 2 and heights up to 1.99
   levels), the shader reads `(a.z + fract(c.w) * 4) * PPL_LEVEL` where it used a select between 0.55 and 0.6: the same
   ALU count, the same registers. Unset heights reproduce the old constants exactly.
4. **Wall clamp** (`torchSourceWallClamp`): the native lights from the square its light stands in; a lens across the
   holder's square edge where the vision test says Blocked / closed door / window (`testVisionAdjacent`) stops 0.005 inside
   the edge (corners: the nearer edge).
5. **Native hold** (`torchSourceHold`, 15 hundredths of a square): while the holder stands still (same x / y / z and look),
   the native keeps the position it was last handed until the lens sways further; an idle pose's hand sway would
   otherwise re-light the torch's squares every frame (pixelLight lattice packs, lighting re-bakes without it).
6. **Render-time re-solve** (`torchSourceFresh`): the native update runs after the frame's render, so stock's light (and the
   first version's lens) was the previous frame's pose; the first per-pixel consumer of a frame re-solves the lens from the
   pose the frame draws. The same frame's native update reuses that solve (same pose: nothing moves between the render and
   `LightingJNI.update`).
7. **Vehicle lights at the drawn car** (`torchSourceVehicles`): `vehicleSmooth` draws a car between two physics steps; its
   headlights were drawn per pixel at the last step. The render-time pass re-places them from the shown transform.
8. **Every carried light on its own.** Lights now stand where their items are, so pixelLight no longer merges a lantern into
   the torch beside it: each is drawn (the native's own behaviour: it lights the brightest per square).
9. **Carrier body shadow** (`torchSourceSelfShadow`, compiled into the chunk composite at start-up): the carrier as a soft
   disc of `torchSourceBodyPct` (22) hundredths of a square between the lamp and the point, penumbra widening with the
   distance behind the body (a lamp ~0.08 squares across): a lantern at your side leaves the other side in your shadow.
   (CapsuleShadow cannot: a carried lamp is always inside the carrier's bounding sphere, which it skips.)
10. **Pitch-aware reach** (`torchSourcePitch`, with `aim=item`): a beam tilted down ends at about lens height / slope (plus
    the spill), Java only.
11. **Cheap solve** (`torchSourceFast`): the fixed part of each chain level (attachments, mesh transform, scale) cached per
    model instance (rebuilt when any input changes: a pooled instance reused for another item); per frame one bone matrix
    a level and one sin / cos for the world mapping (the game's helpers did 4 `vectorToWorldCoords` calls and ~12 trig a
    solve). Checked against the reference path every frame (`devTorchSourceCheck`): 87,000 solves, 0 mismatches, max
    1e-6 squares (the reference path first lost 2 % on the box axes to float steps at x ~ 10,461; fixed by mapping the axes
    about the origin).

## Rigs

- `devTorchSourceView`: the lens (yellow), the beam (orange), the native's position (cyan).
- `devTorchSourceCycle=off,on,hold0,stale,slow,item,noclamp` + `devTorchSourcePeriod`: the variants take turns in one run;
  every frame is accounted to its variant (300 ms after a switch and the first full cycle left out): frame time, game-thread
  CPU, solve time, lattice blocks packed, bakes (with `instrument=true`), strong re-bake marks; a report line per cycle.
- `devTorchSourceCheck`: the fast solve against the reference path.
- Harness `torch=on` takes `torch_item`, `torch_slot` (`primary`, `secondary`, `both`, an attached location with `_` for
  spaces) and `torch_part` lists separated by `/`: e.g. `--flag torch_item=Base.HandTorch/Base.Lantern_HurricaneLit/
  Base.FlashLight_AngleHead --flag torch_slot=primary/secondary/Webbing_Right_Walkie`, or `torch_item=Base.Pistol
  torch_part=Base.GunLight`.

## Results (desktop, RTX 4090, 5120x2160, 240 cap)

Scene unless noted: `--source-save Sandbox/2026-09-30_00-33-03 --flag start=10461,6957 --flag face=270 --flag
time_of_day=1`, player standing (`speed=0.01`). In-run cycles, 3 s a variant, first cycle and 300 ms after each switch left
out; GPU from `harness/torchsrc-split.py` (pzopt-overlay.out's GL timer per frame).

Cost (hand torch):

| run | variant | frame mean / p99 ms | GPU mean ms | solve us / frame | per frame: lattice blocks / bakes |
|---|---|---|---|---|---|
| ts-cost2-ppl | off | 6.369 / 6.463 | 1.507 | 0 | 0.06 / 0.018 |
| | on | 6.370 / 6.521 | 1.509 | 1.86 | 0.04 / 0.014 |
| | hold0 | 6.368 / 6.480 | 1.517 | 1.51 | 0.10 / 0.008 |
| | stale | 6.369 / 6.459 | 1.528 | 1.33 | 0.01 / 0.002 |
| | slow | 6.369 / 6.451 | 1.524 | 2.69 | 0.00 / 0.001 |
| ts-cost2-noppl (instrument) | off / on | 6.369 / 6.37 | 1.557 / 1.527 | 0 / 1.28 | bakes 0 / 0 |
| | hold0 | 6.369 | 1.558 | 1.23 | bakes **0.073** |
| | slow | 6.369 | 1.573 | 2.15 | 0 |
| ts-cost-spin (ppl, turn=90) | on / hold0 / stale | 3.333 (all) | - | 1.4 / 1.2 / 0.9 | 1.65 / 1.80 / 1.73 |

- Frame time and GPU time: no difference between variants (differences of ±0.03 ms GPU are the run's noise; the
  game-thread CPU column of the in-game report drifted down through every cycle's order and is left out).
- The solve: 1.3-1.9 us a frame (two solves: render time + the native update reuses it, so ~1 in practice, `reused=` in the
  stats); the game's own helpers (`slow`) 2.2-2.7 us: the cached chain saves ~0.9 us.
- The hold: without pixelLight, letting the native follow the idle sway (`hold0`) cost 0.073 chunk re-bakes a frame (4.4 a
  second standing still); with the hold 0. With pixelLight it is lattice packs (0.10 -> 0.04 a frame).
- `stale` (no render-time re-solve) saves ~0.5 us and leaves the per-pixel light one frame behind the drawn model.

Vehicle lights (ts-drive1: night 120 km/h path drive, headlights on, pixelLight): the drawn car's headlights were 0.052
squares from the physics step's on average, **0.41 at most**: that is how far the per-pixel beams sat from the car
before. Frame mean 6.374 -> 6.392 ms, p99 8.35 -> 8.37, GPU 3.27 -> 3.23 (off -> on): the drive's noise.

Body shadow (`torchSourceSelfShadow`, ts-lantern / ts-final1-3: lantern in the off hand, pistol with a GunLight): three
passes. 1: a lit ellipse just behind the body (only points past the body's far side were tested) -> the ray's entry into
the disc. 2: the gun light at the hip half-shaded its whole cone: the penumbra was lamp x (L - t) / t, squares wide for a
body right beside its lamp -> lamp x (L - t) / L (similar triangles at the body's plane). 3: the lantern 0.20 from the
body's middle sat inside the 0.22 disc and darkened the whole half-plane towards the body -> the disc is at most 70 % of
the lamp's distance. Final: one soft wedge behind the body away from the lantern, the gun's cone lit; GPU on 1.528 vs
`nobody` (body data zeroed) 1.539 ms: within noise. Cone lights whose carrier stands behind the lens skip the test in Java
(`bodyInCone`). Off by default (a strong change of look; applies at the next launch).

Wall clamp (ts-wall4, Riverside shed, `Sandbox/2026-10-02_10-30-09`, start 11311,6864 facing the wall at x = 11312, lens
pushed 1.2 squares forward with `devTorchSourcePush=120`, hold off, cycle on / noclamp): 7,452 clamps; the native position
(cyan) stops on the wall line with the clamp and lands in the bedroom behind the wall without.

Visuals: the lens markers sit on the torch in the right hand at hip height, the lantern in the left, the webbing
flashlight on the chest, the pistol's light part (ts-vis1, ts-kinds1, ts-final*); the cone's apex and spill disc moved
from the feet to under the hand. With a lantern and a torch carried together, `off` lost the lantern's light (merged into
the torch), `on` draws both. Fast vs reference solve: 87,000 checks, max difference 1e-6 squares.

Found on the way, not changed here: pixelLight draws objects (grass tufts, the halves of ferns) on squares the player
cannot see black inside a carried point light (a lantern at the feet does it as well: ts-lantern `off`); it only showed
now because the lantern used to be merged into the torch.

Defaults: `torchSource` off (an Enhancements key like the rest of the tab; live), `torchSourceAim=look`,
`torchSourceHold=15`, `torchSourceFresh`, `torchSourceWallClamp`, `torchSourceVehicles`, `torchSourcePitch`,
`torchSourceFast` on, `torchSourceSelfShadow` off.

## Harness finding on the way

Every run of this session froze ("Game Paused") a second into the route: on a Steam launch MangoHud is not loaded, run.sh
fell back to pressing Shift+F2 (MangoHud's toggle_logging key) and F2 is the game's own default Pause key
(`keyBinding.lua`). run.sh now skips the key when MangoHud is not mapped in the game process. Runs use `--launcher direct`.
