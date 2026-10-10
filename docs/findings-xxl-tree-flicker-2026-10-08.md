# The see-through XXL tree flashing opaque (2026-10-08)

Maintainer report (flip, after the eceb5cf2 release): standing still under a big tree in their Sandbox save, the
see-through crown over the player "flickers". Workshop comments the same night ("tree cutoffs blink on and off") may be
the same thing.

## What it is

A one-frame flash: the whole see-through XXL crown (`e_redmapleJUMBOXL_1_0` at 6113,5271, the player at 6105,5263) is
drawn opaque for a single frame, then see-through again, a few times in 10 s and in bursts. The capture (`devCapture`,
a 1:1 gray crop of the tree at ~157 fps) shows ~59-76 % of the crown box changing in that frame and back the next.

## Cause

`pzopt.EntityShadow` (entity shadows, eceb5cf2, on with `sunShadows`) queued its probe compute (`Probes.compute`,
`computeDynamic`, `readBack`) from `frameStart` at the top of `FBORenderCell.renderInternal`, right before
`IsoCell.drawStencilMask` draws the cutaway mask (stencil bit 128). On the frames a brick changed and a dispatch went
out, the mask did not reach the stencil, so the tree's outside pass (stencil NOTEQUAL 128) drew the whole crown opaque.
The exact GL state left between the dispatch and the mask draw was not pinned down; god rays ends its compute passes the
same way (`glUseProgram(0)` + `ShaderHelper.forgetCurrentlyBound()`) later in the frame without this effect.

A per-frame trace of the tree (`FBORenderTrees.addTree`: transparent / cutawayAlpha / fadeAlpha / stencil) showed the
tree's own parameters unchanged and present in the flash frames, and no bake drew it: the tree data was right, the mask
was not.

## Fix

The probe compute is queued from `beforeMoving` instead (`EntityShadow.ComputeDrawer`, one per frame state), after the
mask and before every model draw that reads the probe atlas; `frameStart` still publishes the frame state (`rt`).
`entityShadowComputeEarly=true` restores the old placement (A/B).

## Runs (desktop, 5120x2160, a copy of the flip save `Sandbox/2026-09-26_03-37-09`, the flip's options file, standing still)

Bisect (upscaler / dynRes off, 10 s, burst = > 10 % of the crown box blinking):

| run | change | burst frames |
|---|---|---|
| xxl-ab-noup | flip options | 28 |
| xxl-bis-defaults | empty options file | 0 |
| xxl-bis-noenh | `enhancementsEnabled=false` | 0 |
| xxl-bis-offA / offB | lighting group off / the rest off | 0 / 44 |
| xxl-bis-offPpl / offSun | pixelLight off / sun group off | 36 / 0 |
| xxl-bis-offSunOnly / offGod | `sunShadows=false` / `godRays=false` | 0 / 13 |
| xxl-bis-offTreeSh / offEnt | tree shadows off / entity + mesh shadows off | 30 / 0 |
| xxl-bis-offEntOnly | `entityShadows=false` | 0 |
| xxl-bis-esCpu / esExtrasOff | `entityShadowMethod=cpu` / AO, casters, self, torches off | 0 / 19 |
| xxl-fix1-late / xxl-fix1b-early | the fix / same build with `entityShadowComputeEarly=true` | 0 / 9 |

Verification in the player's configuration (TAAU + dynRes on), `harness/trees/xxl-flicker.py` + Jev
(`xxl-flicker-judge.py`, against the stock game, `xxl-verify-stock`):

| run | blob px / frame | largest blob share | bursts | Jev |
|---|---|---|---|---|
| xxl-fix1b-early (old placement) | 368.7 | 72.1 % | 7 | pzopt_flicker 1.00 |
| xxl-verify-fix1 | 0.0 | 0.0 % | 0 | no_flicker 0.97 |
| xxl-verify-fix2 | 0.01 | 0.004 % | 0 | no_flicker 0.98 |

`xxl-verify-fix2` has 13.65 isolated transient px / 100k (single-pixel specks on the bloody ground in one 0.2 s
stretch, with a 0.7/255 dip of the whole picture): wet-blood glints, likely around a dynamic-resolution step; not the
tree, not in the entity-shadows-off control of the same settings (0.07). The blob figures leave them out.

## Rig

`--mode bench --source-save Sandbox/<copy> --refresh-template --flag explore=walk --flag walk=probe --flag zoom=1
--vmarg -Dpzopt.userOptionsFile=<flip options> --prop upscaler=taau --quit-after 55
--prop devCapture=18,10,240,100,gray,crop=2200:960:720:700,ram` (desktop: the crop holds the tree round the screen
centre), then `harness/trees/xxl-flicker.py <run> --json <run>/xxl-flicker.json` on both sides and
`harness/trees/xxl-flicker-judge.py TEST.json CONTROL.json`. `--refresh-template`: without it run.sh reuses an older
template of the save (the first flip runs loaded the 10-05 state).
