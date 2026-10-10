package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import zombie.iso.sprite.IsoSprite;

/**
 * Reflective props (key {@code mirrorsProps}, 2026-10-08): the tiles that are neither windows nor mirror tiles but have glass,
 * a screen, polished metal or glazed ceramic the camera sees (glass doors, store-front panes, railings, shower screens,
 * display counters and cases, glass-door fridges, the glass table, televisions and monitors, the gym's mirrors, steel
 * counters, sinks and appliances, toilets), 430 sprites found by harness/props/catalog.py.
 *
 * <p>harness/props/masks.py fits a plane to every texel of each sprite's depth map (the game's own, media/depthmaps): the
 * face it lies on (a top, a south face or an east face, the only faces the iso camera sees) and that face's plane, plus how
 * much of it reflects (its glass, screen or metal texels). pzopt.Mirrors draws them as a third kind of reflector beside the
 * windows and mirrors: every glass texel's pane point comes from its face's plane, its reflected ray from the face (a top
 * reflects straight up its screen column, a vertical face along the pane's iso line), the rest is the mirrors' pipeline (the
 * per-pane atlas tile, the static pass every 4th frame, the composite, the visibility feedback, the mirrored models in the
 * main vertical face).
 */
public final class Props {
   private Props() {
   }

   static final int COLS = 32, CELL_W = 64, CELL_H = 128;
   static final int GLASS = 1, SCREEN = 2, MIRROR = 3, STEEL = 4, CERAMIC = 5;
   private static final float[] NONE = new float[0];
   private static final ConcurrentHashMap<IsoSprite, float[]> SPRITES = new ConcurrentHashMap<>();
   private static HashMap<String, float[]> table;
   static int rows = 1;

   static boolean on() {
      return Config.MIRRORS && Config.MIRRORS_PROPS && !Mirrors.failed && Overrides.enabled();
   }

   /** A reflective prop's {class, atlas cell, alpha mode (1: its translucent texels are the glass), main vertical axis (-1 none), its offset, its reflecting top's height (levels, -1 none), its reflective box x0 y0 x1 y1 (frame fractions)}, else null. */
   static float[] info(IsoSprite s) {
      if (s == null) {
         return null;
      }
      float[] v = SPRITES.get(s);
      if (v == null) {
         String n = s.getName();
         v = n == null ? null : table().get(n);
         if (v == null) {
            v = NONE;
         }
         SPRITES.put(s, v);
      }
      return v.length == 0 ? null : v;
   }

   /** Drawn per frame (out of the chunk textures) so its quad is captured and its glass gets the reflection over it. */
   public static boolean perFrame(IsoSprite s) {
      return on() && info(s) != null;
   }

   /**
    * An opaque reflective prop (a screen, steel, ceramic, opaque glass: not the translucent-glass mode) drawn per frame writes its
    * depth, as its baked self did in the chunk texture (mirrorsPropDepth): the translucent pass draws without depth writes, and a
    * glass table's reflection behind a television was composited over the television.
    */
   public static boolean writesDepth(IsoSprite s) {
      if (!Config.MIRRORS_PROP_DEPTH || !on()) {
         return false;
      }
      float[] v = info(s);
      return v != null && v[2] < 0.5F;
   }

   static float strength(int cls) {
      return switch (cls) {
         case SCREEN -> Config.MIRRORS_PROP_SCREEN_PCT;
         case MIRROR -> Config.MIRRORS_MIRROR_PCT;
         case STEEL -> Config.MIRRORS_PROP_STEEL_PCT;
         case CERAMIC -> Config.MIRRORS_PROP_CERAMIC_PCT;
         default -> Config.MIRRORS_PROP_GLASS_PCT;
      } / 100F;
   }

   /** How rough the class's surface is (mirrorsPropGloss blur: the reflection's blur grows with the hit distance by this). */
   static float roughness(int cls) {
      return switch (cls) {
         case STEEL -> Config.MIRRORS_PROP_STEEL_ROUGH_PCT;
         case CERAMIC -> Config.MIRRORS_PROP_CERAMIC_ROUGH_PCT;
         default -> 0;
      } / 100F;
   }

