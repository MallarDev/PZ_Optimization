package pzopt;

import java.util.Locale;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.ObjectRenderInfo;

/**
 * Dev rig {@code devSquareTrace=x,y,z[;x,y,z...]} (2026-09-29, the Fossoil shelf blink): once per frame, after the chunk
 * loop, one log line per listed square with every object's sprite, render layer (baked MinusFloor* / per-frame
 * Translucent*), target and current alpha, whether the square's chunk baked a texture this frame, and the square's light
 * as the bake saw it. At most {@code devSquareTraceFrames} frames (600), starting {@code devSquareTraceAt} seconds after
 * the world is up (0). Game thread only.
 */
public final class SquareTrace {
   private SquareTrace() {
   }

   private static int[][] squares;
   private static int frames, logged;
   private static long startMs = -1;
   private static final java.util.Set<IsoChunk> baked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

   private static boolean on() {
      if (squares == null) {
         String s = Config.DEV_SQUARE_TRACE.trim();
         if (s.isEmpty()) {
            squares = new int[0][];
         } else {
            String[] parts = s.split(";");
            squares = new int[parts.length][];
            for (int i = 0; i < parts.length; i++) {
               String[] c = parts[i].trim().split(",");
               squares[i] = new int[] {Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim())};
            }
         }
      }
      return squares.length > 0;
   }

   /** A chunk texture of {@code c} starts baking this frame (FBORenderCell, beside PixelLight.bakeBegin). */
   public static void noteBake(IsoChunk c) {
      if (squares != null && squares.length > 0) {
         baked.add(c);
      }
   }

   /** After the chunk loop (game thread). */
   public static void frame(int playerIndex) {
      if (!on()) {
         return;
      }
      frames++;
      long now = System.currentTimeMillis();
      if (startMs < 0) {
         startMs = now + Config.DEV_SQUARE_TRACE_AT * 1000L;
      }
      if (now < startMs || logged >= Config.DEV_SQUARE_TRACE_FRAMES) {
         baked.clear();
         return;
      }
      logged++;
      StringBuilder sb = new StringBuilder(256);
      sb.append("square trace: f=").append(frames).append(" ms=").append(now);
      for (int[] q : squares) {
         IsoGridSquare sq = IsoWorld.instance.getCell().getGridSquare(q[0], q[1], q[2]);
         sb.append(" | ").append(q[0]).append(',').append(q[1]).append(',').append(q[2]);
         if (sq == null) {
            sb.append(" -");
            continue;
         }
         sb.append(" bake=").append(sq.chunk != null && baked.contains(sq.chunk) ? 1 : 0);
         zombie.core.textures.ColorInfo li = sq.getLightInfo(playerIndex);
         if (li != null) {
            sb.append(String.format(Locale.ROOT, " li=%.2f,%.2f,%.2f", li.r, li.g, li.b));
         }
         for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject o = sq.getObjects().get(i);
            ObjectRenderInfo ri = o.getRenderInfo(playerIndex);
            String name = o.getSprite() == null ? "?" : o.getSprite().getName();
            sb.append(String.format(Locale.ROOT, " [%s %s a=%.2f/%.2f%s]", name, ri.layer, ri.targetAlpha, o.getAlpha(playerIndex),
                  o.getOverlaySprite() == null ? "" : " ov=" + o.getOverlaySprite().getName()));
         }
      }
      baked.clear();
      Log.info(sb.toString());
   }
}
