# Car glass: reflections and refraction on car windows (2026-10-01)

Maintainer's request: "make car windows have proper reflections and refractions, with virtually zero performance impact;
implement all the state of the art solutions, implement each and profile them". Worktree `~/pzopt-wt/carglass`, branch
`car-glass`. Code: `pzopt.CarGlass` (key `carGlass`, Enhancements tab section "Car glass", next launch), hooks in
`Model.drawVehicle`, `FBORenderCell` (before the moving objects), `ShaderUnit` (shader patch), `Hdr.patchVehicle`
(glints), build.sh (the `pzopt_glass_*` shader copies). Rig: harness flag `car_rig=N` (`pzopt.CarGlassRig`), tools
`harness/carglass/frames.py` (devCapture frames labelled by dev view / alternation) and `harness/carglass/cost.py`
(same-run on / off GPU split from `pzopt-gpusections.out`).

## What stock does

Car glass is part of the opaque body mesh, selected per texel by the mask texture (zones 7-12: four side windows, rear
window, windshield). The vehicle shader mixes the window texture's dark blue 30 % with the skybox texture looked up by a
view-space sphere map (`SphereMap(normal, positionEye)`): the same patch of sky whatever the car's heading, no world, no
sun, no interior. The cabin has no geometry and passengers are never drawn (`showPassenger` is set by no vehicle script).

## What the glass does now

Every window texel is shaded as thin glass seen by the game's orthographic iso camera (elevation 30 deg):

- **Fresnel** (Schlick, F0 4 %, n = 1.52) at the real view angle in the car's chassis frame.
- **Reflection**: the game's own sky texture (its clouds, sunset colours, stars; already rendered every frame for the
  stock look) sampled in the world frame (its hemisphere parameterisation inverted; skybox frame x west / y up / z south,
  turned about the vertical so its sun disk stands at the real sun's azimuth), and the **scene** around the car from a
  per-car reflection probe (below). Downward rays fall back to the ground plane.
- **Glints**: GGX highlights of the sun / moon (`pzopt.Sky`: real position for the date and hour, dimmed by cloud and
  fog) and of the five model lights the game already lights the car with (street lamps, headlights, torches: their
  positions in the chassis frame), sharp on glass (roughness 0.04 / 0.12). With HDR output they go into the HDR glint
  target above white (`Hdr.patchVehicle` takes `pzGlassHdrSpec` instead of its own glass term).
- **Refraction / what the glass lets through**: an analytic cabin ray-cast in the chassis frame (the view ray from the
  glass texel): seats (cushion, backrest, headrest boxes at the script's "inside" passenger positions), occupants (torso
  box + head sphere on the seats with a character), dashboard, the cabin shell (door panels below the belt line, floor,
  headliner); where the ray leaves the cabin through the glass on the far side, the scene behind the car. Lit by the
  car's own stock lighting, darker low in the cabin, tinted by the pane (green-grey, 85 % per pane).
- **Raindrops**: cars outdoors in rain get two layers of procedural drops on the panes (cell grid in the pane's plane,
  sliding down, stretching with speed); each drop replaces the pane normal by its curved face, so it catches the sky and
  the glints at its rim and dims the view through it.
- Cracks, blood and removed windows keep the stock overlays (the stock window colour shows where they cover; a removed
  window is open: no reflection, no tint).

## Chassis frame ("G")

The mesh goes through the script's model transform (BaseVehicle.updateTransform: translate(-ox, oy, oz), rotateXYZ,
scale) and its own mesh transform into the chassis frame; G to eye space is the chassis rotation and the iso camera.
Calibrated on the rig with dev view 4 (G position as colour): sedan windows at G y 0.55-1.15, rocker panel 0.1-0.2, roof
~1.2; the ground is `(floor - z) * 2.449 - centerOfMassY - BaseVehicle.centerOfMassMagic` (the first formula without the
0.7 magic put it 0.7 too high); script positions (passenger "inside", lamps from ModelSlotRenderData) are the Bullet local
frame = G with x mirrored, no vertical offset; the seat's hip point is the inside position + 0.15.

## Pipelines measured

Scene: `car_rig=8 car_rig_r=6 zoom=0.5` on the bench road (11 cars on screen, big: ~1000 px each at 5120x2160), RTX 4090,
uncapped, same-run alternation `devCarGlassAlternate=1000`, GPU sections (`moving.cgon` - `moving.cgoff` + the glass's
own pass), means.

