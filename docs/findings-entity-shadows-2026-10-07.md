# Shadows on entities (2026-10-07)

Goal (maintainer): make shadows affect the player, zombies, animals and cars; cost virtually nothing; try the state-of-the-art
techniques one by one and profile each. Key `entityShadows` (with `sunShadows`), class `pzopt.EntityShadow`, worktree
`~/pzopt-wt/entshadow`, runs `es-*` (desktop, RTX 4090, 5120x2160).

Before: a character's whole body took one shade (the CPU march at its chest, `SunShadow.characterFactor`, eased into its
ambient over ~10 frames); cars and atlas zombies took none; nothing shaded an entity but the static world.

## What an entity receives now

| Source | How | Default |
| --- | --- | --- |
| Static world (walls, roofs, floors above, trees) | per-entity probe brick traced through the god rays' occupancy grid, one trilinear tap per vertex (people) / pixel (cars) | on |
| Moving casters (cars, other characters, animals) | their capsules in the model shaders (Quilez soft capsule shadow), up to 4 nearest a receiver | on, 16 nearest entities |
| Itself (arm on chest, hat brim, cabin on hood) | its tile of the characters' sun depth atlas (`ShadowAtlas`, last frame's pose), 2x2 compare | on, zoom <= 1.25, 16 nearest |
| The sun's direction | half-Lambert facing on the sun's share of the light, mean kept (`entityShadowFormPct` 50) | on |
| Clouds | the cloud field's transmittance (`CloudShadow`'s world mapping, two taps of its field, bindless) at every vertex of a person / pixel of a car, along the sun | with cloudShadows, `entityShadowClouds` |
| Atlas (far / horde) zombies | the CPU march, one value a body, on the sprite's colour | on |
| Bodies and cars next to it (ambient) | capsule ambient occlusion: up to 3 capsules within 1.25 squares, a cylinder's cosine-weighted share on the ambient | on, `entityShadowAoPct` 60 |
| Torches and headlights (any hour, indoors too) | the capsules between a character and the torch that lights it most, as the ground's torch shadows (`CapsuleShadow`: PixelLight's torch share x the darkness) on the lit colour | on, `entityShadowTorches` |

The shade multiplies the ambient only: `1 - s + s * V * facing`, s the sun shadows' strength (`sunShadowStrengthPct`, the same
the ground uses), so an entity in shade matches the ground around it. Indoors, at night without a moon and for draws from
other cameras (mirrors, car occupants) the old per-body value applies; the sun atlas's depth draws skip it.

## Techniques, in the order tried

1. **Per-pixel DDA through the occupancy grid** (`entityShadowMethod=pixel`): exact, sharp, but +384 us (40 zombies) to
   +1951 us GPU in the moving objects pass. Kept as the reference and a dev variant.
2. **Per-entity probe volume** (light probe proxy volume / volumetric lightmap per-object sampling): 8x8x8 probes a person,
   16x16x8 a car, computed by a compute pass, cached; one trilinear tap. Same picture as the reference, ~0 GPU.
3. **Shared world bricks**: a person's brick is the square it stands in plus 3/8 square round, a car's the 2x2 squares plus 3;
   entities in one square share it; a walking one needs a new trace only when it changes square (static traces: 2,208 in
   ~2,700 frames of a crowd, 24 in 2,000 frames of a pinned one).
4. **Block stats + uniform mode**: each 8^3 block's min / max visibility (shared-memory reduction in the compute) read back
   through a persistent map a frame or two later (no sync); a brick all in the sun or all in the shade draws with its one
   value, no lookups. Tried first in the fragment (SSBO reads: dearer than the tap) and the vertex (UBO broadcast).
5. **Moving casters**: (a) a compute pass per frame into a second atlas (static x capsules): ~21 us a dispatch whatever its
   work (`dyncs`, kept as a variant); (b) the capsules in the model shaders, one uniform array a draw (default); an exact
   CPU ray-vs-capsule selection keeps the 4 nearest that can reach the receiver.
