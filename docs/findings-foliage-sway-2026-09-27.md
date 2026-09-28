# Foliage sway: grass, bushes and trees in the wind at ~0 cost (2026-09-27/28)

Asked by the maintainer: "implement foliage sway; secondary objective: virtually zero performance cost; implement the state
of the art, don't discard any ideas, implement and profile them, loop until done". Worktree `~/pzopt-wt/sway`, branch
`foliage-sway`, on origin/master d4e3ff1 (uncommitted). Class `pzopt.Sway` (+ hooks in `Dlss`, `PixelLight`, `TreeBake`);
runs `sway-*` on the desktop (RTX 4090, 5120x2160) through the queue.

Keys (Enhancements tab section "Foliage sway"): `foliageSway` (live, off by default), `foliageSwayPct`, `foliageSwayTaps`.
Launch: `swayPush`, `swayMotionVectors`, `swayMvImage`, `swayMvFold`, `swayMvNoBlend`, `swayTwinRemap` (on);
`swayIterations` (1), `swayGust` (sines), `swayDepthCheck`, `swayMask` (on), `swayAuxBudgetMb` (160); measured and off:
`swayBindless`, `swayPrefetch`, `swayAuxEager`, `swayTwinAll`, `swayLightUndisplaced`. Dev: `devSwayAlternate` (+
`devSwayAlternateAll`), `devSwaySkip` (bisection bits 4, 8, 32, 64), `devSwayView`, `devSwayNoPatch`, `devSwayVariantAll`,
`devSwayVariantStock`, `devSwayGainPct`, `devSwayWind`, `devSwayFlipY`, `devSwayPushOrbit`, `devSwayDumpDir`. Harness flag
`wind=0..1` (+ `wind_angle=`) pins the climate's wind. Rigs: `harness/sway/abframes.py` (within-run A/B), `sections.py`,
`allsections.py` (every GPU section split by the alternation; `--cpu` the render thread's time), `amplitude.py`,
`composite_rig.py` (offline, not predictive); `tools/ShaderRegs.java` (a program's registers on NVIDIA).

## What stock does

B42 has a "Wind sprite effects" display option (`doWindSpriteEffects`, off by default). With it on, every tree and every
sprite with the `moveWithWind` property (grass, bushes, `windType` 1..3 for stiffness) leaves the chunk textures and is drawn
per frame, its quad's top corners offset by one of 15 shared random-target lerps per wind type (`ObjectRenderEffects`).
Characters walking into a bush or tree start a `Vegetation_Rustle` effect on it: the object is invalidated (its chunk level
re-bakes), drawn per frame while it shakes, and re-baked again when it stops.

Cost of the stock option on the church lot at zoom 1 (uncapped, `sway-ref-*`, two runs each, route window):

| | frame | GPU | p99 |
|---|---|---|---|
| option off | 1.170 ms | 1.109 | 2.75 |
| option on | 1.518 ms | 1.480 | 2.94 |
| **cost** | **+348 us** | +370 us | +0.19 ms |

## Design

Keep every plant baked and move its pixels where the chunk textures are composited, a pass that runs every frame anyway.

1. **Bake: a per-texel sway attribute and a free flag.** A second colour attachment (RG8) on the chunk FBO, created only
   for a texture whose bake draws a plant (freed again after a bake without one). The bake's tile programs (tileWithDepth,
   opaqueWithDepth, seamFix2, CutawayAttached) are patched so every `gl_FragColor = c` also writes the attribute: the
   plant's weight at this row (height in the plant ^1.5 times the plant's amplitude from its class and `windType`: the base
   stays, the top moves most: a pivot / weight map as in Pivot Painter), its class (grass, bush, tree: natural frequency), a
   per-plant phase (hash of the square) and four bits of the texel's depth. Rigid objects write 0, so a fence post drawn over
   a hedge clears it. The patched programs also force the **lowest bit of the texel's DEPTH16**: 1 on a plant texel that
   moves, 0 on anything else (one depth step, 1.5e-5); the composite reads that depth anyway, so the plant test is free.
   Both draw buffers stay on for the whole bake, attachment 1 gated by `glColorMaski` (a `glDrawBuffers` switch revalidates
   the FBO); model / generic drawer ops drop to one draw buffer (B42 doors are 3D models; ChunkAo's pass sets its own
   colour mask). The per-object uniform (`pzSwObj`) rides on the sprite's own uniform chain; a rigid draw resets it once
   after a plant used the program. Trees (baked by `pzopt.TreeBake`) are drawn by our own MRT program with the same colour
   and depth as the VBO path. A byte per 16 x 16 texels (`swayMask`) says whether a plant can reach there.
