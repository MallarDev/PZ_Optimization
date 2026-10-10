package pzopt;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import javax.imageio.ImageIO;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

/**
 * The part of the cutaway stencil that IsoCell.drawStencilMask really marks: the mask textures are drawn with alpha
 * test GREATER 0.1, so only their texels above 0.1 set the stencil bit. mask_transparency_player.png marks 21 % of its
 * 1024 x 1024 square (an ellipse, texels 111..922 x 287..740). A see-through tree's inside passes (FBORenderTrees,
 * stencil EQUAL 128) can only draw within those boxes, so a tree whose sprite stays clear of them stays in the bake
 * (treeCutawayReach, FBORenderCell). The boxes are in the stencil areas' offscreen pixels, the space of isInStencil and
 * cachedScreenX; the world framebuffer itself is screen-sized (the projection divides by the zoom). Boxes are read once per texture from the PNG (mods that replace a mask included);
 * an unreadable mask counts as its whole square. The PNGs are decoded on a worker at the main menu (prefetch): the
 * first decode on the game thread was a 50-60 ms frame ~1.5 s into every drive (docs/findings-frame-spikes-2026-10-09.md);
 * until the worker is done a mask counts as its whole square.
 */
public final class CutawayMask {
   private CutawayMask() {
   }

   private static final String PLAYER = "media/mask_transparency_player.png";
   private static final String CURSOR = "media/mask_transparency_cursor.png";
   private static int[] playerBox; // x1, y1, x2, y2 (exclusive) in texels, or null = not read yet
   private static int[] cursorBox;
   // decoded by the prefetch worker: x1, y1, x2, y2, PNG width, PNG height (null = not done, or unreadable)
   private static volatile int[] playerRead;
   private static volatile int[] cursorRead;
   private static volatile boolean prefetchDone;
   private static boolean prefetchStarted;

   /** Decodes both mask PNGs on a worker thread; from NoLoadingScreen at the first game state change, idempotent. */
   public static synchronized void prefetch() {
      if (prefetchStarted) {
         return;
      }
      prefetchStarted = true;
      Thread t = new Thread(() -> {
         try {
            playerRead = scan(PLAYER);
            cursorRead = scan(CURSOR);
         } finally {
            prefetchDone = true;
         }
      }, "pzopt-cutaway-mask");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
   }

   /** The marked box of every stencil area of this frame in offscreen pixels (x1, y1, x2, y2 per area); null if none. */
   public static int[] frameBoxes() {
      IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
      if (cell == null || cell.getStencilAreas().isEmpty()) {
         return null;
      }
      java.util.List<IsoCell.StencilArea> areas = cell.getStencilAreas();
      int[] out = new int[areas.size() * 4];
      for (int i = 0; i < areas.size(); i++) {
         IsoCell.StencilArea a = areas.get(i);
         int[] box = boxFor(i == 0 ? PLAYER : CURSOR, a.texWidth(), a.texHeight());
         int scale = Core.tileScale;
         out[i * 4] = a.stencilX1() + box[0] * scale;
         out[i * 4 + 1] = a.stencilY1() + box[1] * scale;
         out[i * 4 + 2] = a.stencilX1() + box[2] * scale;
         out[i * 4 + 3] = a.stencilY1() + box[3] * scale;
      }
      return out;
   }

   private static int[] boxFor(String path, int texW, int texH) {
      int[] box = path == PLAYER ? playerBox : cursorBox;
      if (box == null || box.length != 6 || box[4] != texW || box[5] != texH) {
         if (!prefetchDone) {
            prefetch();
            return new int[]{0, 0, texW, texH, texW, texH}; // not decoded yet: the whole square, not cached
         }
         box = fit(path, path == PLAYER ? playerRead : cursorRead, texW, texH);
         if (path == PLAYER) {
            playerBox = box;
         } else {
            cursorBox = box;
         }
      }
      return box;
   }

   /** The worker's scan for the texture's size; a mask of another size than its texture counts as the whole square. */
   private static int[] fit(String path, int[] read, int texW, int texH) {
      if (read == null || read[4] != texW || read[5] != texH) {
         if (read != null) {
            Log.info("cutaway mask " + path + ": size differs from the texture, using its whole square");
         }
         return new int[]{0, 0, texW, texH, texW, texH};
      }
      return read;
   }

   /** Off the game thread: the box of the texels above the alpha test in the PNG, with the PNG's size; null if unreadable. */
   private static int[] scan(String path) {
      try {
         File f = new File(ZomboidFileSystem.instance.getString(path));
         BufferedImage img = ImageIO.read(f);
         if (img == null) {
            Log.info("cutaway mask " + path + " unreadable, using its whole square");
            return null;
         }
         int w = img.getWidth(), h = img.getHeight();
         WritableRaster alpha = img.getAlphaRaster();
         int[] row = new int[w];
         int x1 = w, y1 = h, x2 = 0, y2 = 0;
         for (int y = 0; y < h; y++) {
            if (alpha != null) {
               alpha.getSamples(0, y, w, 1, 0, row);
            } else {
               img.getRGB(0, y, w, 1, row, 0, w);
               for (int x = 0; x < w; x++) {
                  row[x] >>>= 24;
               }
            }
            for (int x = 0; x < w; x++) {
               // alpha test GREATER 0.1 on 8-bit alpha: 26 / 255 = 0.102 passes, 25 / 255 = 0.098 does not;
               // linear filtering can lift a texel next to a marked one, so the box grows by one texel below
               if (row[x] > 25) {
                  if (x < x1) x1 = x;
                  if (y < y1) y1 = y;
                  if (x >= x2) x2 = x + 1;
                  if (y >= y2) y2 = y + 1;
               }
            }
         }
         if (x2 <= x1 || y2 <= y1) {
            return new int[]{0, 0, 0, 0, w, h};
         }
         int[] box = {Math.max(0, x1 - 1), Math.max(0, y1 - 1), Math.min(w, x2 + 1), Math.min(h, y2 + 1), w, h};
         Log.info("cutaway mask " + path + ": marked texels " + box[0] + ".." + box[2] + " x " + box[1] + ".." + box[3] + " of " + w + " x " + h);
         return box;
      } catch (Throwable t) {
         Log.info("cutaway mask " + path + " unreadable (" + t + "), using its whole square");
         return null;
      }
   }
}