6. **Self-shadowing** from the existing per-object sun shadow maps (`ShadowAtlas`), bindless; faded out where the surface
   turns from the light (the depth map's terminator stair-steps; the facing term owns that side).
7. **Sun facing** (form shading), **cloud** transmittance, **impostors**.
8. **Per-vertex vs per-pixel**: people's meshes are dense, so the probe tap per vertex (half the cost) loses nothing; cars per
   pixel (their big triangles would smear a shadow's edge).
9. **Off the game thread**: the frame's gather (bricks, casters) runs on a worker from the start of the world render and is
   joined before the moving objects draw: 24 -> 2.4 us a frame on the game thread. Receivers come from the objects the render
   thread drew as models (queued by `bind`), casters from the squares along each receiver's way to the light: a Louisville
   horde's gather 280 -> 63-85 us of the worker.
10. **Detail level**: casters and self-shadows on the 16 entities nearest the camera; the rest keep the static shade and the
    facing.
11. **Clouds at the vertex / pixel** (`entityShadowClouds`): one cloud value an entity left a 5-square car all in or all out of
    a cloud's soft edge (field texel 2.5 squares, detail 0.6). The model shaders now read `CloudShadow`'s field through its
    bindless handle with the same world mapping as the ground (`entityMapping`: 1 / period, parallax per level, drift wrapped
    to a period for float precision), so a car straddles an edge and a body's head and feet take the cloud along the sun.
    Check (es-cloudv2, dev view 12 at the entity's floor against the CPU value): median difference -3 / 255, p10..p90
    -11..+3. Cost (es-cloud1, cover 0.5, paired against one value an entity): GPU -1.5 us (IQR -15..+7), render thread -0.2 us.
12. **The render thread's binds** (es-dedup1, es-memo1/2): the +26 us of render thread was not the GL calls (skipping the 1.2 M
    of 1.7 M uploads whose values the program already held saved nothing; leaving out the reset after a draw saved 2.4 us,
    not worth the `VertexBufferObject` override it needs to keep other paths' draws of these programs unshaded) but the Java
    of each bind, repeated for every mesh of an entity (body, clothes, a car's parts). A per-program memo (same entity, frame
    and camera as the last full bind: only the reset A goes back) halves it: 30.8 -> 14.3 us (875 k of 1.75 M binds).
    The moving pass's GPU timer follows the render thread's submission (`cpu`, no shader work, reads +18 us there): the
    render thread is the lever.
13. **Torch and headlight shadows** (`entityShadowTorches`, es-torch1..4, es-torchcost1): a character standing in another's
    torch shadow on the ground was lit as if nothing stood between. First try: the capsules on the torch's own model light
    term (the light matched by its square); invisible, the native's per-square light already holds the torch in the ambient
    and the stock shader clamps the lighting at 1. Kept: CapsuleShadow's ground model on the clamped lighting, `1 - w (1 - V)`,
    w = the darkness x `sunShadowTorchPct` x PixelLight's fitted share of the torch at the receiver (the torch that gives it
    most), V the capsules between it and the lamp (the lens and height the ground pass takes; the holder and the receiver
    excluded), chosen on the gather worker through a 4-square grid of the drawn models' capsules. Dev view 13 shows the pick
    and depth. Cost at night (40 pinned zombies, the player's torch, paired): GPU +0.0 us (IQR -0.1..+1.0), render thread
    +4.8 us, frame +6.9 us (IQR -19..+31) of 2.16 ms; worker 8 us.
    Found on the way: with the sun off (night) bind kept queueing the drawn objects for a gather that never ran (the queue grew
    by the drawn models every frame); they are queued only on frames a gather runs.
14. **Capsule ambient occlusion** (`entityShadowAoPct`, Unreal's capsule indirect shadows / The Last of Us; es-ao1,
    es-aocost1): in a packed crowd every body lit its neighbour's facing side with the full ambient. The gather worker takes
    the up to 3 capsules within 1.25 squares of each drawn character (the torches' grid), the fragment shader takes an
    infinite cylinder's cosine-weighted share at the closest point of each axis, (r / l) / 2 x the facing, faded to the
    reach, off the ambient (the sun's shade included). Dev view 14. Cost (40 zombies 1-4 squares apart, paired against
    `noao`): frame +13.4 us (IQR -13..+45) of 2.6 ms, render thread +8.1 us (a second uniform array a draw and its reset),
    worker 22.5 us.

## Cost (default, desktop, uncapped, paired 250 ms alternation against off)

| Scene | Frame | GPU busy | Render thread | Game thread |
| --- | --- | --- | --- | --- |
| 40 pinned zombies + 3 cars across the church's shadow edge, zoom 1 (es-cost29) | +42 us of 2.94 ms (IQR +26..+68) | +27 us | +27 us | 2.4 us |
| 120 km/h drive, morning sun (es-drive1) | noise (IQR +-200 us) | moving pass +1 us | +1.4 us | 1.5 us |
| Louisville horde, 60 fps cap (es-lou1) | ~0 | moving pass +2 us | +3 us | 5-10 us |
| The pinned crowd again with the bind memo (es-memo2 / es-cost30) | +14 / +29 us of 2.9-3.1 ms (IQR crosses 0 / +2..+57) | - | +14.5 / +15.4 us | 1.9 us |
| Night, the player's torch on the pinned crowd, torch shadows only (es-torchcost1) | +6.9 us of 2.16 ms (IQR -19..+31) | +0 | +4.8 us | (worker 8 us) |

The render thread's share is two GL calls a model draw (the packed uniform array and the reset after the draw); the reset is
needed because the game draws the same programs on other paths inside the moving pass (DeadBodyAtlas, world item models).

## Lessons (also in memory)

- The moving pass's GPU section time follows the draws' submission, not the shaders' ALU: a pipeline statistics query showed
  94k vertex and 405k fragment invocations a frame for the crowd, far too few for the tens of us first blamed on them.
  Compare whole-frame times (overlay) and GPU busy, paired by cycle (`harness/entshadow/paired.py`, `frametime.py`), on a
  pinned scene (`pin_zombies=`), not a random crowd.
- Something resets texture unit 40 between model draws in busy frames: bind per draw, or bindless.
- An unbound program's uniform reads 0: 0 must mean "no shade".
- The game's `ShaderBufferData` (every program on GL 4.3) has no entry for `sampler2DShadow`: one made the game fail at
  start. Use `sampler2D` + a manual compare (the shader test now refuses shadow samplers).
- `GL.getCapabilities()` on the game thread throws (no context).
- An absolute `FloatBuffer.put` is bounds-checked against the current limit.
- GodRays' roof rule (lower half of a roof square) lets a low sun through pitched eaves; entities take the whole level.
- Dev tint views lie (game lights + clamp): write `gl_FragColor` raw (`devEntityShadowView` 9-11).

## Invisible models on AMD / Windows (2026-10-08 hotfix)

Players on AMD Windows drivers (RX 9070 XT and others) reported invisible characters, zombies, cars, dropped items and
guns with eceb5cf2. Going back to dbee0c2f fixed it. The patch went into every model program whenever GL 4.3 was present,
sun shadows on or off. Its bindless samplers were `layout(bindless_sampler)` sampler uniforms (`pzEsVol` / `pzEsDyn`
sampler3D, `pzEsAtlas` / `pzEsCloud` sampler2D). Such a uniform holds 0 until its handle is set, which counts as
texture unit 0. The game's `ShaderProgram.compile` calls `glValidateProgram` right after the link, with the game's own
sampler2Ds on unit 0 too. A driver that counts bindless samplers in the rule "samplers of two types may not share a unit"
fails the validation, and the game deletes the program: nothing drawn with it shows. NVIDIA does not check the rule and
Mesa leaves bindless samplers out of it, so neither the desktop nor the flip showed it. This is the same rule as the god
rays' black world on the flip (2026-09-27). Not reproduced here (no AMD Windows machine): the cause is inferred. The
fix covers both this cause and the sampler-order one below.

- The bindless shaders now build every sampler from its handle's two halves in plain `int` uniforms
  (`sampler3D(uvec2(uint(pzEsVolLo), uint(pzEsVolHi)))`): no sampler uniform of ours is left in a game program on the
  bindless path. Not `uvec2`: the game's `ShaderBufferData` builds an entry for every active uniform and has none for
  unsigned types; the first try with `uvec2` made the game fail at start (the same trap as `sampler2DShadow`). That also removes
  the other suspect, the game's post-link renumbering of sampler2Ds in the driver's order: ours are no longer
  sampler2D uniforms.
- The model shaders stay stock when sun shadows are off at launch ("entity shadows: sun shadows off at launch, the model
  shaders stay stock"). Turned on during a game, the shade per pixel comes at the next launch.
- `EntityShadowShaderTest` now fails on any `bindless_sampler` uniform, on any non-sampler2D sampler without
  `layout(binding)`, and on any uniform type `ShaderBufferData` does not know, in the patched sources. Those are the AMD rule, checked as text because our drivers pass it.

## Rigs

`devEntityShadowView` 1-14, `devEntityShadowCycle` / `Alternate` (variants off, cpu, pixel, probe, probev, flat, nocast,
noself, noclouds, notorch, noao, nodedupe, noreset, nomemo, dyncs, uni, nouni, pskip, pvs, ppix), `devEntityShadowCheck` (mapping check, occupancy dumps), `devEntityShadowStats`
(pipeline statistics), `tests/pzopt/EntityShadowShaderTest` (every patched program, NVIDIA + Mesa, bindless or not),
`harness/entshadow/{cost,paired,frametime}.py`.
