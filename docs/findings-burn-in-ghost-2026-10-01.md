# The "burn-in" ghost in the Riverside fire department garage (2026-10-01)

Maintainer's report: `Screenshot_20261001_170752.png`, save `Sandbox/pzopt-bench_crash`, player at 6133.8,5250.7 (ground floor of
the Riverside fire department's garage, 21:20, lights on, zoom 0.5, game paused). A grey, translucent image of a chair, a
desk and tall cabinets, with a darker floor diamond, sits over the garage floor up-left of the character; the garage's
yellow lines show through it. Same wording as the 2026-09-28 report ("burned-in, fading image top-left of the character",
flashlight toggles), whose investigation ended at the BloodWet exception (released 08a66cd); that fixed the one-frame
flashes it measured, not necessarily this image.

## What every run shows in its first seconds (not a reproduction)

Each run of the save shows the garage with the ghost for the first two to four seconds after the loading frame, before
the harness teleports the player (the maintainer saw this on the desktop during the south-entry runs). That is the
save's `pzopt-resume.jpg`, the exit capture of the maintainer's session, drawn by `pzopt.ResumeShot` on the loading
frame and faded over the world for up to 3 s. Evidence: with `--prop resumeShot=false` (run `ghost-yard-near-noresume`)
the same seconds are black until the teleport; in `ghost-stairs-1`, which never teleports, the image fades out between
+8 and +11 s at the save's own spot and the live garage underneath has no ghost from +12 s on.

## What the image is

- It is live, not a load-time overlay: the save's own `pzopt-resume.jpg` (the exit capture, written 20 s after the
  screenshot) contains it. A fresh load of the save shows it only while that resume shot fades over the new world
  (run `ghost-stairs-1`, first world frame), then never again.
- Projected back through the save's resume geometry (`pzopt-resume.properties`: chunk corner 6128,5248 at 0.4209,0.3 of
  the screen, one tile = (+128,+64) / (-128,+64) px at zoom 0.5, one level = 192 px) the furniture sits at 6129,5249 if
  it were on level 1 or 6127.6,5247.3 on level 2. The level-2 office at the top of the stairs (6122-6127, 5247-5252: a
  desk, a chair, filing cabinets, run `ghost-stairs-1` frames at +24 s) matches the shapes; the level-1 room above that
  spot is the locker room. Both are hidden while the player is on the ground floor inside (building collapse).
- The region ends in a vertical edge on the right and a diagonal edge below: the bounds of one chunk texture (chunk
  765,655 / 765,656), not of a sprite. The image is achromatic and the lines of the floor show through it.
- The session's console (overwritten by the first run before it was copied; see the memory note) held three
  `ConcurrentModificationException`s out of `FBORenderCell.pzoptRenderInternal` (`BakeScheduler.begin`, frames 262912,
  311808, 318464, in Echo Creek / Brandenburg, long before the screenshot) and one stock `LungeState` error. The zoom went
  2.25 -> 2.5 (driving) -> 0.5 (at the fire department).

## Fixed on the way

`pzopt.BakeScheduler.chunkReused` ran on the world streamer / reuser threads (`IsoChunk.resetForStore`) and removed from
the scheduler's `IdentityHashMap`s while the game thread pruned them: the exception aborts that frame's cell render (no
world composite for one frame). The removal is now queued and drained by `begin` on the game thread.

## Not reproduced (11 runs, all on the maintainer's save copy, same options file, AV1 recordings in `harness/runs/ghost-*`)

| run | scenario | result |
|---|---|---|
| ghost-stairs-1 | stairs walk 0 -> 1 -> 2 -> 1 at zoom 0.5 (autopilot never got back to 0) | no ghost on level 1 |
| ghost-enter-far / -flip | teleport-walk through the medical room into the garage, zoom 2.5 -> 0.25 jump / flips every 3 s | none |
| ghost-yard-far / -near | from the yard (6134,5238) into the garage at 2.5 then 0.25 / at 0.5 | none |
| ghost-under-a2 / -b2 | standing in the lobby / stairs hall, then into the garage | none |
| ghost-south-ao / -noao | from the street south of the building (roof visible, building not collapsed) into the garage, AO on / off | none |
| ghost-stale-old / -fix | same with the deferred AO queue stalled and the kept-term multiply forced / skipped | none, both |
| ghost-stairs-zoomflip | stairs walk at zoom 2.5, jump to 0.25 on the way down on level 1 | none |

Ruled out by reading and by those runs: the resume shot (3 s maximum over the world); the chunk AO term (a kept term is
only multiplied under its own key and texture; a stale term of the previous content cannot cross into another chunk's
texture, and the forced-stale run showed nothing); the "obscuring the player" translucent draws (stock tests squares
1-3 tiles in front on the player's own level, and the per-frame draw re-tests `shouldRenderSquare`, so hidden squares
above are never drawn from stale lists); the other-scale zoom placeholder (a hidden level has 0 rendered squares and
returns before the placeholder draw; the zoom-flip run drew none); the bake-scheduler exceptions (one frame each, no
state left behind).

## Next time it shows

1. Do not quit. Copy `~/Zomboid/console.txt` first (the next launch truncates it) and note the zoom, the floor you came
   from (top floor? basement?), whether the torch was on, and whether a zoom-in happened after entering.
2. Run `harness/queue.sh submit cmd -- cp ~/Zomboid/console.txt /tmp/console-ghost.txt` or copy it by hand, then take
   the exit save (its `pzopt-resume.jpg` is the live frame).
