package pzopt;

/**
 * Dev rig {@code devVisBlinkTrace} (2026-10-03, the tile flicker under a carried lantern): every change of a square's native
 * visibility bits (seen 1, can see 2, could see 4) as LightingJNI reads it, and the changes that undo the square's previous
 * change within 3 frames (a one-to-three-frame blink). One log line per frame with any blink: epoch ms, frame, blinks (all
 * bits / the can-see bit), changes, and the first blinking square, for lining the capture's flicker frames up with them.
 * Reads may come from the lighting workers (lightingReadParallel): synchronized, dev only.
 */
public final class VisBlink {
   private VisBlink() {
   }

   public static final boolean ON = Config.DEV_VIS_BLINK_TRACE;
   private static final java.util.HashMap<Long, long[]> LAST = new java.util.HashMap<>(); // square -> {previous vis, frame of its last change}
   private static int changes, blinks, canSeeBlinks;
   private static String first;
   private static long total, totalCanSee;

   public static synchronized void change(zombie.iso.IsoGridSquare sq, byte was, byte now) {
      int frame = zombie.iso.IsoCamera.frameState.frameCount;
      long key = ((long)sq.x << 34) ^ ((long)sq.y << 8) ^ (sq.z + 64);
      long[] e = LAST.get(key);
      changes++;
      if (e != null && e[0] == now && frame - e[1] <= 3) {
         blinks++;
         if (((was ^ now) & 2) != 0) {
            canSeeBlinks++;
         }
         if (first == null) {
            first = sq.x + "," + sq.y + "," + sq.z + " " + was + "->" + now + " after " + (frame - e[1]) + " frames";
         }
      }
      if (e == null) {
         if (LAST.size() > 200_000) {
            LAST.clear();
         }
         LAST.put(key, new long[] {was, frame});
      } else {
         e[0] = was;
         e[1] = frame;
      }
   }

   public static synchronized void frame() {
      if (!ON) {
         return;
      }
      if (blinks > 0) {
         total += blinks;
         totalCanSee += canSeeBlinks;
         Log.info("vis blink: epoch_ms=" + System.currentTimeMillis() + " frame=" + zombie.iso.IsoCamera.frameState.frameCount + " blinks=" + blinks
            + " canSee=" + canSeeBlinks + " changes=" + changes + " first=" + first + " total=" + total + "/" + totalCanSee);
      }
      changes = blinks = canSeeBlinks = 0;
      first = null;
   }
}
