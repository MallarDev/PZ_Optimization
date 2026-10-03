package pzopt;

import java.util.Locale;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL41;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCamera;

/**
 * The world pass at a fraction of the screen size (docs/plan-upscalers.md). The game renders the world through
 * an orthographic projection in world pixels into a viewport the size of the player's screen rectangle; shrinking
 * that viewport renders the same content into fewer pixels of the same offscreen texture, and {@link Upscaler}
 * resolves the small image back to the screen rectangle before the stock screen shader runs.
 *
 * <p>Everything here concerns the render thread only. The game thread keeps its full-size notion of the screen
 * (culling, chunk work, UI, mouse); the render thread sees the scaled rectangle wherever the world pass restores
 * a viewport (TextureDraw {@code glDoStartFrame} / {@code glDoStartFrameNoZoom} / {@code glViewport}) or asks
 * {@link IsoCamera} for the screen rectangle while the world framebuffer is bound.
 *
 * <p>A sub-pixel jitter (temporal upscalers) is applied as a fractional viewport offset
 * ({@code glViewportIndexedf}), which moves every draw of the frame whatever projection it uses.
 */
public final class RenderScale {
   private RenderScale() {
   }

   /** The upscaler chosen, lower case; "off" when none. The Enhancements tab changes it while the game runs ({@link #reconfigure}). */
   private static volatile String MODE = computeMode();
   /** Render scale per axis, 1.0 when off. */
   private static volatile float SCALE = computeScale();
   private static volatile boolean ACTIVE = computeActive();
   /** dynRes: the scale changes per frame (pzopt.DynRes); each thread then reads its own frame's scale. */
   private static volatile boolean DYNAMIC = ACTIVE && Overrides.enabled() && Config.DYN_RES;
   private static volatile float gameScale = SCALE; // dynRes: the scale of the frame the game thread builds
   private static float renderScale = SCALE; // dynRes: the scale of the frame the render thread replays (latched from the frame's marker)

   private static volatile boolean disabled; // a failure at run time (missing extension, shim, shader) switches the pass off until the settings change
   private static volatile String fallbackMode; // a temporal upscaler that cannot run (no RTX, no shim, no Vulkan) continues as fsr1 at the same scale

   static {
      logMode();
   }

   private static String computeMode() {
      if (!Overrides.enabled()) {
         return "off";
      }
      if (Config.DYN_RES && "off".equals(Config.UPSCALER)) {
         return switch (Config.DYN_RES_UPSCALER) { // dynRes needs a resolve; upscaler=off picks dynResUpscaler's
            case "bicubic" -> "bicubic";
            case "taau" -> "taau";
            default -> "fsr1";
         };
      }
      return Config.UPSCALER;
   }

   private static boolean computeActive() {
      if (Overrides.enabled() && Config.DYN_RES && !"off".equals(MODE)) {
         return true;
      }
      return !"off".equals(MODE) && SCALE < 1.0F || "dlss".equals(MODE) || "xess".equals(MODE) || "taau".equals(MODE);
   }

   private static void logMode() {
      if (ACTIVE && Overrides.enabled() && Config.DYN_RES) {
         Log.info("upscaler: " + MODE + " with dynamic resolution " + Config.DYN_RES_MIN_PCT + ".." + Config.DYN_RES_MAX_PCT + " % (" + Config.DYN_RES_CONTROLLER + ", target " + Config.DYN_RES_TARGET_PCT + " % of the frame interval)");
      } else if (ACTIVE) {
         Log.info("upscaler: " + MODE + " at " + Math.round(SCALE * 100.0F) + " % (" + Config.UPSCALER_QUALITY + (Config.UPSCALER_SCALE_PCT > 0 ? ", upscalerScalePct=" + Config.UPSCALER_SCALE_PCT : "") + ")");
      } else if (!"off".equals(MODE)) {
         Log.info("upscaler: " + MODE + " requested but the render scale is 100 %: off");
      } else {
         Log.info("upscaler: off");
      }
   }

