# Preview scenes: stock vs every optimization + enhancement (2026-09-26)

Measured while making the Workshop preview GIF candidates (`harness/preview-gifs.py`, output under
`docs/workshop/preview/`). The Riverside night wipe (`1b-night-wipe-riverside.gif`) became the Workshop preview on
2026-09-26 16:21 (uploaded unchanged apart from `preview.gif`; also `docs/workshop/images/00-showcase-thumbnail.gif`,
which `scripts/workshop.sh` stages as the preview). It carries no fps tags because of the first open item below.

Setup: desktop, RTX 4090, 5120x2160, NVIDIA GL, `--launcher direct`, `upscaler=off`, `hdrAuto=false`, overlay off,
`instrument=true`, build of 3dd6fba / 13bec5d plus the harness changes of that afternoon. **Stock** = `enabled=false
--option frameRate=244 --option uncappedFPS=false`: stock cannot run uncapped (`Core.loadOptions` turns
`uncappedFPS=true` into a 60 fps lock), so 244 is its fastest setting. **All on** = `uncappedFps=true` +
`ambientOcclusion`, `sunShadows`, `reflections`, `pixelLight` (their sub-keys at defaults). Numbers are the overlay's
route window (`analyze.py`); utilization is the overlay's (GPU busy from the GL timer query, game / render thread in %
of one core).

| Scene (runs under `harness/runs/`) | Side | fps | p50 / p99 / p99.9 ms | >33 ms | jitter ms | GPU % | game thread % |
|---|---|---|---|---|---|---|---|
| Rosewood house, 23:00, torch, zoom 1 (`wsgif-night-stock244-20260926-135215`, `wsgif-night-opt-20260926-134529`) | stock | 203 | 4.4 / 8.2 / 53.3 | 10 | 1.7 | 41 | 92 |
| | all on | **593** | 1.5 / 2.9 / 5.5 | 3 | 0.3 | 85 | 68 |
| Rosewood house, 16:30, zoom 1 (`wsgif-day-stock244-20260926-135355`, `wsgif-day-opt-20260926-134752`) | stock | 222 (near its cap) | 4.3 / 7.2 / 10.4 | 2 | 1.6 | 36 | 95 |
| | all on | **777** | 1.1 / 2.2 / 3.4 | 2 | 0.4 | 84 | 74 |
| Louisville preset walk, zoom 1.5, ~2,200 zombies (`wsgif-louwalk-stock244-20260926-135637`, `wsgif-louwalk-opt-20260926-135513`) | stock | 38 | 22.7 / 78.6 / 104.8 | 192 | 11.2 | 27 | 98 |
| | all on | **78** | 11.0 / 39.9 / 67.9 | 40 | 3.8 | 37 | 96 |
| Rosewood church lot, thunderstorm, 23:00, torch, zoom 1.25 (`wsgif-storm2-opt-20260926-135812`) | all on | 445 | 2.1 / 5.0 / 8.4 | 0 | 0.7 | 92 | 81 |
| Riverside pier, horde-shoot bench, 150 zombies, 16:30 (`wsgif-horde-stock244-20260926-140515`, `wsgif-horde-opt-20260926-140240`) | stock | 162 | 5.9 / 12.8 / 19.5 | 1 | 1.2 | 54 | 99 |
| | all on | 181 | 4.9 / 15.7 / 24.4 | 4 | 1.0 | **92** | 79 |
| Same, 500 zombies (`wsgif-horde500-opt-20260926-141726`; the stock run never started its route) | all on | 114 | 9.3 / 27.9 / 50.7 | 23 | 1.3 | **90** | 84 |
| Riverside Sunset deck edge 6402,5189, 23:00, torch, `lights=on`, zoom 1 (`wsgif-rsnight6-stock244-20260926-153747`, `wsgif-rsnight6-opt-20260926-153951`) | stock | 232 (near its cap) | 4.0 / 7.5 / 26.5 | 2 | 1.6 | 64 | 94 |
| | all on | **226** | 4.2 / 6.6 / 11.8 | 3 | 0.9 | **93** | 40 |
| Riverside dock 6404,5190, same scene (`wsgif-rsnight4-stock244-20260926-151305`, `wsgif-rsnight4-opt-20260926-151419`) | stock | 233 | 4.1 / 7.5 / 27.3 | 2 | 1.5 | 62 | 96 |
| | all on | 249 | 3.8 / 6.4 / 11.7 | 2 | 0.9 | **92** | 40 |

Louisville runs were checked per the rule (settle-window frames: no pale roof patches or speckle; strong marks 5,830
stock / 0 all on). Superseded runs (`wsgif-night-stock`, `wsgif-day-stock` at stock's 60 fps lock, `wsgif-lou-*` inside
the theatre, `wsgif-storm-opt` in daylight in a car, `wsgif-rsnight`..`rsnight3`, `rsnight5` probe) are not used.

## Open: track these

1. **River view at night with every enhancement on is GPU-bound and slower than stock.** Riverside deck, 226 fps all on
   (GPU 93 %, game thread 40 %) vs stock 232 at its cap; the tail is still better (p99.9 11.8 vs 26.5 ms). Stock's own
   water pass is ~1.4 ms and the whole river frame ~4.3 ms of GPU (findings-reflections), and reflections measured
   +11..15 us there, so the extra GPU time is probably another key under 21 lit lamps + the torch (per-pixel lighting's
   point lights, sun shadows' torch / lamp capsules, AO) — not yet attributed. Next: the rsnight6 shot args with one key
   off per run (or `gpuSections=true` and the keys' dev alternators: `devSunAlternate`, `devSsrAlternate`,
   `devPplAlternate`), same spot, `--flag lights=on`; check whether DLSS at its defaults brings it above stock.
2. **Horde scenes: the enhancements eat the headroom.** Horde-shoot pier 162 -> 181 fps with all on (GPU 92 %, 1.12x);
   500 zombies 114 fps at GPU 90 %. Character sun shadows scale with the crowd (capsule pass), candidate first.
   Compare with the enhancements off to size their share.
3. **Stock can only be measured capped** (244); in scenes where stock sits near the cap (day house 222, Riverside 232)
   the stock-vs-optimized ratio understates the gain. For published numbers, say "stock at its 244 cap".

Rigs added for this: `--flag ground_map=N` (level-0 squares round the player at route start) +
`harness/stand-spot-judge.py` (Jev: on dry floor / reflection lands in water / grid power); `lights=on` now also moves
the sandbox `ElecShutModifier` past the world's age, without which switches and street lamps stay dark on the bench save
(`hasGridPower()` = world age <= ElecShutModifier).
