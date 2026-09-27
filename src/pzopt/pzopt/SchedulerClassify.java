package pzopt;

import java.util.Set;
import zombie.MovingObjectUpdateScheduler;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * The update scheduler's per-object simulation level on the frame workers ({@code schedulerClassifyParallel},
 * 2026-09-27).
 *
 * <p>{@code MovingObjectUpdateScheduler.startFrame} walks every moving object of the cell (~2,500 on the Louisville
 * horde) and asks each for its simulation level: its minimum (dead, ragdolling, falling, the state machine's states), its
 * distance, level and alpha against every player, the frame rate. That is 3.5 % of the game thread there, and every input
 * is a read of state no one writes during the walk. So the walk is split: the game thread takes the set's objects in its
 * iteration order and does the loop's one write first ({@code setCurrentSquareFromPosition} for an object without a
 * square: the classification never reads another object's square), the frame workers classify, and the game thread
 * fills the buckets and the separation batch in that same order. A character whose animation player would be replaced
 * by the body-model check inside {@code getAnimationPlayer} (a write) is left to the game thread, as is everything after
 * a failed task.
 */
public final class SchedulerClassify {
   private SchedulerClassify() {
   }

   private static final int PER_TASK = 64;
   private static IsoMovingObject[] objs = new IsoMovingObject[4096];
   private static UpdateSchedulerSimulationLevel[] levels = new UpdateSchedulerSimulationLevel[4096];
   private static int n;
   private static float fps;
   private static MovingObjectUpdateScheduler scheduler;
   private static boolean failed;
   public static long frames, objects, serialObjects, checks, mismatches;
   public static int updatedThisFrame; // objects the buckets update this frame (the frame-mod test of the bucket), for pzopt-gtab.out

   public static boolean enabled() {
      return Config.SCHEDULER_CLASSIFY_PARALLEL && !failed && Overrides.enabled() && GtAb.on(GtAb.SCHED);
   }

   /** Game thread, startFrame after the buckets were cleared: the loop over the cell's objects. */
   public static void run(MovingObjectUpdateScheduler s, Set<IsoMovingObject> set, float averageFps) {
      int count = set.size();
      if (objs.length < count) {
         objs = new IsoMovingObject[count + 1024];
         levels = new UpdateSchedulerSimulationLevel[count + 1024];
      }
      n = 0;
      for (IsoMovingObject o : set) {
         if (o.getCurrentSquare() == null) {
            o.setCurrentSquareFromPosition();
         }
         objs[n++] = o;
      }
      scheduler = s;
      fps = averageFps;
      frames++;
      objects += n;
      if (n >= PER_TASK * 2) {
         Throwable t = FrameBatch.run((n + PER_TASK - 1) / PER_TASK, SchedulerClassify::task);
         if (t != null) {
            failed = true;
            Log.warn("schedulerClassifyParallel: a task failed, the game thread classifies from now on: " + t);
            java.util.Arrays.fill(levels, 0, n, null);
         }
      } else {
         java.util.Arrays.fill(levels, 0, n, null);
      }
      if (Config.DEV_SCHED_CHECK) {
         for (int i = 0; i < n; i++) {
            if (levels[i] != null) {
               checks++;
               UpdateSchedulerSimulationLevel serial = s.pzoptClassifySerial(objs[i], averageFps);
               if (serial != levels[i]) {
                  mismatches++;
                  if (mismatches <= 20) {
                     Log.warn("schedulerClassify mismatch: " + objs[i].getClass().getSimpleName() + " id " + objs[i].getID() + " worker " + levels[i] + " game thread " + serial);
                  }
               }
            }
         }
      }
      updatedThisFrame = 0;
      long frameCounter = s.getFrameCounter();
      for (int i = 0; i < n; i++) {
         IsoMovingObject o = objs[i];
         UpdateSchedulerSimulationLevel sim = levels[i];
         if (sim == null) {
            serialObjects++;
            sim = s.pzoptClassifySerial(o, averageFps);
         }
         s.pzoptAdd(o, sim);
         if (o.getID() % sim.getFrameMod() == (int)(frameCounter % (long)sim.getFrameMod())) {
            updatedThisFrame++;
         }
         objs[i] = null;
         levels[i] = null;
      }
   }

   private static void task(int t) {
      int from = t * PER_TASK, to = Math.min(n, from + PER_TASK);
      for (int i = from; i < to; i++) {
         levels[i] = scheduler.pzoptClassify(objs[i], fps);
      }
   }

   public static String describe() {
      return "schedulerClassify frames=" + frames + " objects=" + objects + " serial=" + serialObjects + (Config.DEV_SCHED_CHECK ? " checks=" + checks + " mismatches=" + mismatches : "") + (failed ? " FAILED" : "");
   }
}