   /**
    * An upscaler key changed on the Enhancements tab (game thread, after Config's live reload): the following frames
    * render at the new mode and scale. A failure or fallback of the old settings is forgotten (the new ones may work);
    * at its next frame start the render thread detaches a DLSS colour image the world may still draw into, releases the
    * old DLSS feature (built again at its next frame when DLSS is still the mode) and drops the sub-pixel jitter
    * ({@link #afterStartFrame}: a generation count, since a draw queued from the options screen's Apply can miss the
    * frame's list). The frame in flight may still use the old scale for its last draws.
    */
   static void reconfigure() {
      MODE = computeMode();
      SCALE = computeScale();
      ACTIVE = computeActive();
      DYNAMIC = ACTIVE && Overrides.enabled() && Config.DYN_RES;
      gameScale = SCALE;
      DynRes.reset();
      disabled = false;
      fallbackMode = null;
      logMode();
      generation++;
   }

   private static volatile int generation; // bumped by reconfigure (game thread)
   private static int appliedGeneration; // render thread

   // render-thread state
   private static boolean worldPass; // between a scaled glDoStartFrame with a player index and the next end-of-frame
   private static int worldPassPlayer = -1;
   private static int worldFboId; // the world framebuffer of that pass: the scaled view only applies while it is bound
   private static boolean suspendedQueued; // a queued suspend marker (thumbnail frames render unscaled)
   private static float jitterX; // pixels, the sub-pixel offset the next world pass starts with (temporal upscalers)
   private static float jitterY;
   private static float frameJitterX; // the offset the world pass being drawn was started with
   private static float frameJitterY;
   private static long frames;

   private static float computeScale() {
      if (Overrides.enabled() && Config.DYN_RES) {
         return Math.min(1.0F, Config.DYN_RES_MAX_PCT / 100.0F); // dynRes: the start (a frame's own scale comes from DynRes, up to dynamicMax)
      }
      if (!Overrides.enabled() || "off".equals(Config.UPSCALER)) {
         return 1.0F;
      }
      int pct = Config.UPSCALER_SCALE_PCT;
      if (pct >= 10 && pct <= 100) {
         return pct / 100.0F;
      }
      switch (Config.UPSCALER_QUALITY) {
         case "native": case "dlaa": case "100": return 1.0F;
         case "balanced": return 0.58F; // DLSS's balanced size (2970x1253 at 5120x2160)
         case "performance": return 0.5F;
         case "ultra": case "ultra-performance": case "ultraperformance": return 0.33333334F;
         case "quality": default: return 0.6666667F;
      }
   }

   /** The scaled world pass is on for this session (an upscaler is selected and nothing failed). */
   public static boolean active() {
      return ACTIVE && !disabled;
   }

   public static float scale() {
      if (!active()) {
         return 1.0F;
      }
      if (DYNAMIC) {
         return onRenderThread() ? renderScale : gameScale;
      }
      return SCALE;
   }

   /**
    * The horizontal factor of this frame's world image. With {@code dynResAxes=x} the scale only narrows the image:
    * the frame's pixel fraction s^2 is spent on the width alone (s^2 x 1) and the height stays native; otherwise s.
    */
   public static float scaleX() {
      float s = scale();
      return horizontalOnly() ? s * s : s;
   }

   /** The vertical factor of this frame's world image (1 with {@code dynResAxes=x}). */
   public static float scaleY() {
      return horizontalOnly() ? 1.0F : scale();
   }

   /** dynResAxes=x under dynRes, with an upscaler that takes any aspect (not DLSS: its sub-rectangle keeps the aspect). */
   static boolean horizontalOnly() {
      return DYNAMIC && Config.DYN_RES_AXES_X && !"dlss".equals(MODE) && !"xess".equals(MODE) && active();
   }

   /** dynRes is on and the scaled pass runs (the scale is per frame). */
   public static boolean dynamic() {
      return DYNAMIC && active();
   }

   /** dynRes, game thread: the scale of the frame now being built. */
   static void setGameScale(float s) {
      gameScale = s;
   }

   /** dynRes, render thread: the frame being replayed was built at this scale (its marker at the head of the draw list). */
   static void latchRenderScale(float s) {
      renderScale = s;
   }

   /**
    * dynResSharpenRamp: RCAS's strength as a factor of the configured one, 0 at native size rising to 1 at 85 %, so
    * the image does not jump in sharpness when the scale crosses into or out of the native bypass. 1 without dynRes.
    */
   public static float sharpenRamp() {
      if (!DYNAMIC || !Config.DYN_RES_SHARPEN_RAMP) {
         return 1.0F;
      }
      return Math.max(0.0F, Math.min(1.0F, (1.0F - scale()) / 0.15F));
   }