2. **Wind (game thread, per frame).** The climate's wind intensity (smoothed) and direction (the sign of the angle
   intensity, as stock's sprite wind and the rain's slant) drive: a lean downwind (`w^1.3`), an oscillation per class
   (grass ~1.3 Hz, bushes 0.8, trees 0.35, faster in strong wind) with the per-plant phase (Crysis's main bending), gusts as
   two crossing sine waves drifting downwind at 1.5..9 squares/s (Ghost of Tsushima's travelling wind, analytic: a gust
   texture was a dependent fetch that cost ~12 us), and leaf flutter on bushes and trees (a higher harmonic varying across
   the crown: detail bending). A breath of air on calm days.
3. **Composite: an inverse warp.** For a pixel p the shader wants the texel s whose displaced position lands on p:
   `s = p - D(s)`. One fixed-point step from s = p with the texel's own attribute (`swayIterations`, a second step cost
   +7 us for no visible change: the weight varies vertically, the displacement is mostly horizontal). `foliageSwayTaps` 2..4
   add probes upwind for pixels a plant's leading edge moves over (a texel whose own displacement lands within 0.75 px of p,
   accepted only if it is not behind what p shows). Colour and depth both come from s, so characters still sort against the
   moved plant. The colour fetch uses the draw's mip level (a per-draw uniform: a displacement jump at a plant's edge must
   not pick a coarse mip, and no derivatives are needed).
4. **Which program.** The game's composite is never patched. A sway **variant** (`pzopt_swChunk`: the recorded stock source
   plus the warp) is bound only for a chunk texture that holds plants (TextureDraw's StartShader, one program per draw as in
   stock); pixelLight's and the sprite filter's composites get **twins** compiled on demand from their recorded final
   sources (`ShaderUnit` hands every source to `Sway.recordSource`; each twin is link-tested before use and a failed one
   keeps the game's program). Each variant is compiled for its exact shape (taps, iterations, push, motion, dev view): a
   uniform-gated dead path costs its registers in every fragment.
5. **Per-frame trees** (faded / translucent near the player) get the same wind at their top as a corner shear through
   stock's own `FBORenderTrees` path (a CPU copy of the shader's wind function).
6. **Characters push plants** (`swayPush`): a character moving through grass or a bush (`IsoMovingObject`'s movement code)
   becomes a pusher (eased in 0.2 s, out 0.6 s; the 8 nearest the camera); plant texels near one bend away from it, more
   at the top. With sway on this replaces stock's `Vegetation_Rustle` (a per-frame draw and two chunk re-bakes for every
   bush walked into).
