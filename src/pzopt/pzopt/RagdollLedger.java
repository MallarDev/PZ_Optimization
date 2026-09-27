package pzopt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import zombie.characters.IsoGameCharacter;
import zombie.core.physics.Bullet;
import zombie.core.physics.RagdollController;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * Every ragdoll controller an {@link AnimationPlayer} creates, from creation to release (2026-09-27, the quit crash
 * {@code Ragdoll::deleteRigidBodies} in {@code IngameState.exit → WorldSimulation.destroy → Bullet.destroyWorld}).
 * libPZBullet's world destructor deletes the dynamics world before the ragdolls still in its id → ragdoll map, so any
 * ragdoll left added at quit removes its bodies from a destroyed world and jumps through a null vtable slot. The exit
 * path normally removes them all (chunk.removeFromWorld → character.removeFromWorld → releaseRagdollController); the
 * ledger names the controllers that escaped it ({@link #beforeDestroy}: owner player, character, ids, where the character
 * is) and, with {@code ragdollQuitSweep}, removes them from the native world before it is destroyed. It also counts
 * controllers released while still added (the native entry would outlive them) and releases off the game thread.
 *
 * <p>What leaked them (2026-09-27, {@code devRagdollLedger=true}): stock itself. When a ragdolled zombie's simulation settles
 * its controller is released; the action state then moves to onground, ZombieOnGroundState.enter calls die() and the zombie
 * turns into its IsoDeadBody (removed from the world), and the same postUpdateAnimating's model update still finds the
 * ragdoll track and builds a new controller for the corpse, owned by nobody (stock 15 of 40 controllers in one horde-shoot
 * run, ours 47 of 109: more kills a second). {@code ragdollCorpseGuard} stops that in AnimationPlayer.initRagdollController.
 */
public final class RagdollLedger {
   private RagdollLedger() {
   }

   private static final class Entry {
      AnimationPlayer owner;
      IsoGameCharacter chr;
      int id;
      String thread;
      long createdNs;
      String lost;
      Throwable squareExit;
   }

   private static final IdentityHashMap<RagdollController, Entry> LIVE = new IdentityHashMap<>();
   private static int createdOffSquare;
   private static int created, released, releasedAdded, releasedOffThread;
   private static int stacksLogged;
   private static final Object[] RECENT_CHR = new Object[64];
   private static final Throwable[] RECENT_STACK = new Throwable[64];
   private static int recentAt;
   private static Field addedField;
   private static boolean fieldFailed;

   private static final boolean DEV = Config.DEV_RAGDOLL_LEDGER;
   private static final boolean ON = DEV || Config.RAGDOLL_QUIT_SWEEP && Overrides.enabled();

   /** Called by AnimationPlayer.initRagdollController right after the controller is bound to its character. */
   public static void created(AnimationPlayer owner, RagdollController rc) {
      if (!ON) {
         return;
      }
      Entry e = new Entry();
      e.owner = owner;
      e.chr = owner.getIsoGameCharacter();
      e.id = e.chr == null ? Integer.MIN_VALUE : e.chr.getID();
      e.thread = Thread.currentThread().getName();
      e.createdNs = System.nanoTime();
      synchronized (LIVE) {
         LIVE.put(rc, e);
         created++;
      }
      zombie.iso.IsoGridSquare sq = e.chr == null ? null : e.chr.getCurrentSquare();
      if (DEV && e.chr != null && (sq == null || !sq.getMovingObjects().contains(e.chr))) {
         synchronized (LIVE) {
            createdOffSquare++;
            if (stacksLogged++ < 6) {
               Log.warn("ragdoll ledger: id=" + e.id + " controller created for a character on no square (dead=" + e.chr.isDead()
                     + "):" + stack(new Throwable()) + "\n  it left its square at:" + recentExit(e.chr));
            }
         }
      }
   }

   /** Called by AnimationPlayer.releaseRagdollController after the pool release (which removes it from the world). */
   public static void released(RagdollController rc) {
      if (!ON) {
         return;
      }
      Entry e;
      synchronized (LIVE) {
         e = LIVE.remove(rc);
         released++;
         if (!"MainThread".equals(Thread.currentThread().getName())) {
            releasedOffThread++;
         }
      }
      if (isAdded(rc)) {
         synchronized (LIVE) {
            releasedAdded++;
         }
         Log.warn("ragdoll ledger: controller released while still in the Bullet world, id=" + (e == null ? "?" : e.id)
               + " thread=" + Thread.currentThread().getName());
      }
   }

   private static boolean isAdded(RagdollController rc) {
      if (fieldFailed) {
         return false;
      }
      try {
         if (addedField == null) {
            Field f = RagdollController.class.getDeclaredField("addedToWorld");
            f.setAccessible(true);
            addedField = f;
         }
         return addedField.getBoolean(rc);
      } catch (ReflectiveOperationException | RuntimeException ex) {
         fieldFailed = true;
         Log.warn("ragdoll ledger: cannot read RagdollController.addedToWorld: " + ex);
         return false;
      }
   }

   private static void clearAdded(RagdollController rc) {
      try {
         if (addedField != null) {
            addedField.setBoolean(rc, false);
         }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
      }
   }

   /** IsoMovingObject.removeFromSquare: a character leaving its square while its ragdoll is live (dev evidence). */
   public static void leftSquare(Object o) {
      if (!DEV || !(o instanceof IsoGameCharacter chr0)) {
         return;
      }
      if (chr0.isDead()) {
         deathShape(chr0);
         synchronized (LIVE) {
            RECENT_CHR[recentAt] = chr0;
            RECENT_STACK[recentAt] = new Throwable();
            recentAt = (recentAt + 1) % RECENT_CHR.length;
         }
      }
      synchronized (LIVE) {
         if (LIVE.isEmpty()) {
            return;
         }
         for (Entry e : LIVE.values()) {
            if (e.chr == o && e.squareExit == null) {
               e.squareExit = new Throwable("left its square with a live ragdoll");
               if (stacksLogged++ < 6) {
                  Log.warn("ragdoll ledger: id=" + e.id + " left its square with a live ragdoll:" + stack(e.squareExit));
               }
            }
         }
      }
   }

   /** Once a frame (RagdollWatch.tick): a live ragdoll whose character is on no square any more. */
   public static void frame(long nowNs) {
      if (!DEV) {
         return;
      }
      synchronized (LIVE) {
         if (LIVE.isEmpty()) {
            return;
         }
         for (Entry e : LIVE.values()) {
            IsoGameCharacter chr = e.chr;
            if (chr == null || e.lost != null) {
               continue;
            }
            zombie.iso.IsoGridSquare sq = chr.getCurrentSquare();
            if (sq != null && sq.getMovingObjects().contains(chr)) {
               continue;
            }
            zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
            e.lost = "lost_square_at_s=" + String.format("%.2f", (nowNs - e.createdNs) / 1e9) + " sq_null=" + (sq == null)
                  + " dead=" + chr.isDead()
                  + " reused=" + (chr instanceof zombie.characters.IsoZombie z && zombie.VirtualZombieManager.instance.isReused(z))
                  + " in_zombie_list=" + (cell != null && cell.getZombieList().contains(chr))
                  + " in_objects=" + (cell != null && cell.getObjectList().contains(chr))
                  + " in_remove_list=" + (cell != null && cell.getRemoveList().contains(chr))
                  + " pos=" + String.format("%.2f,%.2f,%.2f", chr.getX(), chr.getY(), chr.getZ());
            Log.warn("ragdoll ledger: id=" + e.id + " live ragdoll off every square: " + e.lost);
         }
      }
   }

   private static final int[] DEATH_SHAPES = new int[8];

   /** A dead character leaving its square (turning corpse): ragdoll track / controller / simulating, as bits. */
   private static void deathShape(IsoGameCharacter chr) {
      if (!chr.hasAnimationPlayer()) {
         return;
      }
      AnimationPlayer ap = pzoptPlayerOf(chr);
      if (ap == null) {
         return;
      }
      boolean tracks = ap.getMultiTrack().containsAnyRagdollTracks();
      boolean ctl = ap.getRagdollController() != null;
      boolean sim = ctl && ap.isRagdollSimulationActive();
      int k = (tracks ? 1 : 0) | (ctl ? 2 : 0) | (sim ? 4 : 0);
      synchronized (LIVE) {
         DEATH_SHAPES[k]++;
         if (tracks && !ctl && stacksLogged++ < 12) {
            String st = "?";
            try {
               st = String.valueOf(chr.getActionContext().getCurrentStateName());
            } catch (RuntimeException ignored) {
            }
            Log.warn("ragdoll ledger: id=" + chr.getID() + " turns corpse with a ragdoll track and no controller, action state " + st
                  + ", ai state " + (chr.getCurrentState() == null ? "null" : chr.getCurrentState().getClass().getSimpleName()));
         }
      }
   }

   private static String recentExit(Object chr) {
      for (int i = 0; i < RECENT_CHR.length; i++) {
         if (RECENT_CHR[i] == chr) {
            return stack(RECENT_STACK[i]);
         }
      }
      return " (not seen)";
   }

   private static String stack(Throwable t) {
      StringBuilder sb = new StringBuilder();
      StackTraceElement[] st = t.getStackTrace();
      for (int i = 1; i < Math.min(st.length, 30); i++) {
         sb.append("\n    at ").append(st[i]);
      }
      return sb.toString();
   }

   /** WorldSimulation.destroy, before Bullet.destroyWorld: report (and with ragdollQuitSweep remove) what is still added. */
   public static void beforeDestroy() {
      if (!ON) {
         return;
      }
      ArrayList<Map.Entry<RagdollController, Entry>> all;
      synchronized (LIVE) {
         all = new ArrayList<>(LIVE.entrySet());
      }
      int added = 0;
      int swept = 0;
      long now = System.nanoTime();
      for (Map.Entry<RagdollController, Entry> me : all) {
         RagdollController rc = me.getKey();
         Entry e = me.getValue();
         if (!isAdded(rc)) {
            continue;
         }
         added++;
         IsoGameCharacter chr = e.chr;
         StringBuilder sb = new StringBuilder("ragdoll ledger: still in the Bullet world at quit: id=").append(e.id);
         try {
            sb.append(" rc_char_same=").append(rc.getGameCharacterObject() == chr);
            sb.append(" rc_free=").append(rc.isFree());
            sb.append(" owner_free=").append(e.owner.isFree());
            if (chr != null) {
               sb.append(" char=").append(chr.getClass().getSimpleName());
               sb.append(" char_id_now=").append(chr.getID());
               sb.append(" owner_is_char_player=").append(e.owner == pzoptPlayerOf(chr));
               sb.append(" dead=").append(chr.isDead());
               sb.append(" square=").append(chr.getCurrentSquare() != null);
               sb.append(" in_objects=").append(zombie.iso.IsoWorld.instance.currentCell != null
                     && zombie.iso.IsoWorld.instance.currentCell.getObjectList().contains(chr));
               sb.append(" at=").append((int)chr.getX()).append(',').append((int)chr.getY()).append(',').append((int)chr.getZ());
            }
         } catch (RuntimeException ex) {
            sb.append(" (").append(ex).append(')');
         }
         sb.append(" age_s=").append(String.format("%.1f", (now - e.createdNs) / 1e9)).append(" created_on=").append(e.thread);
         if (e.lost != null) {
            sb.append(" | ").append(e.lost);
         }
         if (e.squareExit != null) {
            sb.append(" | square exit:").append(stack(e.squareExit));
         }
         Log.warn(sb.toString());
         if (Config.RAGDOLL_QUIT_SWEEP && Overrides.enabled() && e.id != Integer.MIN_VALUE) {
            Bullet.removeRagdoll(e.id);
            clearAdded(rc);
            swept++;
         }
      }
      synchronized (LIVE) {
         Log.info("ragdoll ledger: created=" + created + " created_off_square=" + createdOffSquare + " released=" + released + " live=" + LIVE.size()
               + " added_at_quit=" + added + " swept=" + swept + " released_while_added=" + releasedAdded
               + " released_off_thread=" + releasedOffThread + " deaths(track/ctl/sim)=" + java.util.Arrays.toString(DEATH_SHAPES));
         java.util.Arrays.fill(DEATH_SHAPES, 0);
         LIVE.clear();
         created = released = releasedAdded = releasedOffThread = stacksLogged = createdOffSquare = 0;
      }
   }

   private static AnimationPlayer pzoptPlayerOf(IsoGameCharacter chr) {
      // the field, not getAnimationPlayer(): that one replaces the player when the body model changed
      try {
         Field f = IsoGameCharacter.class.getDeclaredField("animPlayer");
         f.setAccessible(true);
         return (AnimationPlayer)f.get(chr);
      } catch (ReflectiveOperationException | RuntimeException ex) {
         return null;
      }
   }
}
