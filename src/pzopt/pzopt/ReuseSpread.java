package pzopt;

import zombie.characters.IsoZombie;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * zombieReuseSpread (Louisville 120 third item, 2026-10-06): the zombies leaving the world are reset for reuse over a few
 * frames instead of all in the frame they left.
 *
 * <p>Stock {@code VirtualZombieManager.update} resets every zombie removed this frame ({@code resetForReuse}: the state
 * machine, the action context, the animator data, the zombie's stats) before it goes into the reuse pool. A chunk row
 * leaving the grid removes dozens at once downtown: ~10 ms of resets in one frame on the Louisville horde. Here those
 * zombies wait in a queue and are reset oldest first under {@code zombieReuseBudgetUs} a frame; the budget grows with the
 * backlog so the queue drains within {@code zombieReuseDrainFrames}. A spawn that finds the pool empty resets one queued
 * zombie first (the pool never allocates a new zombie because of the queue), a queued zombie counts as reused
 * ({@code isReused}) as it would be in stock by the end of its frame, and its vocal sound stops when it is queued, as the
 * reset would. Intended difference: the reset's random draws (the speed roll in {@code DoZombieStats}) come a few frames
 * later in the game's random sequence. Single player only.
 */
public final class ReuseSpread {
   private ReuseSpread() {
   }

   private static final boolean KEY = Config.ZOMBIE_REUSE_SPREAD;

   public static boolean enabled() {
      return KEY && Overrides.enabled() && !GameClient.client && !GameServer.server;
   }

   private static final java.util.ArrayDeque<IsoZombie> PENDING = new java.util.ArrayDeque<>();
   private static final java.util.Set<IsoZombie> PENDING_SET = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
   private static long queued, resetInFrame, resetOnDemand, frames, maxBacklog, resetNs;

   /** Game thread: a zombie stock would reset this frame goes to the queue. */
   public static void queue(IsoZombie z) {
      if (z == null || !PENDING_SET.add(z)) {
         return;
      }
      if (z.vocalEvent != 0L) {
         z.getEmitter().stopSoundLocal(z.vocalEvent); // resetForReuse's first sound step, at the time stock would take it
         z.vocalEvent = 0L;
      }
      PENDING.addLast(z);
      queued++;
   }

   /** True while the zombie waits for its reset (stock has it in the pool by then). */
   public static boolean pending(IsoZombie z) {
      return !PENDING.isEmpty() && PENDING_SET.contains(z);
   }

   /** Game thread, once a frame after the queueing: reset oldest first under the frame's budget. */
   public static void drain(java.util.function.Consumer<IsoZombie> reset) {
      int n = PENDING.size();
      if (n == 0) {
         return;
      }
      frames++;
      maxBacklog = Math.max(maxBacklog, n);
      int minCount = (n + Config.ZOMBIE_REUSE_DRAIN_FRAMES - 1) / Config.ZOMBIE_REUSE_DRAIN_FRAMES;
      long budget = Config.ZOMBIE_REUSE_BUDGET_US * 1000L;
      long t0 = System.nanoTime();
      int done = 0;
      while (!PENDING.isEmpty() && (done < minCount || System.nanoTime() - t0 < budget)) {
         IsoZombie z = PENDING.pollFirst();
         PENDING_SET.remove(z);
         reset.accept(z);
         done++;
      }
      resetInFrame += done;
      resetNs += System.nanoTime() - t0;
   }

   /** Game thread: the pool is empty and a zombie is wanted; reset the oldest queued one now. True when one was reset. */
   public static boolean resetOne(java.util.function.Consumer<IsoZombie> reset) {
      IsoZombie z = PENDING.pollFirst();
      if (z == null) {
         return false;
      }
      PENDING_SET.remove(z);
      reset.accept(z);
      resetOnDemand++;
      return true;
   }

   /** VirtualZombieManager.Reset: the queued zombies, for stock's clean-up of the frame's removed zombies. */
   public static java.util.List<IsoZombie> takeAll() {
      java.util.ArrayList<IsoZombie> all = new java.util.ArrayList<>(PENDING);
      PENDING.clear();
      PENDING_SET.clear();
      return all;
   }

   public static String describe() {
      return "reuse spread: queued=" + queued + " resetInFrame=" + resetInFrame + " onDemand=" + resetOnDemand + " frames=" + frames
         + " maxBacklog=" + maxBacklog + String.format(" resetMs=%.1f", resetNs / 1e6);
   }
}