7. **DLSS** (`swayMotionVectors`): without motion vectors DLSS's history damped the sway to ~66 % of its native amplitude.
   A plant fragment computes how far its texel moved since last frame (the previous frame's wind uniforms) and stores it
   (`swayMvImage`) into an R32UI image, [frame epoch 10 bits | x 11 | y 11] at 1/256 render px, plus the epoch into a 16 x 16
   tile image; nothing else in the composite writes (the world framebuffer keeps one draw buffer). DLSS's own depth + motion
   pass reads a tile's epoch (one small cached fetch) and only in tiles written this frame the pixel's word, adding it to
   the camera motion; both images are cleared every 512 frames so a stale epoch never matches. Characters and cars still
   get their own motion right after (their stencil rectangles overwrite). The first version (a second render target of the
   world framebuffer, zeroed by the resolve, a pass of its own) stays behind `swayMvImage=false`.
8. **VRAM**: the attribute textures of chunk textures not drawn for 3 s are freed above `swayAuxBudgetMb` (their chunk
   re-bakes when it comes back on screen).

## Bugs found on the way

- Renaming the composite's `varying vec2 texCoord` broke its link with the vertex shader's output (varyings link by name):
  every chunk texture sampled one constant texel (flat coloured squares). The varying keeps its name; the uses are renamed.
- Tree crowns had rows rejected by the depth check: the driver's float -> DEPTH16 conversion differs from
  `floor(d * 65535 + 0.5)` by one step on some rows. Fixed for good by writing the depth quantised ourselves.
- Doors carried garbage attributes: B42 doors are 3D models (`IsoObjectModelDrawer`, DrawModel ops) whose program is bound
  outside the sprite stream's StartShader, so the second draw buffer stayed on and got their colour (gl_FragColor
  broadcasts to every draw buffer). Model / generic / water / particle ops switch back to one draw buffer first.
