package pzopt;

import java.util.Set;
import zombie.MovingObjectUpdateScheduler;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoMovingObject;

/**
 * {@code animalLosSnapshot} (2026-10-05, Louisville 120 fps pass): what every animal's {@code updateLOS} walk reads, taken
 * once per scheduler frame. Stock walks the cell's whole object set (a HashSet of ~2,400 zombies in the Louisville horde)
 * once per animal per frame, touching every zombie object; that was 10 % of the game thread with entityUpdateParallel and
 * tiered updates on. The walk only acts on zombies (the animalLosFast fold: a tick, or a spotted call within 10 squares)
 * and on non-animal players; every other object only had its distance computed and dropped. So the set is walked once, in
 * its own iteration order, into primitive arrays: per kept entry its kind, and for a zombie its position and whether the
 * stock filters (not reanimated-for-grapple-only, a current square) pass. Each animal then scans the arrays.
 *
 * Difference from stock (an intended edit, documented in docs/override-edits.md): the zombies' positions and filters are
 * the ones of the frame's first animal update instead of each animal's own moment, i.e. at most one frame's update order
 * apart; players are read live, as stock does. The snapshot is rebuilt when the scheduler frame or the set's size changes.
 */
public final class AnimalLosSnapshot {
   private AnimalLosSnapshot() {
   }

   public static final byte ZOMBIE = 1; // a zombie passing the grapple / square filters
   public static final byte PLAYER = 2; // a non-animal player: the stock per-object path, live
   public static final byte ANIMAL = 3; // an animal: only the self entry matters (spottedList.add(this))

   public static int n;
   public static byte[] kind = new byte[4096];
   public static float[] x = new float[4096];
   public static float[] y = new float[4096];
   public static float[] z = new float[4096];
   public static IsoMovingObject[] obj = new IsoMovingObject[4096];
   private static long frame = Long.MIN_VALUE;
   private static int setSize = -1;
   public static long builds;
   public static long uses;

   public static boolean enabled() {
      return Config.ANIMAL_LOS_SNAPSHOT && Overrides.enabled() && GtAb.on(GtAb.ANIMAL_SNAP);
   }

   /** Game thread, from IsoAnimal.updateLOS: the snapshot for this frame, built on the first call. */
   public static void ensure(Set<IsoMovingObject> set) {
      long f = MovingObjectUpdateScheduler.instance.getFrameCounter();
      int size = set.size();
      if (f == frame && size == setSize) {
         uses++;
         return;
      }

      frame = f;
      setSize = size;
      builds++;
      if (obj.length < size) {
         int cap = Integer.highestOneBit(size) << 1;
         kind = new byte[cap];
         x = new float[cap];
         y = new float[cap];
         z = new float[cap];
         obj = new IsoMovingObject[cap];
      }

      int k = 0;
      for (IsoMovingObject o : set) {
         if (o instanceof IsoZombie zombie) {
            if (zombie.isReanimatedForGrappleOnly() || zombie.getCurrentSquare() == null) {
               continue;
            }

            kind[k] = ZOMBIE;
            x[k] = zombie.getX();
            y[k] = zombie.getY();
            z[k] = zombie.getZ();
            obj[k++] = zombie;
         } else if (o instanceof IsoAnimal) {
            kind[k] = ANIMAL;
            obj[k++] = o;
         } else if (o instanceof IsoPlayer) {
            kind[k] = PLAYER;
            obj[k++] = o;
         }
      }

      for (int i = k; i < n; i++) {
         obj[i] = null; // nothing held past this frame's entries
      }

      n = k;
   }
}
