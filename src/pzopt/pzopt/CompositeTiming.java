package pzopt;

/**
 * devCompositeTiming (2026-10-09, the 300 fps loop): render-thread time of each step of a chunk composite draw's start
 * (ChunkRenderShader.startRenderThread), summed and logged every 10 s as us per draw. The driver work of the GL calls is
 * what dominates on the render thread in a native Wayland window, so this is where a composite draw's cost goes.
 */
public final class CompositeTiming {
   private CompositeTiming() {
   }

   public static final boolean ON = Config.DEV_COMPOSITE_TIMING;
   static final String[] NAMES = {"stock", "pixelLight", "ssr", "cloud", "relief", "godRays", "sway", "spriteFilter"};
   private static final long[] NS = new long[NAMES.length];
   private static long draws, lastLog, t;

   public static void begin() {
      t = System.nanoTime();
   }

   public static void step(int i) {
      long now = System.nanoTime();
      NS[i] += now - t;
      t = now;
   }

   public static void end() {
      draws++;
      long now = System.nanoTime();
      if (now - lastLog > 10_000_000_000L) {
         if (lastLog != 0L && draws > 0) {
            StringBuilder b = new StringBuilder("composite timing: " + draws + " draws, us per draw:");
            long sum = 0L;
            for (int i = 0; i < NAMES.length; i++) {
               b.append(' ').append(NAMES[i]).append('=').append(String.format(java.util.Locale.ROOT, "%.2f", NS[i] / 1000.0 / draws));
               sum += NS[i];
               NS[i] = 0L;
            }
            b.append(String.format(java.util.Locale.ROOT, " total=%.2f", sum / 1000.0 / draws));
            Log.info(b.toString());
         }
         draws = 0L;
         lastLog = now;
      }
   }
}