   /** The scale bakes plan their mip levels and AO sampling for: dynRes, the lowest scale it may use (the most minified). */
   public static float bakeScale() {
      return DYNAMIC && active() ? Config.DYN_RES_MIN_PCT / 100.0F : scale();
   }

   /** dynRes: this frame renders at the maximum scale and native resolution, the resolve can be skipped (dynResNativeBypass). */
   public static boolean nativeFrame() {
      return DYNAMIC && (Config.DYN_RES_NATIVE_BYPASS && scale() >= 0.9999F || scale() > 1.0001F); // supersampled: the stock composite shrinks it
   }

   public static String mode() {
      if (!active()) {
         return "off";
      }
      String f = fallbackMode;
      return f != null ? f : MODE;
   }

   /** The selected mode cannot run here: continue with another one at the same scale (logged once). */
   public static void fallback(String mode, String why) {
      if (fallbackMode == null && !mode.equals(MODE)) {
         fallbackMode = mode;
         Log.warn("upscaler: " + MODE + " unavailable (" + why + "); using " + mode + " at " + Math.round(scale() * 100.0F) + " %");
      }
   }

   /** A quality name for the log / overlay: "fsr1 67 %". */
   public static String describe() {
      return mode() + " " + Math.round(scale() * 100.0F) + " %";
   }

   /** Turn the pass off until the upscaler settings change (logged once). */
   public static void disable(String why) {
      if (!disabled) {
         disabled = true;
         worldPass = false;
         Log.warn("upscaler off: " + why);
      }
   }

   static int px(int screenPixels) {
      return Math.max(1, Math.round(screenPixels * scaleX()));
   }

   /**
    * A screen-pixel size or origin fed to a world-pass shader that maps gl_FragCoord through it (fog screenInfo /
    * cameraInfo on the game thread): scaled whenever the pass is active, since every world frame renders scaled.
    */
   public static float scaledPx(float screenPixels) {
      return active() ? screenPixels * scaleX() : screenPixels;
   }

   /** scaledPx for a vertical size or origin. */
   public static float scaledPxY(float screenPixels) {
      return active() ? screenPixels * scaleY() : screenPixels;
   }

   /** Same for a value computed on the render thread inside the world pass (water / puddle WViewport, particles). */
   public static float viewPx(float screenPixels) {
      return scaledView() ? screenPixels * scaleX() : screenPixels;
   }

   /** viewPx for a vertical size. */
   public static float viewPxY(float screenPixels) {
      return scaledView() ? screenPixels * scaleY() : screenPixels;
   }

   /** The view-cone blur's displaySize (VisibilityPolygon2): scaled like its screenSize / displayOrigin inside the scaled world pass. */
   public static float visBlurPx(float screenPixels) {
      return Config.DEV_UPSCALER_STOCK_VIS_BLUR ? screenPixels : viewPx(screenPixels);
   }

   public static float visBlurPxY(float screenPixels) {
      return Config.DEV_UPSCALER_STOCK_VIS_BLUR ? screenPixels : viewPxY(screenPixels);
   }

   static int pxFloor(int screenPixels) {
      return (int)(screenPixels * scaleX());
   }

   // --- render thread -------------------------------------------------------------------------------------------

   public static boolean onRenderThread() {
      return Thread.currentThread() == RenderThread.renderThread;
   }

   /** The render thread is inside a scaled world pass (the world framebuffer is bound and the viewport is scaled). */
   public static boolean inWorldPass() {
      return worldPass && active() && TextureFBO.lastID == worldFboId;
   }

   public static int worldPassPlayer() {
      return worldPassPlayer;
   }

   private static boolean worldFboBound(int player) {
      Core core = Core.getInstance();
      TextureFBO fbo = core.getOffscreenBuffer(player < 0 ? 0 : player);
      if (fbo == null || TextureFBO.lastID != fbo.getBufferId()) {
         return false;
      }
      worldFboId = fbo.getBufferId();
      return true;
   }

