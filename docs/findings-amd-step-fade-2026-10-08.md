# AMD / Windows: the screen black at every sun step (2026-10-08)

## Reports

Two Discord bug-report posts on 2026-10-08, both AMD on Windows (RX 6700 XT / RX 6650 XT, driver 32.0.21045.5002), release
3fe8723f, sun shadows + cloud shadows on, game hour ~6:00 (moon key light):

- "Ground flickering to black" (mngskmanjones): appeared after updating to 3fe8723f, also in single player without mods;
  sun shadows off fixes it.
- "Issue with flickering" (helraven): the same; sun shadows off fixes it.

## The signature in their videos

Per-frame mean luma of the player's 8 s clip: the picture drops from ~80 to ~5 (89 % of the pixels black) in one frame,
holds ~0.4 s, then eases back over ~1 s; twice in 8 s. The other clip: the same drops (to 25, then 5) with the bright roof
tiles and the road markings blown out to white while the ground goes black. 0.4 s flat + 1 s ease is the
`sunStepFadeMs` (1500, smoothstep) fade of dbee0c2f's `sunStepSync`, starting from a wrong old term.

The fade (CloudShadow composite, bindless path) multiplies each pixel by `mix(1, old / new, rest)`. Black ground with white
light roofs means `old` is not the kept term but the chunk texture's own colour: the bindless sampler uniform `pzStepOld`
read texture unit 0 (DIFFUSE), i.e. its handle never took. Its sibling `pzCloudTerm` was a bindless sampler uniform too
(the cloud's direct-sun share; a wrong read there is faint, so it was never noticed).

## Cause (inferred: no AMD / Windows machine here)

Both were `layout(bindless_sampler) uniform sampler2D` in the game's chunk composite program, which the game numbers
(`ShaderProgram.onCompileSuccess`: every sampler2D but the first gets the next unit, in the driver's order) and validates.
On AMD's Windows driver the handle set with `glUniformHandleui64ARB` did not stay: the sampler read a texture unit. NVIDIA
and Mesa keep the handle, so neither the desktop nor the flip showed it. The same class of driver difference as
65759d1 (entity shadows invisible on AMD / Windows: bindless sampler uniforms in game programs).

## Fix

`cloudHandleInts` (default on, launch): the composite gets each handle as two int uniforms (`pzCloudTermLo / Hi`,
`pzStepOldLo / Hi`) and builds the sampler from them (`sampler2D(uvec2(uint(lo), uint(hi)))`), as the entity shadows'
model shaders do since 65759d1: no sampler uniform of ours is left in the game's program. `cloudHandleInts=false` is the
old path. `int` because the game's ShaderBufferData knows no unsigned uniform types.

## Verification

- Emulation (flip, `cloudHandleInts=false --prop devStepOldUnit=0`: the old term's sampler on unit 0 instead of its
  handle), the low-sun sweep rig (`--flag start=8168,11502 route=E:0 hold=14 time_of_day=20.0 weather=clear zoom=2
  --prop devSunHour=19.6 devSunHourSpeed=0.03 devCapture=6,14,30,25,gray`): run `amdfade-emul`, a drop to 33-45 at each
  step then a ~1.5 s ease back (16 dropped frames in 13 s, `harness/lowsun-flicker.py`: 3 pop waves, picture overshoots
  27 / 43 luma): the players' signature.
- Fix (flip, defaults with sun + cloud shadows): run `amdfade-fix`, 0 dropped frames, luma 74-78, `lowsun-flicker.py`
  0 pop waves and 0 reversals: the steps still ease in (without the fade each step is a one-frame pop).
- NVIDIA (desktop, headless `/tmp/glslcheck`): the patched stock composite with the int handles compiles, links and
  validates; `CloudShadowShaderTest` checks it and that the ints branch declares no sampler uniform.

Workaround for players until the fix: sun shadows off (or `sunStepFadeMs=0`: the step switches in one frame).