3. Open Options > Profiler and read the `bake scheduler` / `zoom kept` counters from F9's log line, or let a session
   grep `pzopt: ` lines around the moment.

## Evening: what the south runs showed live (18:30-19:00)

The maintainer saw an artifact live, twice, in the south-to-north walks through the garage (runs `ghost-south-*`,
"a small artifact in the middle of the garage"). Scanned at the full 60 fps with `harness/flicker.py` and 10 fps crops:

1. **The façade wall lamp fading in mid-garage for ~1 s after the entry.** The south wall is cut away in the frame the
   player steps in (its texture re-bakes without it) and the lamp attached to it, a per-frame translucent object, fades
   out at `IsoObject.updateAlpha`'s rate (`alphaStep / 14` a frame on game time: about one second at any frame rate),
   standing on the floor with no wall behind it. **Vanilla does exactly the same** (`ghost-south-stock`,
   `enabled=false`, same frames). Not ours. Since the fade runs on game time it stands still while the game is paused:
   a cut-away object caught mid-fade and paused is a permanent, translucent, grey "burn-in" until unpaused. That is the
   most likely reading of the 17:07 screenshot (paused, translucent office furniture of the hidden top floor at its
   own place) and of the 2026-09-28 "burned-in, fading image" with the flashlight toggles; the stairs descent itself
   (run 1 and the stock `ghost-stairs-stock`) shows no such fade in either build, so the exact object set and moment
   of the maintainer's case are still unknown.
2. **Ours, fixed:** for 2-3 frames at the entry the roof was still drawn over part of the garage next to chunk-shaped
   black holes in the floor (every `ghost-south-*` run; stock switches in one frame). The scheduler spreads the
   building's cutaway / collapse re-bakes over several frames, and a held level kept stale square flags that made the
   occlusion grid cull and free the garage texture (census `ghost-south-census`, `BakeLog.hidden` re-creations). Held
   cutaway / collapse levels now refresh their flags each frame; `ghost-south-fix2`: no re-creations, one-frame switch.
   `docs/override-edits.md` "held cutaway levels keep their square flags fresh".

## Identity confirmed (19:30): the police office directly above, not a shelf

`docs/media/ghost-vs-level1-office-2026-10-01.png` (the screenshot crop, the same crop contrast-enhanced, and level 1 at
the same world squares from run `ghost-up1`, same zoom, shifted so the chairs align). Projected through the save's own
resume geometry the ghost's chair lands on (6132.3, 5254.3) and its table on (6133.7, 5254.2) at level 1: the two chairs
and the table of the level-1 police office (red walls, "hall" room in the lot) that stands right over that part of the
garage; the tall "shelf" block on the right lands on that office's north-east wall beside the bulletin board (a wall
segment seen whole, as a texture bake draws it, with the board as its lighter top). Level 2 at that spot is the roof
(`ghost-up2`). The alpha matches the cutaway alpha (0.25 when the square is seen): the office's squares are the ones the
wall stands on, and chairs, table and wall are all drawn at that alpha.

Descents from that office into the garage (`ghost-down1` fixed build, `ghost-down1-old` without the flag fix,
`ghost-down-pause` with the new `pause_at` flag) did not show it: the collapse re-bake lands within three frames and the
level-1 content is gone. The trigger of the maintainer's case is still open; the facts that would settle it: did they come
down the garage stairs (top at ~6138,5258 level 1) or the west stairs, were they standing by that office's wall just
before, and did the image fade when the game was unpaused.

## Maintainer's path (19:45): torch on, from the north fence

The screenshot's speed controls show the game paused; the overlay in its corner reads 157 fps at the VRR cap, max 7.7 ms,
nothing unusual. The maintainer entered the garage from the north yard, torch on, starting near the north fence, after
the session's zoom went 2.5 (driving) -> 0.5. Runs of that path on the fixed build: `ghost-north-torch` (torch on, from
6134,5226 south into the garage at 0.5, hold), `ghost-north-zoom-ease` / `ghost-north-zoom-jump` (torch on, from 6134,5222
at zoom 2.5, wheel / instant zoom to 0.5 on the approach, then 0.25 inside): no office over the garage in any of them.
Seen on the way: with the torch on, one frame right after the entry had the traffic cone's torch shadow smeared along the
floor (`ghost-north-torch`, 38 s), the reprojected torch shadow mask (`pplShadows`) one frame stale at the cutaway.

Layers that could paint the office's footprint, objects and wall as grey shapes at their own positions without a colour
draw, all keyed per chunk texture and all on in the maintainer's options: the per-pixel-light composite's texel level from
the chunk depth (a stale depth would light those pixels with the dark level-1 light: the stock bake clears the depth,
`TextureFBO.startDrawing` clears both), the relief codes (`ReliefAux`, re-encoded within a frame: "stale 0" in the
session's last log line), the kept AO term (the forced-stale run showed nothing at the office's scale), the puddle
reflections (`Ssr`: puddles lie under the ghost; a floor mirror would put the image 384 px lower than it is). None is
confirmed; none reproduced.

**Next time it shows, before unpausing** (one minute, settles which layer it is): Options > Enhancements, switch off and
Apply one at a time, watching the ghost: per-pixel lighting, relief, ambient occlusion, darkness floor, reflections
(restart needed, skip); then toggle the flashlight. Then copy `~/Zomboid/console.txt` before any other launch.