| Pipeline (the scene behind the glass, the reflections) | GPU cost |
|---|---|
| blit: each car's screen rect (colour + depth) copied into an atlas before the vehicles; per-pixel SSR march | +306 us (copies 199) |
| two-pass: body (glass discarded), texture barrier, glass reading the live world; per-pixel march | +192 us |
| batch: all bodies, one barrier, every car's glass replayed after the moving pass | +189 us |
| live: one draw, a texture barrier before each car; per-pixel march | +110 us |
| live without the march (sky + ground plane + cabin) | +45 us |
| live without the cabin | +94 us |
| sky + glints only (no world reads, no cabin) | +10 us |
| probes: per-car reflection probe (32x32 octahedral, marched before the vehicles), live reads | +48 us (probe pass 20) |

Probe pass breakdown (`devCarGlassSkip`): ~8 us fixed (framebuffer switch + draw), ~9 us for the first reads of the
world colour / depth surfaces that frame (the GPU resolving the render targets for sampling); marching the 9 x 1024
texels is negligible. So probes are refreshed together every `carGlassProbeEvery` (8) frames, a new car's at once, a
moving car's every other frame.

**The patched stock program** (the first design patched the game's vehicle shaders themselves): with the glass code inside the game's vehicle shaders, the moving pass cost 270 us with
the glass switched off vs 64 us stock (zoom 1, ~20 cars): the inlined glass code's registers cut the occupancy of every
car fragment and the stock shader (11 texture fetches) became latency bound. A `discard` (tried for the two-pass) was
not the cause. Hence the final design: the game's vehicle programs stay exactly stock; build.sh copies them as
`pzopt_glass_*`, only the copies get the glass patch (first statement: discard every texel that is not a window), and
`Model.drawVehicle` draws the body mesh once more with the glass program right after the stock draw (depth LEQUAL).

## Final design and cost

Pipeline (defaults): the stock body draw (untouched program), then the glass program over the mesh's **window
triangles** only (an index buffer per mesh + mask texture: 7.6 % of the triangles; read back asynchronously through
staging buffers and a fence, 0.17 ms on the render thread per new model, the full mesh with per-texel discard until then),
a **compact glass fragment unit** (the mask test, the window's flags and the car's light from the per-car data, the glass,
the stock overlays only on damaged / bloody windows), **one `pzGlassCar` upload per car and one `pzGlassFr` upload per
frame** (the game's own setters are not repeated: the stock draw left the textures bound, the samplers keep their units),
**probes** refreshed together every 8 frames (a moving car's every 4, a new car's at once), no `glGet` except on probe
frames, cars under `carGlassMinPx` (40) on screen left stock.

Measured with in-run alternation (`devCarGlassAlternate`, `devCarGlassCycle` for several variants in one short run),
RTX 4090 at 5120x2160. Caveat: from 23:39 on 2026-10-01 the desktop had a steady 39 % GPU load from other applications
(editor, browser, chat); the stock moving pass itself measured 249 us then against 64 us before, so absolute numbers of
the static scenes below are inflated about 4x; same-run deltas stay comparable.

| Scene | Glass draws | Probe pass (per frame) | Render thread |
|---|---|---|---|
| Rosewood drive, 120 km/h, zoom 1 (~2-4 cars on screen) | +10 us | 4.6 us | within noise; frame time 6.30 vs 6.33 ms at the cap |
| Car ring, zoom 1, ~33 cars, desktop under load | +54 us | 3.1 us | +106 us (~3.2 us a car) |
| same before the window triangles (whole mesh, discard) | +40 us (quiet desktop) | | +150 us |
| no cars on screen | 0 | 0 | ~0 (one drawer per frame) |

Breakdown on the 33-car ring (`devCarGlassCycle=off,0,16,240,256`): the glass draws themselves with a constant colour
+11 us, the basic glass (Fresnel, sky gradient, sun glint, overlays) +20, the cabin ray-cast +11, probe lookups +3, lamp
glints +3, the sky texture ~0.

Variants measured and left off:
- `carGlassVertexEnv` (reflection lookups and Fresnel per vertex: exact for flat panes under the ortho camera): a loss,
  full glass +67 vs +54 us; the glass draws are a few hundred vertices each, too small to hide vertex texture fetches.
- `carGlassCompact=false` (the stock fragment shader with the glass appended): +95 vs +62 us (same round).
- `carGlassSsrMode=pixel` (every glass pixel marches its own ray through the live world): +65 us of the 110 us total of the
  live pipeline at zoom 0.5; the probes do the march once per direction per car.
- `carGlassLiveReads` (the scene behind the far window and the ground per pixel from the live world): needs the world's
  own render targets bound while drawing into them (a texture barrier per car); the probe gives those terms instead.
- HDR glints from the glass pass (`HdrGlint.vehicleOn` on the glass program): a second two-target switch per car, +50 us
  with HDR on; with HDR output the windows' glints come from the stock draw's own glint term.
- blit snapshots, two-pass and batch pipelines: see the table above.

Open: the render-thread cost per glass draw (~3 us a car on the static ring: program switch, vertex attribute setup,
the draw) could be halved by batching all glass draws after the moving objects (one program bind, one depth state),
at the price of rebinding each car's overlay textures; not done.

## Glass outside the window zones: the CarLuxury quarter windows and the side mirrors (2026-10-02)

Maintainer: "there is one car in the game that has a small window on the back that should also be glass, and the side
mirrors too". Dev views 10 (every texel by its glass class) and 11 (the diffuse with the glass tinted) on a ring of the 18
distinct models (`car_rig_models=`) showed that the masks only mark the stock window zones 7-12; the artists painted more
glass in the window colour elsewhere:
- the **CarLuxury** coupe's two small rear quarter windows (behind the door windows, 473 texels each, unmarked in
  `vehicle_luxurycar_mask`): that car;
- the LBMW news van's side window (VanRadio, `vehicle_vanradio_lbmwshell` on the van mask, unmarked);
- the **side mirrors** of every car: the window colour inside the door zones (the mirrors hang on the doors), on the pickup
  in the front guard (fender) zones.

`CarGlass.GlassMap`: per skin (mask + diffuse texture) both are read back asynchronously once, and a worker builds an R8
glass map: 1-6 the window zones; the window paint = the diffuse's median under them; connected blobs of unpainted diffuse
texels within its spread outside the window zones (>= 60 texels at 512^2, 5 x 5 across, half filled: trim lines and plate
squiggles drop out); a blob mostly in a head / tail or lamp / light-bar zone is rejected (bumpers, lamps); mostly in a door
zone, or small with a quarter of door / fender texels -> 8 mirror; else -> 7 extra glass (no part behind it: always intact).
The glass program reads the map (one fetch, replacing the six-colour zone test), the window triangles come from it, mirrors
are shaded silvered (75 % reflection at every angle, nothing through). Until a skin's map is ready (a few frames once per
skin) the car keeps the stock windows. `tests/pzopt/CarGlassTest` runs the classifier on the game's own textures (the
coupe: 946 extra texels = the two quarter windows, 791 mirror). Cost on 18 distinct models + the save's cars at zoom 1:
+66 us (the same per car as before, desktop under load), probe pass 2.7 us a frame. Key `carGlassExtra` (default on).

