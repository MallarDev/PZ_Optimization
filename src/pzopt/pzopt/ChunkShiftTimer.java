package pzopt;

/**
 * Louisville 120 item 3 (dev, only while a devGtAlternate alternation runs): the phases of one chunk-grid shift
 * (IsoChunkMap.LoadUp / Down / Left / Right), logged when the shift took over 1 ms.
 */
public final class ChunkShiftTimer {
   private ChunkShiftTimer() {
   }

   private static final String[] PHASES = {"rowRemoval", "groundScroll", "requests", "swap", "cellCache", "lightingScroll"};
   private static final long[] T = new long[PHASES.length + 1];

   public static void start() {
      if (GtAb.TIMING) {
         T[0] = System.nanoTime();
      }
   }

   public static void mark(int phase) {
      if (GtAb.TIMING) {
         T[phase + 1] = System.nanoTime();
      }
   }

   public static void done(String dir) {
      if (!GtAb.TIMING || T[0] == 0L) {
         return;
      }
      long total = T[PHASES.length] - T[0];
      if (total > 1_000_000L) {
         StringBuilder b = new StringBuilder("chunk shift ").append(dir).append(String.format(": %.2f ms (", total / 1e6));
         for (int i = 0; i < PHASES.length; i++) {
            b.append(i == 0 ? "" : ", ").append(PHASES[i]).append(String.format(" %.2f", (T[i + 1] - T[i]) / 1e6));
         }
         Log.info(b.append(')').toString());
      }
      T[0] = 0L;
   }
}
