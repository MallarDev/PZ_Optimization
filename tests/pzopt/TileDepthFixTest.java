package pzopt;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * TileDepthFix (issue #38): the source's box drawn with the game's geometry equals the shipped depth map cell; every
 * fitted texture covers exactly the stock box's texels (so the sprite draws and discards the same pixels), is never
 * behind the stock box (the fitted boxes lie inside it), and moves the surface back for the cabinets on the far walls; the build
 * of every distinct texture stays short (it runs during the load).
 */
public class TileDepthFixTest {
   public static void main(String[] args) throws Exception {
      int w = 128, h = 256;
      float[] stock = TileDepthFix.stockPixels(w, h);

      File png = new File(System.getenv().getOrDefault("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid"), "media/depthmaps/DEPTH_fixtures_counters_01.png");
      if (png.isFile()) {
         BufferedImage img = ImageIO.read(png);
         int ox = 0, oy = 512; // cell 16: column 0, row 2
         int both = 0, onlyOne = 0, off = 0;
         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               int argb = img.getRGB(ox + x, oy + y);
               boolean shipped = (argb >>> 24) != 0;
               boolean ours = stock[x + y * w] >= 0.0F;
               if (shipped && ours) {
                  both++;
                  if (Math.abs((argb & 0xFF) - (int)Math.floor(stock[x + y * w] * 255.0F)) > 2) {
                     off++;
                  }
               } else if (shipped != ours) {
                  onlyOne++;
               }
            }
         }
         System.out.println("TileDepthFixTest: stock box vs shipped cell 16: " + both + " texels in both, " + onlyOne + " in one only, " + off + " off by more than 2 LSB (the editor sampled the texels a hair differently)");
         Check.check(both > 10000, "the stock box covers the shipped cell");
         Check.check(onlyOne < both / 50, "the stock box's outline matches the shipped cell (edge texels only)");
         Check.check(off < both / 100, "the stock box's depth matches the shipped cell within 2 LSB");
      } else {
         System.out.println("TileDepthFixTest: no game install, shipped-cell comparison skipped");
      }

      java.lang.reflect.Method plane = TileDepthFix.class.getDeclaredMethod("planeAt", int.class, int.class, int.class, int.class, org.joml.Vector3f.class);
      plane.setAccessible(true);
      org.joml.Vector3f ceiling = new org.joml.Vector3f(0.0F, TileDepthFix.CEILING, 0.0F);
      java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
      for (String row : TileDepthFix.BOXES) {
         distinct.add(row.split("\\|")[3]);
      }
      long t0 = System.nanoTime();
      int moved = 0;
      for (String boxes : distinct) {
         float[] px = TileDepthFix.pixels(boxes, w, h, stock);
         int changed = 0;
         for (int i = 0; i < w * h; i++) {
            Check.check((px[i] >= 0.0F) == (stock[i] >= 0.0F), "fitted texel coverage equals the stock box's at " + i + " for " + boxes);
            if (px[i] >= 0.0F) {
               Check.check(px[i] >= stock[i] - 1e-4F, "a fitted texel is never in front of the stock box (" + boxes + ")");
               Check.check(px[i] > (float)plane.invoke(null, i % w, i / w, w, h, ceiling) + 1e-3F, "every fitted texel under the ceiling plane (on it pixel light reads the level above)");
               if (px[i] > stock[i] + 1e-4F) {
                  changed++;
               }
            }
         }
         if (changed > 1500) {
            moved++;
         }
      }
      long ms = (System.nanoTime() - t0) / 1_000_000L;
      System.out.println("TileDepthFixTest: " + TileDepthFix.BOXES.length + " tiles, " + distinct.size() + " distinct textures built in " + ms + " ms; " + moved + " move the surface back");
      Check.check(TileDepthFix.BOXES.length == 88, "88 tiles in the table");
      // a cabinet hung on the square's south or east edge (the viewer's side) shows the stock box's own faces: only the
      // one straight N-facing shape keeps every texel; every other shape moves thousands of texels back
      Check.check(moved >= distinct.size() - 1, "the fitted textures move the cabinets' surfaces back from the stock box");
      Check.check(ms < 3000, "the textures build in under 3 s");
      // the canopies (tileDepthCanopies): a fitted shape (slab, valance or ellipsoid) moves texels; with a source box they stay
      // inside it and never in front of it; without one (drawn with the default depth) every covered texel keeps a depth
      int canopyMoved = 0, noSource = 0;
      for (String row : TileDepthFix.CANOPIES) {
         String[] f = row.split("\\|");
         float[] shape = TileDepthFix.pixels(f[3], w, h, null, null);
         int shapeHits = 0;
         for (float v : shape) {
            if (v >= 0.0F) {
               shapeHits++;
            }
         }
         Check.check(shapeHits > 500, "a canopy's fitted shape covers texels (" + f[0] + ")");
         if ("-".equals(f[1])) {
            noSource++;
            boolean[] mask = new boolean[w * h];
            for (int i = 0; i < w * h; i++) {
               mask[i] = shape[i] >= 0.0F || i % 7 == 0 && i / w < 160; // the shape plus scattered texels it misses
            }
            float[] cover = new float[w * h];
            for (int i = 0; i < w * h; i++) {
               cover[i] = mask[i] ? 1.0E9F : -1.0F;
            }
            float[] px = TileDepthFix.pixels(f[3], w, h, cover, mask);
            for (int i = 0; i < w * h; i++) {
               Check.check((px[i] >= 0.0F) == mask[i], "a no-source canopy keeps a depth on exactly its sprite's texels (" + f[0] + ")");
            }
            canopyMoved++;
            continue;
         }
         float[] src = TileDepthFix.pixels(f[2], w, h, null, null);
         float[] px = TileDepthFix.pixels(f[3], w, h, src, null);
         int changed = 0;
         for (int i = 0; i < w * h; i++) {
            if (px[i] >= 0.0F) {
               Check.check(src[i] >= 0.0F, "a canopy texel inside its source box (" + f[0] + ")");
               Check.check(px[i] >= src[i] - 1e-4F, "a canopy texel never in front of its source box (" + f[0] + ")");
               if (px[i] > src[i] + 1e-4F) {
                  changed++;
               }
            }
         }
         if (changed > 500) {
            canopyMoved++;
         }
      }
      System.out.println("TileDepthFixTest: " + TileDepthFix.CANOPIES.length + " canopies (" + noSource + " without a depth texture), " + canopyMoved + " move the surface back or cover their sprite");
      Check.check(canopyMoved == TileDepthFix.CANOPIES.length, "every fitted canopy moves its surface back from the box");
      System.out.println("TileDepthFixTest: ok");
   }
}
