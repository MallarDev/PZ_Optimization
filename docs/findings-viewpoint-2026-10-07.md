# Project Viewpoint profile (2026-10-07)

Workshop 3809306528, "Project Viewpoint" 0.1.5a-hotfix (ellu / norkus, posted 2026-09-27, updated 10-01). It is a
ZombieBuddy Java mod (requires ZombieBuddy 3619862853, 2.3.4 here) that adds a whole new renderer: when the player presses O,
it draws the world in first or third person with its own deferred PBR pipeline (G-buffer, sun / lamp shadows, volumetric
"air", bounce light, TAA, bloom, a far-world LOD, Iris shader-pack support). Its jar holds 654 classes, it is pinned to Build
42.21.0 (SHA-256 of `projectzomboid.jar`; our install leaves the jar alone, so the pin passes), and it ships a proprietary
"all rights reserved" licence.

Rig: `~/src/viewpoint-1007/` (`submit.sh <side> <bench>`, `vp-wrap.sh`, `look/VpLook.java`); runs `vp-*` / `vp2-*`.
Desktop, 5120x2160, the game dir's JVM (stock 42.21: ZGC, `-Xmx3072m`), the mod's Default graphics preset, the 3D model
pack (3810302175) not installed.

- First person turns on only for a real O key press (`GameKeyboard.isKeyPressed`), and only once the ImGui setup window is
  finished. `vp-wrap.sh` writes `onboarding.finished=true` + `graphics.preset=` into `Zomboid/viewpoint-live.properties`,
  waits for `harness: world ready`, focuses the window and presses O with xdotool. The console line `[Viewpoint] compat:`
  confirms it; one press worked in every run.
- The camera is `viewpoint.input.Look.yaw/pitch`: absolute angles fed by raw GLFW cursor deltas (minimum sensitivity 0.1,
  no Lua access). In the first spin run the desktop mouse turned it to a wall and then the sky (cheap frames, 227 fps,
  discarded). `VpLook` (`-javaagent`) holds pitch 0 and yaw = the player's `getDirectionAngleRadians()` every ~1 ms;
  recordings checked: level view over Rosewood (spin), down the road (drive).

## Numbers (route window, in-game overlay)

| bench | side | fps | p99 ms | p99.9 ms | 1 %-low | GPU | game thread |
|---|---|---|---|---|---|---|---|
| spin (Rosewood walk, 25 s) | stock | 84.5 / 90.2 | 42.0 / 38.4 | 56.1 | 24 / 26 | 62 % | 98 % |
| | stock + ZombieBuddy only | 52.0 | 113.7 | | 9 | | |
| | Viewpoint loaded, iso view | 104.9 / 104.7 | 35.5 / 34.9 | 52.1 | 28 / 29 | 71 % | 98 % |
| | **Viewpoint first person** | **174.2** | 9.4 | 18.3 | 106 | 97 % | 52 % |
| | ours | 243.8* / 392.7 | 27.0* / 8.0 | | 37* / 125 | 87 % | 90 % |
| | ours + Viewpoint first person | 167.4 | 8.6 | 11.7 | 116 | 98 % | 36 % |
| drive 120 km/h (42 s) | stock | 99.3 | 35.5 | 44.5 | 28 | 72 % | 79 % |
| | Viewpoint loaded, iso view | 113.1 | 17.5 | 24.6 | 57 | 80 % | 70 % |
| | **Viewpoint first person** | **217.6** | 8.8 | 15.4 | 114 | 97 % (408 W) | 48 % |
| | ours | 513.3 | 5.3 | 7.3 | 188 | 97 % | 57 % |
| | ours + Viewpoint first person | 210.7 | 7.2 | 15.3 | 139 | 98 % | 28 % |

\* that run had a ~10 s dip to 40-60 fps mid-route (690-780 fps before it); its repeat had none.

Stock-JVM spin runs are noisy: ZGC with a 3 GB heap stalls on allocation in every stock-based run (25-94 stalls per run,
none with ours), and the stock-like sides range from 52 to 105 fps. The iso-view gain over stock is inside that spread. In
iso view every Viewpoint hook checks `View.enabled` and passes the call through.

## Where first person spends its time

- **GPU-bound.** GPU 97-98 %, ~360-410 W, and the game thread waits 45-52 % of its time in `SpriteRenderer.waitForReadySlotToOpen`
  (frame hand-off). The mod's own per-pass GPU timers (`[Viewpoint] N fps | gpu ms ...` every ~10 s, drive):
  total 4.1-4.6 ms a frame, i.e. post+TAA 1.1, G-buffer 0.8-1.0, air (volumetric) 0.5-0.8, far world 0.3-0.5, shadows 0.4-0.5,
  sky+light 0.4, glass 0.25-0.3, bloom 0.1-0.15. 1,100-1,600 draw calls a frame (3.7k-7.8k mesh draws in them); the render
  thread's CPU work about 1 ms.
- **Game thread** ~4.4-4.9 ms a frame; the scene build (`IsoCell.render`, which the mod replaces) is 9-17 % of it. The stock
  iso work (translucent tiles 32-35 %, chunk bakes ~10 %, occluders, cutaways) is gone: on switch-on the mod frees the iso
  view's 351 chunk textures.
- **Switch-on hitch:** a 52-54 ms frame when O is pressed (chunk cache 32 ms for 35 builds, far world 10.7 ms), and one 201 ms
  main-thread frame in the drive's first 10 s.
- **Memory:** heap 1.9 -> 2.8 GB of the 3 GB cap over the 40 s drive; ~12 GB of the 4090's 24 GB VRAM in use (game included).
- **Gameplay changes in first person:** `MovingObjectUpdateScheduler` simulation levels thinned by distance (zombies
  without a target in their own tiers), and the game's view distance raised to the mod's draw distance. Weather FX and
  the iso renderer are skipped.
- Console errors: same count as stock (18 spin / 20 drive) on every side.

## With our mod

It runs: first person on, same 61 / 61 patch targets found, no extra errors. Being GPU-bound, the frame rate is Viewpoint's
own (167 vs 174, 211 vs 218: inside the run spread). Our game-thread work still shows: game thread 52 -> 36 % (spin) and
48 -> 28 % (drive), 1 %-low 106 -> 116 and 114 -> 139, spin p99.9 18.3 -> 11.7 ms. Viewpoint patches 22 methods our overrides
edit. Harness runs use `modCompat=report`; on a player's install `modCompat=auto` would switch off 17 keys (playerLosFast,
playerLosNative, zombieCullSortFast, zombieLodDynamic, statsNoBox, losLightPrefetch, vsyncLock, vsyncAdaptive, vblankLock,
reflexSleep, lampIdsApart; plus bootPump, fmodAsync, hotsaveWarmup, luaPrecompile, preloadAnimSets, resumeShot for
ZombieBuddy's own patches). These short runs did not test whether keeping those keys on is safe.

## Code overlap

`viewpoint.game.FrameCaps` (+ `Viewpoint_FrameCapOptions.lua`) follows our `pzopt.FrameCap` (2026-09-19): 13 of 17 identifiers
are the same (`FPS_TABLE`, `STOCK_MAX_FPS`, `MIN_FPS`, `MAX_FPS`, `MENU_SAME`, `MENU_UNCAPPED`, `MENU_CHOICES`,
`get/setMenuFramerateIndex`, `getMenuFramerateChoices`, `setGameFramerate`, `beforeLoadOptions`, `extraGameFps`, `uncappedNow`,
`lockNow`), and the fps table is ours plus 360. Nothing else matched by name. Our repo has no LICENSE file; what to do about
it is the maintainer's call.
