package pzopt;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import zombie.ui.UIElementInterface;

/**
 * uiTickStagger (2026-09-30, the UI snappiness pass): stock runs every UI element's Lua update in the same frame, once
 * every 100 ms (UIManager.doTick), so that frame carries all of them (~0.5 ms on the HUD with the inventory windows,
 * ~1.5 ms with the crafting window open) and the others none. Here every top-level element keeps its own 100 ms tick,
 * with a phase spread over the 100 ms by its place in the UI list when first seen: the same updates at the same rate,
 * spread over frames. While a top-level element (and its children) updates, UIManager.doTick is that element's tick
 * and UIManager.uiUpdateIntervalMS its own time since its last tick (what Lua reads through getMillisSinceLastUpdate).
 */
public final class UiTicks {
   private static final class Tick {
      long nextMs;
      long lastMs;
      long seenFrame;
   }

   private static final IdentityHashMap<UIElementInterface, Tick> ticks = new IdentityHashMap<>();
   private static long frame;
   private static long nextCleanupMs;
   private static long interval;

   private UiTicks() {
   }

   public static boolean active() {
      return Config.UI_TICK_STAGGER && Overrides.enabled();
   }

   /** UIManager.updateUIElements, once per frame before the element loop. */
   public static void beginFrame(long nowMs) {
      frame++;
      if (nowMs >= nextCleanupMs) {
         nextCleanupMs = nowMs + 5000L;
         Iterator<Map.Entry<UIElementInterface, Tick>> it = ticks.entrySet().iterator();
         while (it.hasNext()) {
            if (frame - it.next().getValue().seenFrame > 600L) {
               it.remove();
            }
         }
      }
   }

   /** Is this top-level element's 100 ms tick due now (index / count place a new element's phase)? */
   public static boolean due(UIElementInterface e, int index, int count, long nowMs) {
      Tick t = ticks.get(e);
      if (t == null) {
         t = new Tick();
         long phase = count <= 0 ? 0L : (index * 100L / count) % 100L;
         t.nextMs = nowMs + phase;
         t.lastMs = nowMs - 100L;
         ticks.put(e, t);
      }
      t.seenFrame = frame;
      if (nowMs < t.nextMs) {
         return false;
      }
      interval = Math.min(nowMs - t.lastMs, 1000L);
      t.lastMs = nowMs;
      t.nextMs += 100L;
      if (t.nextMs <= nowMs) {
         t.nextMs = nowMs + 100L;
      }
      return true;
   }

   /** The element's time since its previous tick (valid right after due returned true). */
   public static long interval() {
      return interval;
   }
}
