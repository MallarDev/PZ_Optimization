# macOS on OpenGL 4.1 core (`macGlCore`, 2026-10-01)

Branch `mac-gl41` (worktree `~/pzopt-wt/macgl`). Mac: MacBook Pro M1 Pro, macOS 27.0.1, "4.1 Metal - 91.7".

## Why the Mac had no Enhancements

The game asks GLFW for a window with no version hints, so macOS hands it Apple's legacy context: OpenGL 2.1, GLSL 1.20,
no GL 3 entry point. Apple offers more only as a *core* profile (3.2 to 4.1, forward compatible), where the
fixed-function API is gone. On 2.1 the game runs its GL 2.1 shader path (`Core.getUseOpenGL21()` = `!OpenGL33`,
`ShaderUnit` rewrites every `#version 330` to 120) and every pzopt enhancement that needs GL 3+ was gated off
(`HdrMac.MAC`, `"OS X"`, `!MAC` checks, or a capability check: ShadowAtlas / CapsuleShadow / TreeSilhouette since
99320b9, because LWJGL aborts the JVM on a missing entry point).

Probe (`tools/mac/coreprobe/CoreProbe.java`, the game's JRE and jar): a 4.1 core forward-compatible context works,
GLSL 4.10; 330 / 400 / 410 / 150 / 140 compile, 120 and every `compatibility` source fail, `gl_FragColor` and `texture2D`
are undeclared in 330, `uimage2D` is a reserved word (no `GL_ARB_shader_image_load_store`). 43 extensions, among them
`GL_NV_texture_barrier`, `GL_ARB_texture_storage`, `GL_ARB_texture_gather`, `GL_EXT_texture_compression_s3tc`,
anisotropic filtering; no compute, image load / store, bindless, buffer storage, DSA, `ARB_clear_texture`, KHR_debug.

## What the game still calls from the fixed-function API

Small: 41 deprecated entry points in the game and pzopt (`glPushAttrib` / `glPopAttrib` 50 / 55 sites, `glTexEnvi` 51,
`glPushClientAttrib` 32, `glAlphaFunc` 25, immediate mode in TextureCombiner / DeadBodyAtlas / WorldItemAtlas /
TileGeometry and HDR's Mac encode, a matrix stack in HeightTerrain / ModelManager / PZGLUtil, display lists in
AngelCodeFont, lights in HeightTerrain). `glEnable(GL_ALPHA_TEST)` / `GL_TEXTURE_2D` 90 sites. The game's own drawing is
modern (generic vertex attributes, a shader on every path; `ShaderHelper` binds the default shader for program 0).
Quads: `VBORenderer` runs with mode 7 (`GL_QUADS`), RainTiles and FogPass `glDrawArrays(GL_QUADS)`.

## The shim (`pzopt.CoreGl`, `pzopt.CoreGlsl`)

LWJGL 3.4.1 forces `forwardCompatible` on a core context, so its table has no deprecated entry point (a call aborts the
JVM). After `GL.createCapabilities()` the shim builds a second `GLCapabilities` (package-private constructor, reflection)
with `fc = false` and its own `FunctionProvider`:

- **Java upcalls** (LWJGL `CallbackI`, FFM upcall stubs, ~70 ns a call on the M1) for the removed calls: alpha test, matrix
  stacks, immediate mode, texture env, client state, display lists (no-ops: `glGenLists` returns 0, so AngelCodeFont's
  caching turns itself off), lights / materials (no-ops), `glPushAttrib` / `glPopAttrib`, `glPushClientAttrib` / pop.
- **Aliases**: `...EXT` / `...ARB` names to the core function (180 of them: EXT framebuffer object / blit, ARB vertex buffer
  object...), `glTextureBarrier` to `glTextureBarrierNV` (and `GL_ARB_texture_barrier` reported).
- **A shared no-op** for any entry point the driver lacks: LWJGL's `OpenGL33` check with `fc = false` wants 30
  compatibility-only packed-vertex calls (`glVertexP2ui`...) Apple's core library does not have, so the flag came out false
  and the game silently stayed on its GLSL 1.20 path. With the no-op it is true and the game runs its 3.3 path like on Linux
  and Windows. Version and extension flags still follow the extension set, so nothing that checks them calls a no-op.
- **Emulated GL 4.4 `glClearTexImage` / `glClearTexSubImage`** through a scratch framebuffer (`GL_ARB_clear_texture`
  reported): SSR, Sway and god rays clear textures with it.
- **Validation**: the game validates a program right after linking, before it points any sampler at a unit; Apple fails a
  program whose samplers of different types still share unit 0 (NVIDIA and Mesa pass it). The shim reports
  `GL_VALIDATE_STATUS` true; the units are set before every draw.
- A vertex array object bound at all times (`glBindVertexArray(0)` maps to it).

`glPushAttrib` is the hot one: the game pushes `GL_ALL_ATTRIB_BITS` around every character, vehicle and item model.
Instead of reading ~80 values per push, the state setters (blend, depth, colour mask, viewport, scissor, cull, polygon,
stencil, line width, texture bindings; for client pushes the vertex attribute arrays, buffer bindings, pixel store) are
swapped in LWJGL's address table for tracking upcalls only while a push is open; each saves the old value the first time it
changes, the pop restores those. Outside a push every setter is the driver's own entry point.

### Shaders

`glShaderSource` passes every source (game, pzopt, mods) through `CoreGlsl.translate`, *after* pzopt's patchers (they
anchor on the stock `#version 120` text): GLSL 1.x and `compatibility` to 330 core (`attribute` / `varying`, `texture2D` and
friends through per-source wrapper functions declared ahead of the user's code, so a sampler named `texture` still works,
`gl_FragColor` / `gl_FragData` as declared outputs, `gl_Vertex` / `gl_Color` / `gl_MultiTexCoordN` / `gl_Normal` as
attributes at NVIDIA's aliasing locations, the matrices as `pz_` uniforms fed from the emulated stacks, `ftransform()`);
4.2+ to 410 with `layout(binding = N)` removed and applied after the link (`glProgramUniform1i`, `glUniformBlockBinding`);
`#extension` lines the context lacks dropped. Every fragment shader with one colour output gets the alpha test the core
profile dropped: `main` renamed, a wrapper discards by `pz_AlphaTest` (function, reference), which the shim keeps current on
the bound program (at `glUseProgram` and on every `glAlphaFunc` / `GL_ALPHA_TEST` change). A failed compile logs the
translated source with line numbers.

Offline rig: `tools/mac/coreprobe/ShaderRig.java` translates and compiles every file of a shader directory on the Mac's
compiler (stock: 192 of 195 compile; the 3 left are the debug-only instanced shaders, SSBOs). Unit test:
`tests/pzopt/CoreGlslTest`.

## Enhancements on the core context

Gates moved from "macOS" to `CoreGl.legacyMac()` (a Mac still on the 2.1 context: `macGlCore` off or the core window
failed). Status on the Mac (runs `macgl-enh*`, all at once, zoom 1, 13:40):

| Feature | Status | Notes |
|---|---|---|
| Ambient occlusion, sun shadows (chunk AO kernel, shadow atlas, capsule / tree silhouettes) | on | no change needed beyond the shim |
| Cloud shadows | on | non-bindless path (no `GL_ARB_bindless_texture`) |
| Relief | on | barrier through `GL_NV_texture_barrier` |
| Per-pixel light | on | 4.20 sources to 410, bindings after the link |
| Reflections (SSR) | on, `march` | `ppr` needs image atomics: `Ssr.mode()` is march on macOS, the image declarations sit under `PZ_SSR_PPR`, the composite scatter is not patched |
| Wet blood, GPU blood bake | on | |
| God rays | on | new fragment volume path (`GodRays.Gl.computeFrag`): the visibility, integration and 8-slice min/max passes as one draw per slice into the 3D textures' layers, the column integration walked from the top in each slice; local lights fall back to the analytic method, the low-res buffer to its fragment pass; the shade fused into the fog buffer is off on the core context (see Open) |
| Fog pass, rain tiles | on | `GL_QUADS` draws through `CoreGl.drawArraysQuads` |
| Foliage sway (incl. wind sprite sway twins) | on | |
| Sprite filter | on | |
| Colour grading | on | `layout(binding)` without 420pack (the translation applies it) |
| Darkness floor, remembered places | on | barrier through `GL_NV_texture_barrier` |
| HDR (EDR) | on, alpha-gain | the world patches (expansion, glints) and the stats / bloom passes now run on the Mac like on Windows; `GL_ALPHA_BITS` (gone in core) read from the back buffer attachment |
| FSR 1.0 | on | |
| bakeMipLevels | on | bind + `glTexParameteri` instead of DSA (core context only) |
| persistentVbo, GPU texture compression | stock / CPU path | no buffer storage, no compute: their fallbacks |

Harness gap found on the way: `run-mac.sh install` copied only `.class` / `.properties` / `.lua` / `.txt`, so pzopt's own
shader files (`media/shaders/pzopt_*`: pixel light's variants, sway's twins, sprite filter's tile variants, the puddle
early-Z shaders, visBlurReduce) were never on the Mac in harness runs (players get them from the release zip). It now
installs `.frag` / `.vert` / `.glsl` / `.h` / `.gif` too.

## Cost

Spin route (`--flag route=S:450 --flag turn=90 --flag zoom=max`, 120 fps cap), MacBook Pro M1 Pro, **a defaults options file**
(`--vmarg -Dpzopt.userOptionsFile=<empty file>`): the Mac's own tab file turns AO, pixel light + shadows, reflections, sun
shadows and FSR on, which never ran on 2.1, so every earlier comparison measured those features, not the context.

| Context | fps (two runs) | p99 | render thread |
|---|---|---|---|
| legacy 2.1 (`macGlCore=false`) | 106.7, 107.6 | 25.9, 25.0 ms | 58 % of a core |
| 4.1 core, timer queries on | 67.9, 67.2 | 30.0, 33.0 ms | |
| 4.1 core, timer queries off (`macGlTimerQueries=false`, the default) | 105.5, 106.1 | 24.9, 24.6 ms | 64 % |

**GL timer queries on Apple's OpenGL (over Metal) cost a third of the frame rate**: the overlay's `GL_TIME_ELAPSED` around
the frame and present pacing's `GL_TIMESTAMP` (present pacing switches from cpu to gpu mode once timestamps exist). The
legacy context has no timer query, so off restores the 2.1 behaviour (no GPU load in the overlay, cpu pacing).
`macGlTimerQueries=true` brings them back for a measurement.

The shim itself is ~6 % of a core on the render thread: ~1,500 `glEnable`, ~500 `glDisable` and ~1,000 `glUseProgram` upcalls a
frame (~70-150 ns each) and ~55 attribute push / pop pairs a frame (the game pushes `GL_ALL_ATTRIB_BITS` around every model
draw). The first version intercepted the state setters inside each push (swapping LWJGL's table entries while a push was
open): ~10,000 upcalls a frame (2,600 single states, 2,100 `glActiveTexture`, 1,450 `glBindTexture`, 1,500 vertex attribute /
buffer calls). A snapshot of the push's states read at push time (~120 `glGet`s, client-side on Apple's GL) replaced it.
The injected alpha-test `discard` measured free (`devCoreAlphaInject=false`: 50.2 vs 50.0 fps in the timer-query-bound runs):
the shaders the game draws with the alpha test on already discard or write `gl_FragDepth` (census with `devCoreGlTrace`:
tileWithDepth, the chunk composite, seamFix2, basicEffect, DeadBodyAtlas...).

