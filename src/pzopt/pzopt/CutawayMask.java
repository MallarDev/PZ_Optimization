package pzopt;

import java.awt.image.BufferedImage;
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
 * an unreadable mask counts as its whole square.
 */
public final class CutawayMask {
   private CutawayMask() {
   }

   private static final String PLAYER = "media/mask_transparency_player.png";
   private static final String CURSOR = "media/mask_transparency_cursor.png";
   private static int[] playerBox; // x1, y1, x2, y2 (exclusive) in texels, or null = not read yet
   private static int[] cursorBox;

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
         box = read(path, texW, texH);
         if (path == PLAYER) {
            playerBox = box;
         } else {
            cursorBox = box;
         }
      }
      return box;
   }

   private static int[] read(String path, int texW, int texH) {
      int[] whole = {0, 0, texW, texH, texW, texH};
      try {
         File f = new File(ZomboidFileSystem.instance.getString(path));
         BufferedImage img = ImageIO.read(f);
         if (img == null || img.getWidth() != texW || img.getHeight() != texH) {
            Log.info("cutaway mask " + path + ": size differs from the texture, using its whole square");
            return whole;
         }
         int x1 = texW, y1 = texH, x2 = 0, y2 = 0;
         for (int y = 0; y < texH; y++) {
            for (int x = 0; x < texW; x++) {
               // alpha test GREATER 0.1 on 8-bit alpha: 26 / 255 = 0.102 passes, 25 / 255 = 0.098 does not;
               // linear filtering can lift a texel next to a marked one, so the box grows by one texel below
               if ((img.getRGB(x, y) >>> 24) > 25) {
                  if (x < x1) x1 = x;
                  if (y < y1) y1 = y;
                  if (x >= x2) x2 = x + 1;
                  if (y >= y2) y2 = y + 1;
               }
            }
         }
         if (x2 <= x1 || y2 <= y1) {
            return new int[]{0, 0, 0, 0, texW, texH};
         }
         int[] box = {Math.max(0, x1 - 1), Math.max(0, y1 - 1), Math.min(texW, x2 + 1), Math.min(texH, y2 + 1), texW, texH};
         Log.info("cutaway mask " + path + ": marked texels " + box[0] + ".." + box[2] + " x " + box[1] + ".." + box[3] + " of " + texW + " x " + texH);
         return box;
      } catch (Throwable t) {
         Log.info("cutaway mask " + path + " unreadable (" + t + "), using its whole square");
         return whole;
      }
   }
}
