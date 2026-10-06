package pzopt;

import java.util.Arrays;
import zombie.ai.states.ClimbThroughWindowState;
import zombie.characters.IsoZombie;
import zombie.iso.IsoWorld;

/**
 * Parallel read-only half of {@code IsoWorld.sceneCullZombies} ({@code sceneCullParallel}).
 *
 * <p>The workers answer only the stock "should this zombie enter zombieWithModel?" predicate. The game thread still
 * builds both lists in the original zombie-list order, runs the relevance sort, and performs every model, scene-cull
 * and blending mutation. That keeps the experiment on the read-only side of the render pipeline.
 *
 * <p>A zombie climbing through a window stays serial: the predicate may call {@code couldSeeHeadSquare}, whose head
 * square can be derived from animation bones. Everything else on this path is position, camera, alpha, lighting-bit
 * and state reads after the simulation has finished for the frame.
 */
public final class SceneCullBatch {
   private SceneCullBatch() {
   }

   private static final int PER_TASK = 64;
   private static final byte PENDING = -1;
   private static final byte WITHOUT_MODEL = 0;
   private static final byte WITH_MODEL = 1;
   private static final byte SERIAL = 2;

   private static IsoZombie[] zombies = new IsoZombie[4096];
   private static byte[] result = new byte[4096];
   private static int count;
   private static IsoWorld world;
   private static volatile boolean failed;

   public static long frames;
   public static long objects;
   public static long serialObjects;
   public static long checks;
   public static long mismatches;
   public static long failures;

   /** Whether this frame is large enough and the experimental key is enabled. */
   public static boolean active(int n) {
      return n >= PER_TASK * 2
         && Config.SCENE_CULL_PARALLEL
         && Overrides.enabled()
         && GtAb.on(GtAb.SCENE_CULL)
         && !failed;
   }

   /** Game thread: allocate the reusable slots for this frame. */
   public static void begin(IsoWorld owner, int n) {
      if (zombies.length < n) {
         int size = n + 1024;
         zombies = new IsoZombie[size];
         result = new byte[size];
      }
      world = owner;
      count = n;
      frames++;
      objects += n;
   }

   /** Game thread: copy one stable zombie reference into the batch. */
   public static void set(int index, IsoZombie zombie) {
      zombies[index] = zombie;
      result[index] = PENDING;
   }

   /** Run the read-only classifications. False means the caller must use the stock path for this frame. */
   public static boolean run() {
      Throwable t = FrameBatch.run((count + PER_TASK - 1) / PER_TASK, SceneCullBatch::task);
      if (t != null) {
         failed = true;
         failures++;
         Log.warn("sceneCullParallel: worker task failed; using the game thread from now on: " + t);
         return false;
      }
      return true;
   }

   private static void task(int task) {
      int from = task * PER_TASK;
      int to = Math.min(count, from + PER_TASK);
      IsoWorld owner = world;
      for (int i = from; i < to; i++) {
         IsoZombie zombie = zombies[i];
         if (zombie.isCurrentState(ClimbThroughWindowState.instance())) {
            result[i] = SERIAL;
         } else {
            result[i] = owner.pzoptCullClassify(zombie) ? WITH_MODEL : WITHOUT_MODEL;
         }
      }
   }

   /**
    * Game thread: consume one answer. The dev rig recomputes every worker result serially and switches the session back
    * to stock after the first disagreement.
    */
   public static boolean result(int index) {
      IsoZombie zombie = zombies[index];
      byte worker = result[index];
      if (worker == SERIAL) {
         serialObjects++;
         return world.pzoptCullClassify(zombie);
      }

      boolean withModel = worker == WITH_MODEL;
      if (Config.DEV_SCENE_CULL_CHECK) {
         checks++;
         boolean serial = world.pzoptCullClassify(zombie);
         if (serial != withModel) {
            mismatches++;
            failed = true;
            if (mismatches <= 20) {
               Log.warn("sceneCullParallel mismatch: zombie id " + zombie.getID() + " worker=" + withModel + " gameThread=" + serial);
            }
            withModel = serial;
         }
      }
      return withModel;
   }

   /** Game thread: do not retain the world's zombies between frames. */
   public static void finish() {
      Arrays.fill(zombies, 0, count, null);
      world = null;
      count = 0;
   }

   public static String describe() {
      return "sceneCull frames=" + frames + " objects=" + objects + " serial=" + serialObjects
         + (Config.DEV_SCENE_CULL_CHECK ? " checks=" + checks + " mismatches=" + mismatches : "")
         + " failures=" + failures + (failed ? " FAILED" : "");
   }
}
