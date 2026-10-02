# Vertical seams where walkers push foliage (2026-10-02)

Report (maintainer, save `Sandbox/2026-10-02_10-30-09`, a chicken yard at 10742,6978, foliage sway on): "whenever an entity
walks through foliage, there are visual artifacts vertically".

## Cause

`swayPush` (foliage sway, 2026-09-27) bends the plants round a character or animal moving through grass or a bush: a
displacement along screen x (u = x - y) added to the wind's, read by the chunk composite through its inverse (two fixed-point
steps, s = p - D(p - D(p))). The bend was `sign(du) * (1 - |du| / r)^2 * fv * strength`: full left on one side of the
walker's column, full right on the other. A displacement that jumps across a column has no inverse there. Replaying the
shader's two steps offline (32 px amplitude x 0.9, r 0.9 squares, 64 px per u), the texel a screen pixel shows jumps by
11-16 px between two neighbouring pixels at every plant weight (1, 0.5, 0.25): a strip of the plant vanishes along a
vertical line running from the walker's feet to three squares above it (`fv`), and it travels with the walker. A player's
body covers its own column; a hen (about 20 px tall at zoom 1 on 5K) leaves the seam in view.

## Fix

The bend is now an odd, continuous function of the offset, zero on the walker's column: `2.5 t (1 - t^2)^2` with
t = du / (1.5 r). Plants still part (peak 0.72 of the old amplitude, 21 px at full weight, at 0.45 of the reach) and the
inverse stays continuous: the largest texel step per screen pixel is 2.09 at full weight (a stretch, no skipped texture)
against 228 before. `devSwayPushOld=true` restores the old kernel for same-build A/Bs.

## Measurements (desktop, 5120x2160, the maintainer's save)

- The kernel replay above is the proof of the seam. In-game the push moves the yard's plants only a few px, so the seam is
  narrow there and hard to capture.
- Hens rig (12 hens via `--flag animals=12:hen`, player still, `wind=0 weather=clear`, `cloudShadows=false`, 1:1 24 fps
  `devCapture` of the screen centre; `harness/foliage-tears.py --movers`, the overlay masked): fix vs `swayPush=false`
  is parity for Jev (0.99; walkers with a seam over 2x the grass beside them: 0.2 % vs 0.4 %). The old kernel's run read
  15 % and Jev called tears (0.92), but every one of those samples was one hen beside a scenery edge that scores the same
  in the hen-free median frame: a confound, not evidence. Check any hit against the median frame.
- `--prop devSwayGainPct=0` zeroes the wind's displacement and keeps the push: with it, frame minus the run's median shows
  only what walkers do to the plants (runs `fol-iso-old` / `fol-iso-new`).
- A Jev circle walk (`explore=circle director=jev`) through the tall grass shows no seam: the player's body hides it.

Runs (worktree `~/pzopt-wt/foliage-push/harness/runs/`): `fol-hens2-{old,new,off}`, `fol-iso-{old,new}`,
`fol-orbit2-{old,new}`; main checkout: `fol-push-on*`, `fol-hens-*`.