- ChunkAo's in-bake AO pass calls `glColorMask` itself, re-enabling the masked attachment 1: its output overwrote the
  attributes (pixelLight's twin showed almost no sway).
- A variant that did not compile returned the placeholder source (an empty main): every plant texture drew black. A failed
  variant now gets a source with `#error` (the game marks it uncompiled; the texture keeps the stock composite).
- Cloud shadows' functions above `main` fetch DIFFUSE: the redirected fetch must be declared right after the `texCoord`
  declaration. The sprite filter's composite reads `gl_FragColor` after writing it: with the motion output every use is
  renamed to `gl_FragData[0]` (a mixed use compiles but does not link).
- RG8_SNORM is not colour-renderable on NVIDIA (the writes were dropped silently): the motion texture is RG16F.

## Cost

Within-run A/B (`devSwayAlternate=1000`, the off half runs the game's own composite program), church lot, zoom 1, wind 0.8,
uncapped, `uiRenderOffscreen=true`, 5120x2160, paired on/off seconds (mean +- standard error):

| step | cost |
|---|---|
| v1 (noise per displacement call) | +56 +- 5 us (zoom 2.5: +70 +- 9) |
| gust noise computed once per pixel | +49 +- 3 (against the patched-off program) |
| ... bisection: no upwind probes / plain colour fetch / no depth check / + no noise | +24 / +46 / +41 / +35 |
| **patched composite with sway off, against no patch at all** (3 + 3 runs) | **+12.5 us** (1163.4 vs 1175.9, spread +-1.5) |
| composite as a separate variant program bound only for textures with plants: off cost | **0** (1160.9 vs 1159.1) |
| ... on cost against the game's own program: default / no probes | +61.6 +- 4 / +38 +- 4 |
| ... tile mask (a byte per 16 x 16 texels: can a plant reach here) | +62.5 (no mask +68) |
| **isolation**: variant bound, its lookup off (pzSwOn = 0) | +33 |
| ... program selected before binding (no double start) / variant for every draw | +33 / +33 |
| ... no per-frame glGetIntegerv(VIEWPORT) (texture-space mapping instead) | +37 |
| ... colour fetch textureLod / textureGrad / implicit | +34 / +25 / +34 |
| ... the variant a plain copy of stock, nothing bound | **-2 +- 3** (switching programs costs nothing) |
| ... the patched variant, nothing bound | +15..19 |
| ... a stock copy with every per-draw bind and uniform | **-1 +- 6** (the per-draw GL work costs nothing) |
| compile-time specialised variant (no dead paths): taps 3 / 1 / 1 without check and mask | +54 / +43 / +37 |
| per-draw mip level (no per-fragment derivatives), mask tap first | +51 / +43 / +34 |
| depth-bit flag (the bake sets the depth's lowest bit on plant texels; the composite tests the depth it reads anyway) | taps 3 +54, taps 1 **+27 +- 6** |
| gust as two analytic waves drifting downwind (the gust texture was a dependent fetch in the chain) | +18.2 +- 2.7 |
| **one fixed-point step (default)**; two steps | **+14.6 +- 2.7**; +21.3 |
| zoom 2.5 (more chunk textures on screen), two steps | +30.9 +- 3.2 |

The composite pass itself (GPU sections split by the alternation, `harness/sway/sections.py`): ~500 us at 5K zoom 1 (the chunk
textures are 1024 x 2048 at zoom 1 and overlap ~4x), taps 1 +13..17 us, taps 3 +46 us.

### Bakes, streaming, VRAM, game thread

- Bakes while streaming (bench route, `devSwayAlternateAll`: the off half bakes without attributes; `chunks` GPU section):
  +13..19 us a frame while `glDrawBuffers` switched per program inside a bake (the driver revalidates the FBO on every
  switch); **-5.9 / -3.4 us (noise)** once both draw buffers stay on for the whole bake and attachment 1 is gated with
  `glColorMaski` (model / generic drawer ops still drop to one draw buffer: ChunkAo's in-bake pass and the doors).
- VRAM: 104 MB at zoom 1 on the lot, 272 MB at zoom 2.5; while streaming the attribute textures grew to 448 MB (keyed to
  pooled render chunks, never released). Now freed after a bake without a plant and, above `swayAuxBudgetMb` (160), for
  textures not drawn for 3 s (their chunk re-bakes when it comes back on screen): 142..180 MB while streaming.
- Game thread (Louisville horde, game-thread bound): 0.36 % of the stack samples in `pzopt.Sway` (the per sprite-start
  lookup, since cached by program id); the push hook queues at most 64 movers a frame.

### The maintainer's full set (pixelLight, sharp sprite filter, DLSS, HDR, AO, god rays, reflections, sun shadows)

| step | cost |
|---|---|
| pixelLight's composite patched in place, DLSS | +45 +- 5 us (upscaler off: ~+93) |
| twins compiled from the recorded final sources instead (off cost 0) | +45 +- 6 |
| + DLSS motion vectors, attachment attached / detached every frame | +530 (the driver revalidated the world FBO) |
| ... attached once, `glDrawBuffers` per sway draw | +497 |
| ... both draw buffers for the composite, attachment 1 gated with `glColorMaski` | **+86 +- 5** (motion vectors ~+41) |
| ... RG8_SNORM motion texture / zeroed in the DLSS resolve instead of a clear | no change (RG8_SNORM dropped: not renderable) |

pixelLight's composite is heavy (its lighting taps all wait on the displaced coordinate), so its twins cost ~3x the stock
variant per pixel.

### Final build (2026-09-28)

Runs of 2026-09-28 are short (15 paired seconds, `--flag hold=15 --route-seconds 18`), one or two per row; +-7..10 us.

**Off** (sway off, the patched tile programs vs no patch at all, three runs each, mean frame time of the last 15 s):
1155.3 us without the patch (spread 2.5) vs 1159.7 (1165.3 / 1154.2 / 1159.6); GPU 1095.8 vs 1098.9 us: inside the noise.

**Default settings** (no upscaler, no pixelLight): **+19..21 us** (+19.2 +- 4.0, +20.9 +- 4.6, +21.0 +- 3.3, +19.7 +- 2.8),
1.7 % of the 1.16 ms frame; the stock wind option costs +348 us on the same lot.

**The maintainer's full set** (pixelLight, sprite filter, DLSS, HDR, AO, reflections, god rays, sun / cloud shadows):

| step | cost |
|---|---|
| start of the day (twins, motion as a second render target) | +85.9 +- 5.6 |
| motion attachment written unblended (`swayMvNoBlend`: glEnable(GL_BLEND) covered every draw buffer) | +90.8 +- 5.5 vs +105 +- 11 blended (noise) |
| render thread: pixelLight binds the twin itself (one program start a draw, not three: `swayTwinRemap`), the twin looked up by identity not by a string key, the motion folded into DLSS's own depth + motion pass (`swayMvFold`, no pass and no `glDrawBuffers` switches of its own) | +87.9 +- 9.3 (render thread +91 -> +46 us a frame, the GPU is the wall) |
| bisection: motion written as zero / motion math skipped (dev 32) / no motion | +96 / +83 / +51 +- 13 |
| plant fragments `imageStore` their motion (RGBA16F + epoch; nothing else writes, no second render target) | +89.6 +- 6.0 (amplitude 3.3 -> 4.0 px) |
| bisection: a twin that returns at once (dev 64) with / without motion | **+47 / +18**: the motion read in DLSS's pass was ~29 us fixed |
| **motion as R32UI [epoch 10 | 11 | 11 bits], 16 x 16 tile epochs gate DLSS's read** | **+52.5 +- 7.1** (amplitude 4.06 px) |

Measured and left off (no gain inside the noise): `swayLightUndisplaced` (pixelLight's light taps on the pixel's own texel:
+84 / +83 vs +86 / +83), `swayTwinAll` (every chunk draw through the twin, no program switches: trivial twin +54 vs +47),
`swayPrefetch` (colour and depth fetched with the flag test: full +85, default +20.9 vs +19.0), `swayAuxEager` (the attribute
fetched beside the depth: default +19.7 vs +21.0, full +59). Register use (`tools/ShaderRegs.java`, NVIDIA's program binary):
the twins need exactly the registers of their base programs (8 / 17 vec4 temporaries), the stock variant 6 vs 2.

What is left of the full set: ~18 us for the twin programs themselves (a twin that does nothing), ~35 us for the lookup inside
pixelLight's composite at DLSS's render size.

## Visual checks

- `devSwayView=1` (stock / variant path): plants tinted by weight (red) and class (blue for trees), rejected attributes dark,
  upwind landings green; fences, walls, graves and doors untouched.
- Amplitude (`harness/sway/amplitude.py`, 1:1 crops of the graveyard, median shift range of the moving 64 px blocks):
  default 5.3 px; full set + DLSS 4.06 px with the image-store motion (3.3 with the render-target motion, 2.1 without any:
  DLSS's history damped it); full set without pixelLight 4.39 px (3.4 before); sharp sprite filter at zoom 1.5 2.5 px. The
  frames with the most change between two captures show only the plants moving (no tile-shaped or smeared artifacts).
- Configuration matrix (default, full set, full set without pixelLight, sprite filter active): same brightness with sway on
  and off, every variant / twin compiled and linked, no shader warning.
- Stock's translucent crown tops on faded trees are unchanged (the same with sway off).

## Open

- Push: verified with a dev pusher (`devSwayPushOrbit`); its radius (1.2 squares) and ease may want tuning in play.
- With the default taps 1 a plant's outline stays where it was baked and its content moves inside it; taps 2..4 extend the
  leading edge for ~+13 us a probe.
- The full set costs ~+52 us at 5K on the 4090: ~18 us is the twin programs existing (not registers, not program switches,
  not fetch latency: all measured), ~35 us the lookup inside pixelLight's composite.
- `dlssPipeline=true` (two image sets) with the old render-target motion attached the texture to one set's framebuffer
  only (fixed: attachments tracked per framebuffer, reset when DLSS rebuilds them); the image path has no attachment.
- Enhancements tab preview clips for the section (it reuses another section's clip slot for now).
- macOS (GL 2.1 context) is excluded like the other composite features.

Lessons (the same as the god rays pass: fixed costs and the pass's fragment count dominate, not the arithmetic):
- A uniform-gated dead path costs its registers in every fragment: compile the variant per shape.
- A ternary between two texture calls is flattened (both fetches run).
- Most of the frame-level delta that is not in the composite section was noise: the per-draw work measured -1 +- 6.

