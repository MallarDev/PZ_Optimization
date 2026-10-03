package pzopt;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * devStencilProbe: reads the stencil at one offscreen pixel (the cutaway's centre, the player) on the render thread at
 * named points of the frame and logs the values every 2 s, to find where the cutaway's tree bit (128) is lost.
 * Dev only: glReadPixels stalls the pipeline.
 */
public final class StencilProbe extends TextureDraw.GenericDrawer {
   private static final ByteBuffer ONE = MemoryUtil.memAlloc(65536);
   private static final java.util.Map<String, int[]> SEEN = new java.util.TreeMap<>(); // point -> {reads, with bit 128, last value, last fbo}
   private static long nextLogMs;
   private static String lastState = "";

   private final String point;
   private final int x;
   private final int y;

   private StencilProbe(String point, int x, int y) {
      this.point = point;
      this.x = x;
      this.y = y;
   }

   /** Game thread: queue a read at this point of the sprite renderer's command stream (offscreen pixel x, y; y down). */
   public static void queue(String point, int x, int yDown, int offscreenHeight) {
      if (!Config.DEV_STENCIL_PROBE) {
         return;
      }
      SpriteRenderer.instance.drawGeneric(new StencilProbe(point, x, offscreenHeight - 1 - yDown));
   }

   /** Render thread, at once (inside another drawer). */
   public static void readNow(String point, int x, int yDown, int offscreenHeight) {
      new StencilProbe(point, x, offscreenHeight - 1 - yDown).render();
   }

   @Override
   public void render() {
      // a 4096-px row and column through the point: how many pixels carry the tree bit
      int v = 0;
      ONE.clear();
      GL11.glReadPixels(Math.max(0, this.x - 2048), this.y, 4096, 1, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, ONE);
      for (int i = 0; i < 4096; i++) {
         if ((ONE.get(i) & 128) != 0) v++;
      }
      ONE.clear();
      GL11.glReadPixels(this.x, Math.max(0, this.y - 2048), 1, 4096, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, ONE);
      for (int i = 0; i < 4096; i++) {
         if ((ONE.get(i) & 128) != 0) v += 65536;
      }
      int fbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      String state = " depthTest=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST) + " depthFunc=" + GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
         + " alphaTest=" + GL11.glIsEnabled(GL11.GL_ALPHA_TEST) + " stencilTest=" + GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
         + " program=" + GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM) + " depthClear=" + GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE);
      if (this.point.startsWith("a")) lastState = state;
      synchronized (SEEN) {
         int[] s = SEEN.computeIfAbsent(this.point, k -> new int[4]);
         s[0]++;
         if (v != 0) {
            s[1]++;
         }
         s[2] = v;
         s[3] = fbo;
         long now = System.currentTimeMillis();
         if (now >= nextLogMs) {
            nextLogMs = now + 2000L;
            StringBuilder sb = new StringBuilder("stencil probe:");
            for (java.util.Map.Entry<String, int[]> e : SEEN.entrySet()) {
               int[] t = e.getValue();
               sb.append(' ').append(e.getKey()).append(" reads=").append(t[0]).append(" frames with bit128=").append(t[1]).append(" last row px=").append(t[2] & 65535).append(" col px=").append(t[2] >>> 16).append(" fbo=").append(t[3]);
            }
            Log.info(sb.toString() + " | after mask:" + lastState);
         }
      }
   }
}
