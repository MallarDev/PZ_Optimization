# Characters' sun shadows cut flat at the head (2026-10-02)

Player question: "With Shadows ON, my character's shadow (and the zombies') has the top of the head cut off, like a flat
cut. Is that supposed to happen, or a setting?" Not intended, and no setting fixed it: a bug in the caster pass.

## Cause

`pzopt.CapsuleShadow` draws each caster's sun shadow inside one screen quad (`VERT`): the box of the bounding capsule's two
axis end points and their shadows on the floor, padded by the capsule's radius. The shadow of the capsule's round top
reaches `radius / sin(sun elevation)` past the shadow of its top axis point along the ground, the pad only `radius`. Below
roughly 45 deg of sun the head's shadow ran past the quad's far edge and was clipped on that straight edge (the quad is
boxed along the shadow's screen direction, so the cut runs at a slant across the head). The shadow map (`ShadowAtlas`)
held the whole head; only the quad cut it. High sun: the head's shadow lies under the body, nothing to cut.

## Fix

The quad's far end comes from the shadow of the capsule's top (both end points raised by the radius) instead of its
axis end. The quad is a little longer under a low sun; the extra fragments leave at the bounding-capsule test (one depth
fetch and one capsule test each). Dev A/B: `--prop devShadowTipTogglePeriod=MS` switches between the old end and the new
one in the same run (`capsule shadows: dev tip toggle old|fixed at epoch_ms` lines).

## Evidence (desktop, runs headcut-*)

- headcut-t18.5 (18:30, sun 25 deg, player + 6 zombies on the church lot, zoom 0.5, 1:1 capture): the player's shadow ends
  on a straight slanted edge in the old periods, in a round head in the fixed ones. `harness/shadow-tip-judge.py` (tip
  edge hardness over the sides'; box 560,620,1620,1000): old median 0.94, p90 1.66, 58 % of tips cut; fixed median 0.29,
  p90 0.44, 0.4 % cut. Jev: before_cut 0.96, after_round 0.97, verdict fixed 0.99.
- headcut-ab1 (16:30): the head's shadow lies inside the old quad at that sun; old and fixed look the same.
- headcut-t12 (noon): the player's shadow changes across a switch no more than from frame to frame (mean abs diff 0.43
  vs 0.52): unaffected.
- Video: `docs/media/shadow-head-cut-before-after.mp4` (`harness/stitch-shadow-head.py`).