Not the cause, measured on the way: present pacing (`presentPacing=cpu` or `off` with the queries still on: 49.9 / 50.5 fps),
the game's model / vehicle reflections that its 3.3 path turns on (`bPerfReflections=false`: no change).

60 km/h drive (`--mode drive`, KY-60 east, defaults options file), one run each with the new defaults: legacy 112.4 fps
(p99 17.3, p99.9 23.9 ms), core 109.0 fps (p99 18.2, p99.9 23.2 ms).

## Validation on the Mac (2026-10-01, runs `macgl-final-*`, `macgl-storm*`, `macgl-gr-*`)

- Daylight with the Mac's own tab settings (AO, pixel light + shadows, reflections, sun shadows, FSR), `devCoreGlTrace` on:
  118 fps at the 120 cap, 0 GL errors, every program linked, cable / character shadows drawn.
- Night with a lit torch (pixel light, darkness floor, remembered places, god rays, colour grading): the torch cone lit per
  pixel with the cables' shadows in it, 119 fps.
- Thunderstorm (rain tiles, puddles with early-Z, puddle and water reflections, wet blood, fog pass): 115-120 fps.
- Visual parity of the shim alone (zoom 1, stock settings): legacy vs core screenshots within 1/255 of mean colour.

## Open

- **God rays in a storm with pixel light on** draw a chunk-shaped brightness step (some chunk textures brighter, steady over
  seconds). Bisected on the Mac: absent with god rays off, with pixel light off and the fog fuse off, and with pixel light
  alone; present with god rays + pixel light (fuse on or off) and with god rays' fog fuse alone. Not the new fragment volume
  path (the stock composite's haze from the same volume is clean, and the step stays with the volume unused). The fused fog
  shade is off on the core context (`GodRays.fogFuse()`), which removes the pixel-light-off case. Suspect for the rest:
  god rays' chunk-haze uniforms go to `Ssr.boundProgram()` (the game's `ShaderHelper.currentlyBound`), which may not be the
  pixel-light variant program the chunk draws with. That is platform-independent code, so it likely shows on Linux too;
  not compared there (flip offline). Repro: `--flag zoom=1 --flag shot_at=8 --flag weather=storm --prop godRays=true
  --prop pixelLight=true`.
- `sprite filter`'s per-frame tile variants are refused because `pzSwObj` moves (3 -> -1) — the same on the desktop
  (NVIDIA, 2026-09-30 runs), not a Mac issue. At zoom 1 on the Mac `sharp` shows the lane markings' texels crisply (dotted
  lane texels, stair-stepped dash edges) where stock blurs them; not compared to Linux at the same spot.
- `VideoTexture` (menu video) sets `GL_CLAMP`, an invalid enum in core: the wrap stays REPEAT (one GL error at the menu).
- Not ported (optimizations with a working fallback): `persistentVbo` (no buffer storage), GPU texture compression (no
  compute: the CPU worker path), SSR's pixel-projected mode (march instead).