   private static synchronized HashMap<String, float[]> table() {
      if (table != null) {
         return table;
      }
      HashMap<String, float[]> m = new HashMap<>();
      try {
         File f = zombie.ZomboidFileSystem.instance.getMediaFile("ui/pzopt/props/props.txt");
         int max = 0;
         try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
               line = line.trim();
               if (line.isEmpty() || line.startsWith("#")) {
                  continue;
               }
               String[] p = line.split("\\s+");
               int cell = Integer.parseInt(p[1]);
               int cls = switch (p[2]) {
                  case "screen" -> SCREEN;
                  case "mirror" -> MIRROR;
                  case "steel" -> STEEL;
                  case "ceramic" -> CERAMIC;
                  default -> GLASS;
               };
               if (!enabledClass(cls)) {
                  continue;
               }
               float top = p.length > 6 ? Float.parseFloat(p[6]) : -1F; // (a "top" at the floor is a base or a shadow, not a surface people reflect in)
               // the reflective texels' box (frame fractions): the captured quad is cut down to it (mirrorsPropTightQuads)
               float bx0 = 0F, by0 = 0F, bx1 = 1F, by1 = 1F;
               if (p.length > 10) {
                  bx0 = Float.parseFloat(p[7]);
                  by0 = Float.parseFloat(p[8]);
                  bx1 = Float.parseFloat(p[9]);
                  by1 = Float.parseFloat(p[10]);
               }
               m.put(p[0], new float[] {cls, cell, "alpha".equals(p[3]) ? 1F : 0F, Integer.parseInt(p[4]), Float.parseFloat(p[5]), top >= 0.1F ? top : -1F, bx0, by0, bx1, by1});
               max = Math.max(max, cell);
            }
         }
         rows = max / COLS + 1;
         Log.info("mirrors: " + m.size() + " reflective props");
      } catch (Throwable t) {
         Log.warn("mirrors: no prop table (" + t + "): props stay stock");
      }
      table = m;
      return m;
   }

   private static boolean enabledClass(int cls) {
      if (cls == MIRROR && !Config.MIRRORS_WALL_MIRRORS) {
         return false; // the gym's wall mirrors go with the wall mirrors
      }
      String want = Config.MIRRORS_PROP_CLASSES;
      if (want.isBlank() || "all".equals(want)) {
         return true;
      }
      String name = switch (cls) {
         case SCREEN -> "screen";
         case MIRROR -> "mirror";
         case STEEL -> "steel";
         case CERAMIC -> "ceramic";
         default -> "glass";
      };
      return ("," + want + ",").contains("," + name + ",");
   }

   /** Render thread: the RG8 atlas (R face << 6 | plane offset, G reflectance). */
   static int loadAtlas() {
      int tex = GL11.glGenTextures();
      int w = 4, h = 4;
      ByteBuffer px = BufferUtils.createByteBuffer(w * h * 2);
      try {
         File f = zombie.ZomboidFileSystem.instance.getMediaFile("ui/pzopt/props/prop-atlas.png");
         java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
         w = img.getWidth();
         h = img.getHeight();
         px = BufferUtils.createByteBuffer(w * h * 2);
         java.awt.image.Raster r = img.getRaster();
         int[] a = new int[w], b = new int[w];
         for (int y = 0; y < h; y++) {
            r.getSamples(0, y, w, 1, 0, a);
            r.getSamples(0, y, w, 1, 1, b);
            for (int x = 0; x < w; x++) {
               px.put((byte)a[x]).put((byte)b[x]);
            }
         }
         px.flip();
      } catch (Throwable t) {
         Log.warn("mirrors: prop atlas unreadable: " + t);
         px.clear();
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG8, w, h, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, px);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
      Log.info("mirrors: prop atlas " + w + "x" + h);
      return tex;
   }
}