   /**
    * After the stock {@code DoStartFrameStuff} / {@code DoStartFrameNoZoom} of a world frame (render thread): when the
    * world framebuffer is bound the viewport and scissor become the scaled player rectangle. {@code player} is the
    * index the frame was started with (-1 = a full-screen frame, never scaled).
    */
   public static void afterStartFrame(int player) {
      if (appliedGeneration != generation) { // the upscaler settings changed: release what the old ones built
         appliedGeneration = generation;
         renderScale = SCALE;
         Dlss.reconfigure();
         Taau.invalidate();
         jitterX = 0.0F;
         jitterY = 0.0F;
      }
      if (player < 0 || !active()) {
         worldPass = false;
         return;
      }
      if (suspendedQueued || !worldFboBound(player)) {
         worldPass = false;
         return;
      }
      worldPass = true;
      worldPassPlayer = player;
      frameJitterX = jitterX;
      frameJitterY = jitterY;
      applyWorldViewport(player);
      Dlss.attachDirectColor(worldFboId); // dlssDirectColor: the world draws straight into the DLSS colour image
   }

   /**
    * After a model draw (render thread): ModelSlotRenderData.renderToImposterCard restores the viewport it read as
    * integers, which drops the fractional jitter for the rest of the frame; put the jittered viewport back.
    */
   public static void afterModelDraw() {
      if (worldPass && (frameJitterX != 0.0F || frameJitterY != 0.0F) && active() && TextureFBO.lastID == worldFboId) {
         int p = worldPassPlayer;
         float sx = scaleX(), sy = scaleY();
         GL41.glViewportIndexedf(0, (int)(fullLeft(p) * sx) + frameJitterX, (int)(fullTop(p) * sy) + frameJitterY, Math.max(1, Math.round(fullWidth(p) * sx)), Math.max(1, Math.round(fullHeight(p) * sy)));
      }
   }

   /** The stock end of a frame (render thread): the world pass is over until the next scaled start. */
   public static void afterEndFrame() {
      worldPass = false;
   }

   /** Sets the scaled viewport (with the current jitter) and scissor of a player's world rectangle. */
   public static void applyWorldViewport(int player) {
      int x = fullLeft(player), y = fullTop(player), w = fullWidth(player), h = fullHeight(player);
      float fx = scaleX(), fy = scaleY();
      int sx = (int)(x * fx), sy = (int)(y * fy);
      int sw = Math.max(1, Math.round(w * fx)), sh = Math.max(1, Math.round(h * fy));
      viewport(sx, sy, sw, sh);
      GL11.glScissor(sx, sy, sw, sh);
   }

   private static void viewport(int x, int y, int w, int h) {
      if (frameJitterX != 0.0F || frameJitterY != 0.0F) {
         GL41.glViewportIndexedf(0, x + frameJitterX, y + frameJitterY, w, h);
      } else {
         GL11.glViewport(x, y, w, h);
      }
   }

   /**
    * A viewport requested by the game thread (TextureDraw {@code glViewport}) while the render thread is in a scaled
    * world pass: a rectangle equal to a player's screen rectangle or to the whole screen is scaled like the world
    * viewport (IsoWorld's view-cone restore); anything else (the FX mask, the cone texture) passes through.
    */
   public static void requestedViewport(int x, int y, int w, int h) {
      if (inWorldPass() && isScreenRect(x, y, w, h)) {
         float fx = scaleX(), fy = scaleY();
         viewport((int)(x * fx), (int)(y * fy), Math.max(1, Math.round(w * fx)), Math.max(1, Math.round(h * fy)));
      } else {
         GL11.glViewport(x, y, w, h);
      }
   }

   private static boolean isScreenRect(int x, int y, int w, int h) {
      if (x == 0 && y == 0 && w == Core.width && h == Core.height) {
         return true;
      }
      for (int p = 0; p < IsoPlayer.numPlayers; p++) {
         if (x == fullLeft(p) && y == fullTop(p) && w == fullWidth(p) && h == fullHeight(p)) {
            return true;
         }
      }
      return false;
   }

   // The stock screen rectangle of a player (what IsoCamera returns on the game thread).
   static int fullLeft(int p) {
      return p == 1 || p == 3 ? Core.width / 2 : 0;
   }

   static int fullTop(int p) {
      return p == 2 || p == 3 ? Core.height / 2 : 0;
   }

   static int fullWidth(int p) {
      return IsoPlayer.numPlayers > 1 ? Core.width / 2 : Core.width;
   }

   static int fullHeight(int p) {
      return IsoPlayer.numPlayers > 2 ? Core.height / 2 : Core.height;
   }

