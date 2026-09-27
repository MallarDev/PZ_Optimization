package pzopt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.LightingJNI;

/**
 * The player's line-of-sight pass without its lazy lighting reads ({@code losLightPrefetch}, 2026-09-27).
 *
 * <p>{@code IsoPlayer.updateLOS} asks every moving object's square whether the player could see it; the first read of a
 * square in a lighting pass is {@code JNILighting.update}, two JNI calls and the square's light fields rewritten. The
 * pre-pass lighting drain ({@code lightingReadParallel}) refreshes the dirty on-screen chunk levels, but a horde spreads
 * over squares it did not reach (off-screen chunk levels, levels not dirty for the drain), and the walk pays their refresh
 * one by one on the game thread: 1.6-2 % of it on the Louisville horde, then the scene cull and the zombies' own
 * visibility tests read the same squares again. Right before the walk the stale squares of the cell's moving objects are
 * grouped by chunk level and refreshed on the frame workers through {@link LightingBatch}: one task per chunk level (the
 * per-level bookkeeping the refresh does stays with one task), the room / meta hooks recorded and applied by the game
 * thread after the join, the lazily created per-chunk structures touched on the game thread first. The walk then finds
 * every square fresh (the natives' answers do not change within a lighting pass, so the values are the ones the walk
 * would have read).
 */
public final class LosPrefetch {
   private LosPrefetch() {
   }

   private static int frame;
   private static IsoChunk[] taskChunk = new IsoChunk[256];
   private static int[] taskLevel = new int[256];
   private static final ArrayList<ArrayList<IsoGridSquare>> TASK_SQUARES = new ArrayList<>();
   private static int tasks;
   private static int player;
   public static long runs, squares, taskCount, small;

   private static boolean enabled() {
      return Config.LOS_LIGHT_PREFETCH && LightingBatch.ENABLED && Overrides.enabled() && GtAb.on(GtAb.LOS_PREFETCH);
   }

   /** Game thread, the top of the player's line-of-sight walk. */
   public static void run(int playerIndex, Set<IsoMovingObject> objects) {
      if (!enabled()) {
         return;
      }
      frame++;
      tasks = 0;
      player = playerIndex;
      int found = 0;
      for (IsoMovingObject o : objects) {
         IsoGridSquare sq = o.getCurrentSquare();
         if (sq == null || !(sq.lighting[playerIndex] instanceof LightingJNI.JNILighting jl) || !jl.pzoptStale()) {
            continue;
         }
         IsoChunk c = sq.chunk;
         int li = sq.z + 32;
         if (li < 0 || li >= 64) {
            continue;
         }
         if (c.pzoptLosFrame != frame || c.pzoptLosTask == null) {
            c.pzoptLosFrame = frame;
            if (c.pzoptLosTask == null) {
               c.pzoptLosTask = new int[64];
            }
            Arrays.fill(c.pzoptLosTask, -1);
         }
         int t = c.pzoptLosTask[li];
         if (t < 0) {
            t = tasks++;
            c.pzoptLosTask[li] = t;
            if (t == taskChunk.length) {
               taskChunk = Arrays.copyOf(taskChunk, t * 2);
               taskLevel = Arrays.copyOf(taskLevel, t * 2);
            }
            taskChunk[t] = c;
            taskLevel[t] = sq.z;
            if (t == TASK_SQUARES.size()) {
               TASK_SQUARES.add(new ArrayList<>());
            }
            TASK_SQUARES.get(t).clear();
            c.getRenderLevels(playerIndex); // created here, never by a worker
            c.getCutawayDataForLevel(sq.z);
         }
         TASK_SQUARES.get(t).add(sq);
         found++;
      }
      runs++;
      if (tasks < 2) {
         small++;
         clear();
         return; // one level or none: the walk's own lazy reads are cheaper than a batch
      }
      squares += found;
      taskCount += tasks;
      final int n = tasks;
      final int p = playerIndex;
      final LightingBatch.Effects[] effects = new LightingBatch.Effects[n];
      for (int i = 0; i < n; i++) {
         effects[i] = LightingBatch.effectsFor(i, n, p);
      }
      Throwable t = FrameBatch.run(n, i -> LightingBatch.withEffects(effects[i], () -> {
         ArrayList<IsoGridSquare> list = TASK_SQUARES.get(i);
         for (int k = 0; k < list.size(); k++) {
            if (list.get(k).lighting[p] instanceof LightingJNI.JNILighting jl) {
               jl.pzoptRefresh();
            }
         }
      }));
      LightingBatch.applyAll(n);
      if (t != null) {
         Log.warn("losLightPrefetch: a refresh failed on a worker: " + t);
      }
      clear();
   }

   private static void clear() {
      for (int i = 0; i < tasks; i++) {
         taskChunk[i] = null;
         TASK_SQUARES.get(i).clear();
      }
      tasks = 0;
   }

   public static String describe() {
      return "losPrefetch runs=" + runs + " squares=" + squares + " tasks=" + taskCount + " small=" + small;
   }
}