## Window flicker on Mesa (2026-10-02)

The maintainer's flip (Radeon 890M, Mesa 26.2) showed parked cars' windows "flickering like crazy": the glass pass drew at
GL_LEQUAL over the stock window, and Mesa compiled the glass copy's depth a rounding step off the stock program's (no
`invariant gl_Position`; the copy computes `transform * position` once more for the chassis position), so the glass lost
the depth test per pixel and the stock sky map showed through. Fix: the glass pass draws with a polygon offset of
(-1, -4) (`carGlassDepthOffset`, default on). A per-term cycle on the flip (probe lookups, sky texture, cabin ray-cast off
in turn) left the flicker in every glass variant and none with the glass off, which is what pointed at the pass itself.
Window pixels changing per frame on the same walk: 3.38 % without the offset, 0.09 % with it.

## macOS (2026-10-03)

Car glass never ran on a Mac: `CarGlass.active()` and the shader hook excluded macOS outright (the glass was written
alongside `macGlCore`, which moved every other enhancement's gate to `CoreGl.legacyMac()`). With that gate the glass
program then failed to link on Apple's GL 4.1: `ERROR: Input of fragment shader 'pzEnv' not written by vertex shader`.
The vertex patch declared the per-panel environment varying but wrote it only with `carGlassVertexEnv` (off); NVIDIA and
Mesa accept an unwritten varying, Apple's linker does not. The patch now writes `pzEnv = vec4(0.0)` when the vertex
environment is off. (The `pz_*` "attribute not found" lines in a macGlCore link log are warnings; read on to the ERROR.)

Mac runs (MacBook Pro M1 Pro, `car_rig=8 car_rig_seat=true zoom=0.5`, `cgmac-seat2`, `cgmac-seat3`, `cgmac-views`): both
glass programs link, ~20,600 glass draws a run, uniforms ~4 us per car on the render thread; the windows show the tinted
cabin where stock draws flat blue-grey.

The driver: dev view 5 marks the player's seat as occupied (yellow) and the other three empty, so the cabin ray-cast has
the occupant. In the picture it is not distinguishable from an empty seat (< 1/255 at the seat, view 3 and view 0): the
torso box is near black (albedo 0.045, like the seats) and from the iso camera the head sphere sits behind the roof when
the driver's side faces away. Same on every platform; a visible occupant (clothing / skin colour) is a look change, open.