   /** The rectangle a player's world image takes at scale {@code s}. */
   static int[] scaledRectAt(int p, float s) {
      return new int[]{(int)(fullLeft(p) * s), (int)(fullTop(p) * s), Math.max(1, Math.round(fullWidth(p) * s)), Math.max(1, Math.round(fullHeight(p) * s))};
   }

   /** The largest scale of the session (dynRes: dynResMaxPct within what the mode and the offscreen texture allow; else the fixed scale). */
   static float maxScale() {
      return DYNAMIC ? dynamicMax() : SCALE;
   }

   /**
    * dynRes: the highest scale. Above 1 (supersampling) only with fsr1 / bicubic, whose native-size path is the stock
    * composite (its bicubic then shrinks the larger image), and only as far as the offscreen texture (the next power of two
    * above the screen) reaches; DLSS and taau stop at the screen size.
    */
   static float dynamicMax() {
      float max = Config.DYN_RES_MAX_PCT / 100.0F;
      if (max <= 1.0F) {
         return max;
      }
      String m = mode();
      if (!"fsr1".equals(m) && !"bicubic".equals(m)) {
         return 1.0F;
      }
      TextureFBO fbo = Core.getInstance().getOffscreenBuffer();
      if (fbo == null || fbo.getTexture() == null || Core.width <= 0 || Core.height <= 0) {
         return 1.0F;
      }
      zombie.core.textures.Texture t = (zombie.core.textures.Texture)fbo.getTexture();
      float cap = Math.min((float)t.getWidthHW() / Core.width, (float)t.getHeightHW() / Core.height);
      return Math.max(1.0F, Math.min(max, cap));
   }

   /** The scaled rectangle (x, y, w, h) of a player's world image inside the offscreen texture. */
   public static int[] scaledRect(int p) {
      float sx = scaleX(), sy = scaleY();
      return new int[]{(int)(fullLeft(p) * sx), (int)(fullTop(p) * sy), Math.max(1, Math.round(fullWidth(p) * sx)), Math.max(1, Math.round(fullHeight(p) * sy))};
   }

   // IsoCamera override: the render thread inside a scaled world pass sees the scaled rectangle.
   public static boolean scaledView() {
      return worldPass && active() && TextureFBO.lastID == worldFboId && onRenderThread();
   }

   public static int screenLeft(int p) {
      return (int)(fullLeft(p) * scaleX());
   }

   public static int screenTop(int p) {
      return (int)(fullTop(p) * scaleY());
   }

   public static int screenWidth(int p) {
      return Math.max(1, Math.round(fullWidth(p) * scaleX()));
   }

   public static int screenHeight(int p) {
      return Math.max(1, Math.round(fullHeight(p) * scaleY()));
   }

   // --- jitter (temporal upscalers) -----------------------------------------------------------------------------

   /** The jitter of the frame being drawn, in low-res pixels; (0, 0) for spatial upscalers. */
   public static void setJitter(float x, float y) {
      jitterX = x;
      jitterY = y;
   }

   /** The jitter the frame now being resolved was drawn with. */
   public static float frameJitterX() {
      return frameJitterX;
   }

   public static float frameJitterY() {
      return frameJitterY;
   }

   // --- suspend markers (queued on the game thread, honoured by the render thread in order) ---------------------

   /** Queued before a frame that must render unscaled (save thumbnails). */
   public static void suspendQueued() {
      suspendedQueued = true;
   }

   public static void resumeQueued() {
      suspendedQueued = false;
   }

   /** Drawers the game thread queues around such a frame (SavefileThumbnail.create), in order with its draws. */
   public static final zombie.core.textures.TextureDraw.GenericDrawer SUSPEND = new zombie.core.textures.TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         suspendQueued();
      }
   };
   public static final zombie.core.textures.TextureDraw.GenericDrawer RESUME = new zombie.core.textures.TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         resumeQueued();
      }
   };

   /** One line for the console: mode and scale, or off. */
   public static String settingsLine() {
      return String.format(Locale.ROOT, "upscaler=%s scale=%.3f quality=%s dynRes=%s", mode(), scale(), Config.UPSCALER_QUALITY, DynRes.describe());
   }

   static long frames() {
      return frames;
   }

   static void countFrame() {
      frames++;
   }
}
