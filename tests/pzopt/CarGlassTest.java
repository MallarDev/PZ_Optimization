package pzopt;

/**
 * Car glass: the window zones of the vehicle mask (the stock shaders' zones 7-12, the texel colours with 0 / 0x7F
 * components) and the iso camera rotation the glass turns world directions with (DoPushIsoStuff: rotateX 30, rotateY 135;
 * GL world = (-x, z, -y)): the camera looks north-west and 30 degrees down.
 */
public class CarGlassTest {
   public static void main(String[] args) throws Exception {
      // vehicle_common.frag.h's 27 zones (FF = 255, C0 = 192, 7F = 127, 40 = 64); 7-12 are the windows
      int[][] zones = {{255, 0, 0}, {0, 255, 0}, {0, 255, 255}, {255, 255, 0}, {255, 0, 255}, {0, 0, 255}, {0, 127, 127}, {127, 127, 0},
         {127, 0, 127}, {0, 0, 127}, {127, 0, 0}, {0, 127, 0}, {0, 192, 192}, {192, 192, 0}, {192, 0, 192}, {0, 0, 192}, {0, 0, 0},
         {64, 0, 0}, {192, 0, 0}, {0, 192, 0}, {0, 64, 0}, {127, 64, 0}, {127, 192, 0}, {192, 192, 192}, {64, 64, 64}, {255, 0, 127},
         {0, 255, 127}};
      for (int i = 0; i < zones.length; i++) {
         boolean want = i >= 6 && i <= 11;
         Check.check(CarGlass.windowZone(zones[i][0], zones[i][1], zones[i][2]) == want, "zone " + (i + 1) + (want ? " is" : " is not") + " a window");
      }
      Check.check(CarGlass.windowZone(128, 128, 1), "a texel a step off still counts (the shaders' 0.01 test)");
      Check.check(!CarGlass.windowZone(100, 100, 0), "a texel far off does not");
      org.joml.Vector3f g = new org.joml.Matrix3f(CarGlass.ISO).transpose().transform(new org.joml.Vector3f(0F, 0F, -1F));
      float wx = -g.x, wy = -g.z, wz = g.y;
      Check.check(Math.abs(wx + 0.6124F) < 1e-3 && Math.abs(wy + 0.6124F) < 1e-3 && Math.abs(wz + 0.5F) < 1e-3,
         "the view runs north-west (-x, -y) and 30 degrees down: " + wx + " " + wy + " " + wz);
      // the glass map on the game's own textures (skipped without the game): the CarLuxury coupe's two rear quarter
      // windows (painted glass, unmarked in the mask, ~473 texels each) are extra glass; mirrors are found in door zones
      java.io.File dir = new java.io.File(System.getenv().getOrDefault("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid"), "media/textures/Vehicles");
      String[][] skins = {{"vehicle_luxurycar_mask", "vehicle_luxurycarshell"}, {"vehicle_carmodern_mask", "vehicle_moderncarshell"}, {"vehicle_sportscar_mask", "vehicle_sportscarshell"}};
      for (String[] sk : skins) {
         java.io.File mf = new java.io.File(dir, sk[0] + ".png"), df = new java.io.File(dir, sk[1] + ".png");
         if (!mf.exists() || !df.exists()) {
            continue;
         }
         java.awt.image.BufferedImage mi = javax.imageio.ImageIO.read(mf), di = javax.imageio.ImageIO.read(df);
         CarGlass.GlassMap gm = new CarGlass.GlassMap();
         byte[] cls = CarGlass.classify(rgba(mi), mi.getWidth(), mi.getHeight(), rgba(di), di.getWidth(), di.getHeight(), gm);
         int windows = 0;
         for (byte b : cls) {
            windows += b >= 1 && b <= 6 ? 1 : 0;
         }
         System.out.println("  " + sk[0] + ": window texels " + windows + ", extra glass " + gm.extra + ", mirror " + gm.mirrors);
         Check.check(windows > 1000, sk[0] + ": the window zones");
         Check.check(gm.mirrors > 100, sk[0] + ": mirror glass found in the door zones");
         if (sk[0].contains("luxury")) {
            Check.check(gm.extra > 800 && gm.extra < 2000, sk[0] + ": the two rear quarter windows are extra glass (" + gm.extra + ")");
         }
      }
      System.out.println("CarGlassTest ok");
   }

   private static java.nio.ByteBuffer rgba(java.awt.image.BufferedImage im) {
      int w = im.getWidth(), h = im.getHeight();
      java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(w * h * 4);
      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            int p = im.getRGB(x, y);
            b.put((byte)(p >> 16)).put((byte)(p >> 8)).put((byte)p).put((byte)(p >>> 24));
         }
      }
      b.flip();
      return b;
   }
}
