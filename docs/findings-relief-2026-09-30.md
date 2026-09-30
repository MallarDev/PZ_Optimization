# Relief ("parallax textures"), 2026-09-30

Asked by the maintainer: "implement parallax textures; secondary objective: make them cost virtually nothing; implement all
state of the art solutions, don't discard any idea, implement them one by one and profile them, work in a loop".
Worktree `~/pzopt-wt/parallax`, branch `parallax` from origin/master 6d4a366. Class `pzopt.Relief` (+ hooks in
`PixelLight`). Runs `px-*` on the desktop (RTX 4090, 5120x2160) through the queue. The mock-up that preceded it:
`parallax-demo/relief.py` in the main checkout (a brick wall crop relit by a low sun and a torch sweep).

## What parallax means under this camera

Parallax mapping has two halves. The view half (parallax offset mapping, Kaneko 2001 / Welsh 2004; steep parallax and
parallax occlusion mapping, McGuire 2005 / Tatarchuk 2006; relief mapping, Policarpo 2005; cone step mapping, Dummer 2006,
relaxed cones, Policarpo 2007; quadtree displacement, Drobot 2009) shifts the texture lookup by the height seen along the view
ray, so a flat polygon shows depth and occlusion as the camera moves. Project Zomboid's camera is orthographic and never
turns: every chunk-texture texel is seen along the same direction, the art is already painted from it, and the view shift
would be one constant offset baked into every sprite. What changes on screen is the **light**: the torch sweeps, lamps and
fires flicker, headlights pass, the sun and the moon move. So the height field is used for the light half: a normal per texel
(the art's grooves face away from a raking light) and self-shadowing along the light (parallax occlusion mapping's shadow ray,
horizon mapping), all at composite / bake time.

## Design

1. **Height from the art, no extra texture.** The chunk textures bake unlit with pixelLight (the albedo), so the composite
   estimates a height per texel from the colour it fetches anyway: `reliefHeight=lum` (brighter is higher: painted crevices
   are dark), `groove` (a colour away from its 4x4-texel neighbourhood's mean, mip 2, is a groove: pale mortar in red brick,
   dark gaps between planks), `mix` (default: both; they agree on a dark crack, the distance wins on a pale one).
2. **The tangent frame is exact.** pixelLight's texel normal already reconstructs the texel's world position and its
   neighbours' from the chunk depth; one texel along u / v is a world step on the plane. The relief normal is the cross product
   of the displaced steps (`tu + n0 s dh/du`, `tv + n0 s dh/dv`). A side whose depth leaves the plane (a wall's foot against the
   floor) is left out (one-sided difference), so surface edges do not become ridges. Floors and walls only by default
   (`reliefObjects`): furniture's painted shading would read as relief.
3. **Where it is paid.** The texel normal is lazy (computed once for pixels a dynamic light reaches, in the lit program
   variants only); relief adds four colour fetches and a mip fetch there. Minified textures (zoomed out) fade it.
4. **Self-shadowing** (`reliefShadowSteps`, `reliefShadowPct`): from the texel towards the light, projected on the plane,
   a few texels of the art's height against the ray (POM's shadow ray), soft.

## Log

- 01:40 worktree, reading: pixelLight's composite (`LIGHT_GLSL`, `CHUNK_FRAG_BODY`, the lazy texel normal), ChunkAo / sun
  kernel, foliage sway's per-texel attribute and variant programs (the cost lessons: registers and fixed per-pass costs, not
  arithmetic; compile each variant for its shape).
- 01:50 step 1 built: relief normal in pixelLight's texel normal (torch, lamps, fires, wet glints), dev views
  (`devReliefView` 1 = lit by a low light from the west, 2 = normal, 3 = height). Compiles and validates on NVIDIA and Mesa
  (`tools/hdr/glslcheck.c`, sources dumped with `/tmp/px/Dump.java`).
