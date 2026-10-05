package pzopt;

import zombie.characters.IsoPlayer;
import zombie.inventory.ItemContainer;
import zombie.inventory.ItemPickerJava;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * {@code lootDefer} (2026-10-05, Louisville 120 fps pass): time-sliced loot generation. A chunk's first hand-off rolls the
 * loot of every unexplored container on its squares (LoadGridsquarePerformanceWorkaround, ItemPickerJava.fillContainer); a
 * downtown chunk with shops took up to 32 ms of one game-thread frame. For a chunk arriving at least {@code lootDeferDistance}
 * squares from every player, the containers are queued instead and rolled in arrival order under {@code lootDeferBudgetUs}
 * of each frame (at least one container a frame), with the same calls the hand-off makes (fill, explored, overlay sprite).
 * An entry whose chunk left the world or whose container got explored meanwhile is dropped (the chunk saves it unexplored,
 * so it rolls on its next load, as an unvisited chunk does).
 *
 * Difference from stock (an intended edit, docs/override-edits.md): the containers roll a few frames later, so the order of
 * the game's random draws changes (the same loot tables and chances). Single player only.
 */
public final class LootDefer {
   private LootDefer() {
   }

   private static final java.util.ArrayDeque<IsoObject> OBJECTS = new java.util.ArrayDeque<>();
   private static final java.util.ArrayDeque<ItemContainer> CONTAINERS = new java.util.ArrayDeque<>();
   private static final java.util.ArrayDeque<IsoChunk> CHUNKS = new java.util.ArrayDeque<>();
   private static final java.util.ArrayDeque<Integer> FRAMES = new java.util.ArrayDeque<>(); // SlackWork frame of each entry
   private static final java.util.HashMap<String, SlackWork.Cost> COSTS = new java.util.HashMap<>(); // per container type
   private static final SlackWork.Producer PRODUCER = new SlackWork.Producer() {
      public boolean pending() {
         return !OBJECTS.isEmpty();
      }

      public long nextCostNs() {
         return cost(CONTAINERS.peek()).estimate();
      }

      public int nextAgeFrames() {
         return SlackWork.frame() - FRAMES.peek();
      }

      public void runNext() {
         rollOne();
      }
   };
   private static int lastFrame = Integer.MIN_VALUE;
   public static long deferred, rolled, dropped, maxQueue;
   private static long lastLogNs;

   public static boolean enabled() {
      return Config.LOOT_DEFER && Overrides.enabled() && !GameServer.server && !GameClient.client && GtAb.on(GtAb.LOOT_DEFER);
   }

   /** Game thread, from the hand-off's checkObject: queue this container instead of rolling it now (true), or not (false). */
   public static boolean defer(IsoObject object, ItemContainer container) {
      if (!enabled()) {
         return false;
      }

      IsoGridSquare sq = object.getSquare();
      if (sq == null || sq.getChunk() == null) {
         return false;
      }

      float d2 = (float)Config.LOOT_DEFER_DISTANCE * Config.LOOT_DEFER_DISTANCE;
      for (int i = 0; i < IsoPlayer.numPlayers; i++) {
         IsoPlayer p = IsoPlayer.players[i];
         if (p != null) {
            float dx = p.getX() - sq.getX();
            float dy = p.getY() - sq.getY();
            if (dx * dx + dy * dy < d2) {
               return false;
            }
         }
      }

      OBJECTS.add(object);
      CONTAINERS.add(container);
      CHUNKS.add(sq.getChunk());
      FRAMES.add(SlackWork.frame());
      SlackWork.register(PRODUCER);
      deferred++;
      maxQueue = Math.max(maxQueue, OBJECTS.size());
      return true;
   }

   /** Is (x, y) within {@code dist} squares of a player. */
   public static boolean nearPlayer(float x, float y, int dist) {
      float d2 = (float)dist * dist;
      for (int i = 0; i < IsoPlayer.numPlayers; i++) {
         IsoPlayer p = IsoPlayer.players[i];
         if (p != null) {
            float dx = p.getX() - x;
            float dy = p.getY() - y;
            if (dx * dx + dy * dy < d2) {
               return true;
            }
         }
      }
      return false;
   }

   /** Game thread, once a frame (IsoChunkMap.update): uncapped (no slack), roll queued containers for at most the frame's budget. */
   public static void drain() {
      if (OBJECTS.isEmpty() || SlackWork.active()) {
         return;
      }

      int frame = IsoWorld.instance.getFrameNo();
      if (frame == lastFrame) {
         return;
      }

      lastFrame = frame;
      long t0 = System.nanoTime();
      long budget = Config.LOOT_DEFER_BUDGET_US * 1000L;
      boolean first = true;
      while (!OBJECTS.isEmpty() && (first || System.nanoTime() - t0 < budget)) {
         first = !rollOne() && first;
      }

      if (Config.INSTRUMENT) {
         long now = System.nanoTime();
         if (now - lastLogNs > 10_000_000_000L) {
            lastLogNs = now;
            Log.info("loot defer: deferred " + deferred + " rolled " + rolled + " dropped " + dropped + " queue " + OBJECTS.size() + " max " + maxQueue);
         }
      }
   }

   private static SlackWork.Cost cost(ItemContainer c) {
      String type = c == null ? "" : c.getType();
      return COSTS.computeIfAbsent(type == null ? "" : type, k -> new SlackWork.Cost(500_000.0, 0.25));
   }

   /** The oldest queued container: rolled (true) or dropped (false). */
   private static boolean rollOne() {
      IsoObject object = OBJECTS.poll();
      ItemContainer container = CONTAINERS.poll();
      IsoChunk chunk = CHUNKS.poll();
      FRAMES.poll();
      IsoGridSquare sq = object.getSquare();
      if (sq == null || sq.getChunk() != chunk || !chunk.loaded || object.getContainer() != container || container.isExplored()) {
         dropped++;
         return false;
      }

      long t0 = System.nanoTime();
      ItemPickerJava.fillContainer(container, IsoPlayer.getInstance());
      container.setExplored(true);
      if (!container.isEmpty()) {
         ItemPickerJava.updateOverlaySprite(object);
      }

      cost(container).learn(System.nanoTime() - t0);
      rolled++;
      return true;
   }

   /** Every queued container rolled now (a save or a teleport). */
   public static void flush() {
      while (!OBJECTS.isEmpty()) {
         rollOne();
      }
   }
}