- 02:00 offline rig `harness/relief/relief_proto.py` on a pixelLight dump (`devPplDumpAt` + `devPplView=2`: the unlit albedo
  and the window depth, run `px-dump1`): (1) the window depth falls as x + y grows (C = -depth / 0.0014434); (2) pixelLight's
  texel normal over two texels tilts a wall ~20 degrees (DEPTH16 steps: 36 % of a wall's texels missed the 0.94 snap), so relief
  snaps at 0.8 and then uses the plane's **exact** tangents (one texel = 1/64 of x - y along u, 1/32 of x + y - 6z along v);
  (3) height estimates: luminance and the colour-distance "groove" are noisy (the art's texture), a band-pass (DoG) is clean,
  and the green channel's 2x2-block gradient (four `textureGather`s around the texel, a box-smoothed Sobel) is as clean as the
  DoG: stone blocks read as raised with bevels, planks get their gaps; (4) a soft limit on the slope (`reliefGmaxPct` 20) keeps
  sprite outlines from turning into cliffs. Dropped: Mikkelsen-style bump mapping from the pixel quad's `dFdx`/`dFdy` of the
  fetched green (no fetch at all): the quad-granular differences miss every other mortar line (blocky).
- 02:10 in game, dev view 1 (a low light from the west, `px2-view1`): the stone walls show their blocks raised; bushes and
  fences get relief too (their sprites' depth is a plane). Night torch at the wall (`px3-night-on/off`): nearly the same picture:
  a torch 3-4 squares in front of a wall hits it head-on, where a slope changes N.L by cos (a few %). Relief shows where the
  light grazes (a torch along a wall, a low sun), which is physically right.
- **Cost, first version (composite: four gathers + the self-shadow ray per lit fragment)**, `devPplAlternate` with a variant
  compiled without relief (cost bit 32768), night torch sweeping the wall, uncapped, 5120x2160, 4090:
  +326 us (`px4-cost-torch`). Registers of the torch program 16 -> 21 (relief normal) -> 25 (+ shadow ray) (`tools/ShaderRegs.java`).
  The texel normal (and the relief) ran for every pixel inside the torch's reach circle, before the cone test: moved after it
  (`T0`, also a saving for pixelLight itself). Then +670 us with the shadow ray, +206 us without (`px5-*`, a different sweep
  phase): the chunk textures overlap ~4x on screen and every layer's fragment pays the gathers.
- **Relief codes** (`pzopt.ReliefAux`, `reliefAux`): each chunk texture is encoded once after it bakes (a pass before the
  composite, textures on screen, `reliefAuxBudget` 8 a frame) into one byte per texel: 0 = none, else the texel's plane (floor,
  wall facing +x, wall facing +y; from the depth two texels around) and the art's slope along u and v in nine levels each on a
  square-root scale (prototype: 3 bits without a zero level are noisy, 4 bits look unquantised, 9 levels are close). The
  composite reads one R8 texel: plane + tangents + slope give the relief normal with **no depth reads**, so a floor or wall
  texel skips pixelLight's five-fetch texel normal. (`px6-cost-torch` read -30 us and `px6-cost-sun` +3 us: **both invalid**, no
  code was ever bound: the composite's per-draw hook runs on the StartShader op, whose `tex` is not the chunk's colour; the
  codes are keyed by `tex1`, the depth, as pixelLight's light lists and the AO terms. The code view `devReliefView=6` showed it:
  magenta everywhere.)
- **Torch with codes bound** (`px9-cost-torch`): **-23 us** against pixelLight without relief (875 vs 898 us, range -34..-16):
  floor and wall texels skip the texel normal's depth reads. The torch runs no self-shadow ray (no height in the code).
- **Sun / moon in the composite** (a post-main after the cloud shadows' patch, reading their direct-sun share; stock
  composite and pixelLight's alike): the share read 0 everywhere in clear weather (CloudShadow set its per-texture uniforms
  only while clouds were on; `Relief.wantsSunShare` now asks for them). Cost with the codes bound, `devReliefAlternate`
  (same programs, the relief's uniforms zeroed every other second), GPU section `composite`: **+87 us** median (`px11-cost-sun`,
  700 -> 788 us). Ablations (`devReliefSkip`, `px12-*`): the share fetch alone +37, share + code fetch +66, without the share
  fetch +82 (more fragments reach the math). Fetching the code first and skipping planes that face away before the share
  fetch: +86 (`px13-cost-sun`). **The fetches are the cost**: every chunk-texture layer's fragment pays them (~4x per pixel).
  A per-fragment path cannot be near zero; the sun, whose direction moves in steps, goes into the bake.
- **Baked sun / moon relief** (`reliefSunMode=bake`, default): the code texture is RG8, R the code, G the factor applied / 2.
  At the end of a bake (its framebuffer bound, before the mipmap build): encode, then the factor from the codes (relief normal
  facing over the plane's, times a self-shadow ray that rebuilds the height along the light by integrating the stored slopes,
  4 texels; weighted by the direct-sun share from the AO pass's kept term, the full share on bare ground), multiplied into the
  colour (2 src dst blend), G = the factor. On a key light step (SunShadow's 1.5 degree steps) the textures on screen get new /
  old (`reliefStepBudget` 4 a frame, the mip levels too), a ratio within 0.004 of 1 skipped and G kept there (the keep pass reads
  its own texel after a texture barrier). A texture shown without a code asks for a re-bake. The composite reads nothing for
  the sun. Offline (`aux_rig.py --factor`, low light from the west, share 1): factor mean 0.97, p5 0.47, p95 1.49 on the stone
  wall; blocks raised, grooves shaded on their sunward side.
- **Baked sun, in game** (`px14-bake-on/off`, 18:00): the stone walls' blocks and the bushes gain relief on the faces the
  low sun rakes (a direct share of ~0.3-0.45: the sun shadows treat the sun as ~45 % of the outdoor light, so the relief
  modulates that part, physically modest). Sun sweep (`px15-sweep`, `devSunHour=16 devSunHourSpeed=0.1`, 16:00 -> 17:30 in
  15 s, `devCapture`): 3,497 texture steps, frame-to-frame change 1.1-1.6 (the shadows' own motion), no chunk-shaped pops.
- **Cost of the bake** (GPU sections, `devReliefTiming` per pass): first version (encode, factor into a scratch, apply onto
  the colour, keep G after a texture barrier: four passes, four framebuffer switches) **47.5 us a bake** (encode 8.1, factor
  10.1, apply 5.3, keep 5.1: ~19 us of pass / framebuffer overhead). 120 km/h drive at 17:00 (`px17-drive-on/off`): 1.9 bakes a
  frame, bakes +13 % (3,756 re-bake requests) -> the bake section +207 us a frame.
- **Two passes**: the relight computes the factor itself and writes the colour (attachment 0, blended per draw buffer) and G
  (attachment 1, R8, cleared to 0.5 at a bake; a light step reads its own texel's G after a barrier, a skipped ratio keeps
  both) in one draw; the scratch is gone. `px19-drive`: **27.1 us a bake** (encode 8.6, relight 14.7).
- **The re-bake requests** were textures whose bake the hook never saw: a new chunk texture's GL names are created on the
  render thread (`TexDeferedCreation`), so at the end of its first bake on the game thread `rc.depth.getID()` was not valid yet
  and the hook skipped it; the texture then showed without a code and asked for a re-bake (2 a frame, the whole drive). The
  game thread now keys by `FBORenderChunk.index` + the chunk / level / size it holds (as the AO pass), the render thread
  resolves the GL names when the job runs.
- Zoomed out past `reliefMaxZoom` (1.75) nothing is baked, stepped or read (the relief is under half a pixel), and codes not
  drawn are trimmed over `reliefAuxBudgetMb`.
- **Torch self-shadow on the codes** (`reliefTorchShadowSteps`, the baked sun's ray per fragment, 4 texels): **+124 us**
  (`px16-cost-torchsh`) against -23 without. Off by default (a tab entry notes the cost).
- **Depth** (`px24-*`, 11:00 and 19:00, off / 100 / 200 %): at 11:00 the east-facing stone walls go from flat to raised
  blocks (100) to strongly sculpted (200), brick parapets get their mortar lines; at 200 flat concrete starts to look grainy.
  At 19:00 the roof shingles catch the evening light; walls in shade get none (the direct share is 0). Default 150.
- **Static scene, per frame** (noon, uncapped, player still): composite with codes bound vs not (`devReliefAlternate`, the
  weapon light's torch programs read them): +3.1 us median (`px25-static-alt`). Uncapped fps on / off: the scene is bimodal on
  the game thread (`LightingJNI.checkLights` 7 % or 20-27 %, relief on or off alike: `px26-static-off3` 426 fps at 27 %);
  pairs in the same state: off 466 / on 475 fps, off 426 / on 435 fps (`px26-*`). No fps cost.
- **Without pixelLight** (the stock lit bakes, `px27-stock-on/off`, 11:00): the baked sun relief works the same (the codes and
  the factor come from the lit colour; the height is a high-pass of it, the smooth per-square light barely enters).
- **Horizon mapping for the torch** (`reliefHorizon`: Max 1988 / Sloan and Cohen 2000; at the bake the tan of the relief's
  horizon along +u, -u, +v, -v from the codes, 6 texels, RGBA4 = 2 more bytes a texel; the torch's shadow is one fetch and a
  comparison with the light's elevation, the horizon along its direction weighted by cos^2 between the two axes): **+137 us**
  in the composite (`px28-cost-horizon`) and +20 us a bake: no cheaper than the ray on the codes (+124). Both are off by
  default: a lit torch pixel's extra work times the overdraw, not the fetch count, is what costs.
- **Texture unit.** The code sat on unit 13, which the cloud shadows' field also uses in the composite: with a cloud up each
  would have read the other's texture. Units 2 (code) and 3 (horizons) are only bound by passes outside the composite (the AO
  kernel, HDR's aux map before its own passes, ours), which bind before use; the code is bound every draw (one DSA call).

## Where it stands (2026-09-30 ~04:40)

| Path | Per frame | Per bake / step | VRAM |
|---|---|---|---|
| Sun / moon relief, baked (default) | **0** (`px25-static-alt`: +3 us median with the codes bound for the weapon light's torch programs) | +27 us a bake (encode 8.6, relight 14.7); a light step ~100 us per batch of 4 textures | code + applied factor, 2 bytes a texel (~2 MB a 1024^2 chunk texture; `reliefAuxBudgetMb` 512) |
| Torch / lamps / headlights (pixelLight) | **-23 us** vs pixelLight alone (plane texels skip the depth normal) | in the encode | the code |
| Streaming (120 km/h drive, 17:00) | fps 154.2 vs 154.0 (game-thread bound); bake section +67 us a frame | 1.6 bakes a frame | 502 MB at the budget |
| Measured and off | sun per fragment in the composite +86 us; torch self-shadow ray +124 us; horizon maps +137 us; gathers in the composite +206 / +670 us | | |

Dropped: Mikkelsen screen-derivative bump (quad-granular, blocky); the view half of parallax (offset / occlusion / relief /
cone-step / relaxed-cone / quadtree mapping: the camera never turns, so the view shift is already in the art and those
accelerations of a long view ray have no ray to march); micro-occlusion (a cavity term from the relief: the art paints its own
cavities, it would shade them twice).

Pictures (1:1 crops, 5120x2160, the Rosewood church block): `docs/media/relief-sun-1100-off-100-200.jpg` (11:00, stone walls
and roof, pixelLight), `relief-sun-1900-off-100-200.jpg` (19:00: the roof catches the evening sun), `relief-sun-1100-stock-off-on.jpg`
(without pixelLight, 150 %).
