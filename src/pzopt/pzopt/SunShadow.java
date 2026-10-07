package pzopt;

import zombie.GameTime;
import zombie.iso.weather.ClimateManager;

/**
 * The sun of the soft sun shadows (Config {@code sunShadows}): its direction from the time of day and how much of the
 * outdoor light it carries from the daylight and the weather. The shadows themselves are computed by the chunk AO kernel
 * ({@link ChunkAo}) into the term it keeps per chunk texture, so a frame that bakes nothing pays nothing; when the sun
 * moves by {@code sunStepDeg10} tenths of a degree or its strength by a step, the loaded textures compute again (those
 * on screen first, a few a frame, each applied onto its colour as new / old).
 *
 * <p>The sun's path is HdrGlint's: east at 6 h, south at noon ({@code sunMaxElevationDeg} high), west at 18 h (PZ: +x
 * east, +y south), turned by {@code sunAzimuthDeg}. The kernel works in the chunk textures' orthographic view space
 * (x screen right, y screen up, z away from the camera, one unit = one square; a level is 2.449 squares tall), where the
 * world axes are the three plane normals the AO kernel snaps to.
 */
public final class SunShadow {
   /** World +x, +y and +z (one square of height) in the kernel's view space. */
   private static final double[] WX = {0.7071068, -0.3535534, -0.6123724};
   private static final double[] WY = {-0.7071068, -0.3535534, -0.6123724};
   private static final double[] WZ = {0.0, 0.8660254, -0.5};
   private static final int STRENGTH_STEPS = 32;

   /** Game thread (read when a compute is queued): the sun direction in view space (xyz) and the shadow strength (w, 0 = none). */
   static final float[] dir = new float[4];
   /** Across the sun direction and the view direction (xyz); w = tan of the sun's angular radius (the penumbra). */
   static final float[] perp = new float[4];
   /** The direction to the sun in world space (x east, y south, z up, one unit of height = one square), w = tan of the penumbra angle; strength in dir[3]. */
   static final float[] world = new float[4];
   // dir / perp / world above are the sun the picture shows (the per-frame passes: characters' and vehicles' shadows,
   // relief, canopy, the bare ground's share); cDir / cPerp / cWorld are the step the chunk kernel computes with. Without
   // sunStepSync they are the same; with it the shown sun moves when a step is applied to the chunk textures, eased over
   // sunStepFadeMs like them (the van's shadow switched at once while the ground waited for its step: a blink, 2026-10-07)
   static final float[] cDir = new float[4];
   static final float[] cPerp = new float[4];
   static final float[] cWorld = new float[4];
   private static final float[] SHOWN_FROM = new float[4], SHOWN_TO = new float[4]; // world xyz + strength
   private static long shownT0; // 0: no ease in progress
   private static boolean shownSet;
   private static long state = Long.MIN_VALUE;
   private static float hourNow = 12F;
   private static long changes;
   private static long lastStrengthStep;
   private static long lastStrengthDir;
   private static int devFarShadeLogged;
   private static long sweepT0;
   private static long toggleT0;
   private static long detailT0;
   private static int detailSoftness, detailFade, detailLogged;
   /** The unquantised strength of this frame's key light (cloud shadows follow it every frame). */
   static volatile float liveStrength;
   /** 0 = the sun is the key light, 1 = the moon. */
   static volatile int lightBody;
   private static final double[] stepDir = new double[3];
   private static int stepBody = -1;
   private static long stepSerial;

   private SunShadow() {
   }

   /** The key light's step (direction or body) counter: a sun shadow drawn under another step is stale. */
   static long stepSerial() {
      return stepSerial * 2L + stepBody + (long)(dir[3] * 1000F) * 131L
         + (long)(world[0] * 1e4F) * 7919L + (long)(world[1] * 1e4F) * 104729L + (long)(world[2] * 1e4F) * 1299709L;
   }

   static boolean enabled() {
      return Config.SUN_SHADOWS;
   }

   static String stats() {
      return String.format(java.util.Locale.ROOT, "%s: hour %.2f dir %.2f,%.2f,%.2f strength %.2f (live %.3f) penumbra %.3f changes %d | %s", lightBody == 0 ? "sun" : "moon", hourNow, dir[0], dir[1], dir[2], dir[3], liveStrength, perp[3], changes, Sky.stats());
   }

   /**
    * Game thread, once a frame: the sun of this hour and weather. Returns true when it moved or changed strength by a
    * step since the last call that returned true (the kept shadows are stale).
    */
   static boolean update() {
      if (Config.DEV_SUN_TOGGLE_PERIOD > 0) {
         // dev (the Workshop card's clip): sun shadows on and off every period from the first frame, every loaded chunk
         // picture baking again at each switch; the log line's epoch lets a card label the recording's frames
         long now = System.currentTimeMillis();
         if (toggleT0 == 0L) {
            toggleT0 = now;
         }
         boolean on = (now - toggleT0) / Config.DEV_SUN_TOGGLE_PERIOD % 2L == 0L;
         if (on != Config.SUN_SHADOWS) {
            Config.SUN_SHADOWS = on;
            ChunkAo.reconfigure();
            Log.info("sun shadows: dev toggle " + (on ? "on" : "off") + " at epoch_ms " + now);
         }
      }
      if (Config.DEV_DETAIL_TOGGLE_PERIOD > 0) {
         // dev (the "Pixel perfect shadows" card's clip): the detailed shadows and the previous release's take turns
         long now = System.currentTimeMillis();
         if (detailT0 == 0L) {
            detailT0 = now;
            detailSoftness = Config.SUN_SHADOW_SOFTNESS_PCT;
            detailFade = Config.SUN_SHADOW_STOCK_FADE_PCT;
         }
         boolean on = (now - detailT0) / Config.DEV_DETAIL_TOGGLE_PERIOD % 2L == 0L;
         if (on != Config.SUN_SHADOW_MESHES || detailLogged == 0) {
            Config.SUN_SHADOW_MESHES = on;
            Config.SUN_SHADOW_TREE_CARDS = on;
            Config.SUN_SHADOW_STOCK_FADE_PCT = on ? detailFade : 0;
            Config.SUN_SHADOW_SOFTNESS_PCT = on ? detailSoftness : 100;
            ChunkAo.reconfigure();
            detailLogged++;
            Log.info("detailed shadows: dev toggle " + (on ? "on" : "off") + " at epoch_ms " + now);
         }
      }
      if (!enabled()) {
         if (state != Long.MIN_VALUE) {
            state = Long.MIN_VALUE;
            dir[3] = 0F;
            cDir[3] = 0F;
            shownSet = false;
            shownT0 = 0L;
         }
         return false;
      }
      float hour = Config.DEV_SUN_HOUR >= 0F ? Config.DEV_SUN_HOUR : GameTime.getInstance() != null ? GameTime.getInstance().getTimeOfDay() : 12F;
      if (Config.DEV_SUN_HOUR >= 0F && Config.DEV_SUN_HOUR_SPEED > 0F) { // dev: the sun sweeps (worst case of the recompute waves)
         if (sweepT0 == 0L) {
            sweepT0 = System.nanoTime();
         }
         hour = Config.DEV_SUN_HOUR + Config.DEV_SUN_HOUR_SPEED * (System.nanoTime() - sweepT0) / 1e9F;
         hour = hour % 24F;
      }
      hourNow = hour;
      ClimateManager cm = ClimateManager.getInstance();
      float day = cm != null ? clamp01(cm.getDayLightStrength()) : 1F;
      float cloud = cm != null ? clamp01(cm.getCloudIntensity()) : 0F;
      float rain = cm != null ? clamp01(cm.getPrecipitationIntensity()) : 0F;
      float fog = cm != null ? clamp01(cm.getFogIntensity()) : 0F;
      boolean clouds = CloudShadow.enabled();
      // with cloud shadows the clouds shade where they are (the gaps keep the full sun); overcast leaves no gap
      float clear = clouds ? (1F - smooth(0.8F, 1.0F, cloud)) * (1F - 0.8F * rain) * (1F - 0.9F * fog)
         : (1F - 0.85F * cloud) * (1F - 0.8F * rain) * (1F - 0.9F * fog);
      double minElev = Math.toRadians(Math.max(1, Config.SUN_MIN_ELEVATION_DEG));
      double wx, wy, wz, elev;
      float s;
      int body; // 0 sun, 1 moon
      if ("arc".equals(Config.SKY_PATH)) {
         double a = Math.PI * (hour - 6.0) / 12.0; // 0 at 6 h (east), pi / 2 at noon (south), pi at 18 h (west)
         if (Config.SUN_STEP_MODE_ANGLE == 0) {
            double stepRad = Math.toRadians(Math.max(1, Config.SUN_STEP_DEG10) / 10.0);
            a = Math.round(a / stepRad) * stepRad; // the quantised sun: every compute of one step sees the same one
         }
         double sinA = Math.sin(a);
         elev = Math.max(0.0, sinA) * Math.toRadians(Math.max(5, Math.min(89, Config.SUN_MAX_ELEVATION_DEG)));
         double az = Math.toRadians(Config.SUN_AZIMUTH_DEG);
         double hx = Math.cos(a), hy = sinA; // towards the sun, seen from above (east at 6 h, south at noon)
         double rx = hx * Math.cos(az) - hy * Math.sin(az), ry = hx * Math.sin(az) + hy * Math.cos(az);
         double h = Math.sqrt(rx * rx + ry * ry);
         wx = rx / h * Math.cos(elev);
         wy = ry / h * Math.cos(elev);
         wz = Math.sin(elev);
         float rise = (float)Math.max(0.0, Math.min(1.0, (elev - minElev) / Math.toRadians(8.0)));
         s = day * clear * rise * Math.max(0, Config.SUN_SHADOW_STRENGTH_PCT) / 100F;
         body = 0;
      } else {
         Sky.update(Config.DEV_SUN_HOUR >= 0F ? hour : -1F);
         double sunEl = Math.toRadians(Sky.sunElevDeg);
         float sunRise = (float)Math.max(0.0, Math.min(1.0, (sunEl - minElev) / Math.toRadians(8.0)));
         float sunS = sunRise * clear * day * Math.max(0, Config.SUN_SHADOW_STRENGTH_PCT) / 100F;
         double moonEl = Math.toRadians(Sky.moonElevDeg);
         // the moon's shadows show once the sky is dark (the sun 4 deg under the horizon: none; 12 deg: all), as strong as
         // its brightness allows (the phase law: a quarter moon is ~a tenth of a full one; the shadows' contrast by its
         // square root), faded in over its first degrees above minElev like the sun's
         float dark = (float)Math.max(0.0, Math.min(1.0, (-Sky.sunElevDeg - 4.0) / 8.0));
         float moonRise = (float)Math.max(0.0, Math.min(1.0, (moonEl - minElev) / Math.toRadians(8.0)));
         float moonS = Config.MOON_SHADOWS ? dark * moonRise * clear * (float)Math.sqrt(Sky.moonBrightness)
            * Math.max(0, Config.SUN_SHADOW_STRENGTH_PCT) / 100F * Math.max(0, Config.MOON_SHADOW_PCT) / 100F : 0F;
         double[] w = sunS > 0F || moonS <= 0F ? Sky.sun : Sky.moon;
         body = w == Sky.sun ? 0 : 1;
         s = body == 0 ? sunS : moonS;
         wx = w[0];
         wy = w[1];
         wz = w[2];
         elev = body == 0 ? sunEl : moonEl;
      }
      if (Config.SUN_STEP_MODE_ANGLE != 0 || !"arc".equals(Config.SKY_PATH)) {
         // a new step once the light has turned sunStepDeg10 from the last step's direction (or changed body): the steps
         // come at the same angular spacing whatever the path (azimuth steps near a high noon sun would come far faster)
         // sunStepLowPct: near the horizon a shadow's length goes with 1 / tan(elevation), so a 1.5 deg step at 5 deg made
         // every long shadow 30 % shorter at once; the step shrinks to that share of the elevation (0.4 deg at 5 deg)
         double stepDeg = Math.max(1, Config.SUN_STEP_DEG10) / 10.0;
         if (Config.SUN_STEP_LOW_PCT > 0) {
            stepDeg = Math.min(stepDeg, Math.max(0.2, Math.toDegrees(Math.max(0.0, elev)) * Config.SUN_STEP_LOW_PCT / 100.0));
         }
         double cosStep = Math.cos(Math.toRadians(stepDeg));
         double dot = wx * stepDir[0] + wy * stepDir[1] + wz * stepDir[2];
         if (body != stepBody || dot < cosStep || stepSerial == 0L) {
            stepDir[0] = wx;
            stepDir[1] = wy;
            stepDir[2] = wz;
            stepBody = body;
            stepSerial++;
         }
         wx = stepDir[0];
         wy = stepDir[1];
         wz = stepDir[2];
         if (Config.SUN_SHADOW_REACH_FADE && Config.SUN_SHADOW_FAR && body == 0) {
            // a 3-level caster's shadow (7.35 squares tall) longer than the far field reaches would end on its fade: the
            // strength fades out over 85 -> 115 % of the reach instead (the stepped height, as the dusk fade below)
            double wl = Math.sqrt(wx * wx + wy * wy);
            double len = 7.35 * wl / Math.max(1e-4, wz);
            double reach = Math.min(ChunkAo.FAR_MARGIN, Config.SUN_SHADOW_FAR_SQUARES);
            s *= 1F - smooth(0.85F * (float)reach, 1.15F * (float)reach, (float)len);
         }
         if (Config.SUN_STEP_LOW_PCT > 0 && body == 0) {
            // the sun's fade near the horizon follows the step's height, not the live one: a direction step (longer
            // shadows, darker) and a fade step (fainter shadows, brighter) came as separate changes a few seconds apart
            float riseLive = (float)Math.max(0.0, Math.min(1.0, (elev - minElev) / Math.toRadians(8.0)));
            if (riseLive > 0F) {
               double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
               double stepEl = Math.asin(Math.max(-1.0, Math.min(1.0, wz / Math.max(1e-9, wl))));
               float riseStep = (float)Math.max(0.0, Math.min(1.0, (stepEl - minElev) / Math.toRadians(8.0)));
               s = s / riseLive * riseStep;
            }
         }
      }
      liveStrength = s;
      long sStep = Math.round(Math.min(1F, s) * STRENGTH_STEPS);
      // (in the fade band near the horizon the sun drives the strength: no hold there, the first change came as a double step)
      boolean fading = body == 0 && elev < minElev + Math.toRadians(8.0);
      if (sStep != 0L && lastStrengthStep > 0L && Math.abs(sStep - lastStrengthStep) < 2L
            && !(Config.SUN_STRENGTH_FINE && (fading || Long.signum(sStep - lastStrengthStep) == lastStrengthDir))) {
         sStep = lastStrengthStep; // hysteresis: clouds drifting across a step boundary would recompute every texture again and again
      }
      // sunStrengthFine: the hysteresis holds a turn back only; a strength going one way (the sun's fade at dusk and dawn)
      // moves a step at a time instead of jumps of two (a tenth of a long shadow's darkness at once)
      if (sStep != lastStrengthStep && lastStrengthStep > 0L && sStep != 0L) {
         lastStrengthDir = Long.signum(sStep - lastStrengthStep);
      }
      lastStrengthStep = sStep;
      float penumbra = (float)Math.tan(Math.toRadians(3.0 * Math.max(1, Config.SUN_SHADOW_SOFTNESS_PCT) / 100.0));
      double dh = Math.sqrt(wx * wx + wy * wy + wz * wz);
      wx /= dh;
      wy /= dh;
      wz /= dh;
      long aStep = stepSerial * 2L + stepBody;
      if ("arc".equals(Config.SKY_PATH) && Config.SUN_STEP_MODE_ANGLE == 0) {
         aStep = Math.round(Math.atan2(wz, Math.hypot(wx, wy)) * 1000.0) * 7919L + Math.round(Math.atan2(wy, wx) * 1000.0);
      }

      long next = sStep == 0L ? -1L : (aStep * 64L + sStep) * 1024L + Config.SUN_SHADOW_SOFTNESS_PCT;
      lightBody = body;
      if (next == state) {
         updateShown();
         return false;
      }
      boolean was = state != Long.MIN_VALUE;
      state = next;
      double vx = wx * WX[0] + wy * WY[0] + wz * WZ[0];
      double vy = wx * WX[1] + wy * WY[1] + wz * WZ[1];
      double vz = wx * WX[2] + wy * WY[2] + wz * WZ[2];
      put(wx, wy, wz, sStep / (float)STRENGTH_STEPS, penumbra, cDir, cWorld, cPerp);
      updateShown();
      changes++;
      if (!was || changes <= 3 || changes % 20 == 0) {
         Log.info("sun shadows: " + stats());
      }
      return was;
   }

   /** A sun (world direction, normalised; strength; penumbra) into its view-space direction, world and across arrays. */
   private static void put(double wx, double wy, double wz, float strength, float penumbra, float[] d, float[] w, float[] p) {
      double vx = wx * WX[0] + wy * WY[0] + wz * WZ[0];
      double vy = wx * WX[1] + wy * WY[1] + wz * WZ[1];
      double vz = wx * WX[2] + wy * WY[2] + wz * WZ[2];
      d[0] = (float)vx;
      d[1] = (float)vy;
      d[2] = (float)vz;
      d[3] = strength;
      w[0] = (float)wx;
      w[1] = (float)wy;
      w[2] = (float)wz;
      w[3] = penumbra;
      double pl = Math.sqrt(vx * vx + vy * vy); // cross(L, V) with V = (0, 0, -1): (-Ly, Lx, 0)
      p[0] = pl < 1e-4 ? 1F : (float)(-vy / pl);
      p[1] = pl < 1e-4 ? 0F : (float)(vx / pl);
      p[2] = 0F;
      p[3] = penumbra;
   }

   /** Is the shown sun synced to the chunk textures' applied steps (else it is the computed one)? */
   private static boolean shownFollowsTextures() {
      return Config.SUN_STEP_SYNC && ChunkAo.enabled();
   }

   /** Game thread, once a frame: the shown sun (the computed one, or the applied step's, eased). */
   private static void updateShown() {
      if (!shownFollowsTextures() || !shownSet) {
         put(cWorld[0], cWorld[1], cWorld[2], cDir[3], cWorld[3], dir, world, perp);
         SHOWN_TO[0] = cWorld[0];
         SHOWN_TO[1] = cWorld[1];
         SHOWN_TO[2] = cWorld[2];
         SHOWN_TO[3] = cDir[3];
         shownSet = cDir[3] > 0F || cWorld[2] != 0F;
         shownT0 = 0L;
         return;
      }
      if (shownT0 == 0L) {
         return;
      }
      float t = (System.nanoTime() - shownT0) / (1.0e6F * Math.max(1, Config.SUN_STEP_FADE_MS));
      float k = t >= 1F ? 1F : t * t * (3F - 2F * t);
      if (t >= 1F) {
         shownT0 = 0L;
      }
      double x = SHOWN_FROM[0] + (SHOWN_TO[0] - SHOWN_FROM[0]) * k, y = SHOWN_FROM[1] + (SHOWN_TO[1] - SHOWN_FROM[1]) * k,
         z = SHOWN_FROM[2] + (SHOWN_TO[2] - SHOWN_FROM[2]) * k;
      double l = Math.max(1e-9, Math.sqrt(x * x + y * y + z * z));
      put(x / l, y / l, z / l, SHOWN_FROM[3] + (SHOWN_TO[3] - SHOWN_FROM[3]) * k, cWorld[3], dir, world, perp);
   }

   /**
    * sunStepSync, game thread: the chunk textures now show the step computed with this sun (world xyz, strength): the shown
    * sun eases to it (at once without the composite's fade).
    */
   static void stepApplied(float[] w, float strength) {
      if (!shownFollowsTextures()) {
         return;
      }
      if (SHOWN_TO[0] == w[0] && SHOWN_TO[1] == w[1] && SHOWN_TO[2] == w[2] && SHOWN_TO[3] == strength) {
         return;
      }
      SHOWN_FROM[0] = world[0];
      SHOWN_FROM[1] = world[1];
      SHOWN_FROM[2] = world[2];
      SHOWN_FROM[3] = dir[3];
      SHOWN_TO[0] = w[0];
      SHOWN_TO[1] = w[1];
      SHOWN_TO[2] = w[2];
      SHOWN_TO[3] = strength;
      boolean ease = Config.SUN_STEP_FADE_MS > 0 && CloudShadow.stepFadeSupported() && dir[3] > 0F && strength > 0F;
      shownT0 = ease ? System.nanoTime() : 0L;
      if (!ease) {
         put(w[0], w[1], w[2], strength, cWorld[3], dir, world, perp);
      }
   }

   /** sunStepSync, game thread: no texture waits for a step (none on screen needs one): the shown sun takes the computed one. */
   static void syncShown() {
      if (shownFollowsTextures() && shownT0 == 0L) {
         stepApplied(cWorld, cDir[3]);
      }
   }

   // ------------------------------------------------------------------------------------------------ characters in the shade

   private static final int CACHE = 8192;
   // one word per slot: the key's hash in the high 32 bits, the visibility's float bits in the low 32. A worker and the
   // game thread may fill the cache at once (renderPrepParallel, the character draw pool): a single long store is atomic,
   // so a reader sees a key with its own value or a miss, never a neighbour's value under its key
   private static final long[] CACHE_E = new long[CACHE];
   private static final float LEVEL = 2.4494897F; // squares of height per level

   /**
    * sunShadows + sunShadowCharacters: the factor on a character's ambient light (ModelInstance.updateLights): 1 in the sun,
    * indoors and at night, 1 - strength in the static world's sun shadow. Any thread (the character draw pool): read-only
    * grid walks, a direct-mapped cache per half square and sun step (a lost race only recomputes or serves a neighbour's
    * value for a frame; the ambient eases over ~10 frames anyway).
    */
   public static float characterFactor(zombie.characters.IsoGameCharacter chr) {
      float s = dir[3];
      if (s <= 0F || !Config.SUN_SHADOWS || !Config.SUN_SHADOW_CHARACTERS || !Overrides.enabled()) {
         return 1F;
      }
      zombie.iso.IsoGridSquare sq = chr.getCurrentSquare();
      if (sq == null || !sq.isOutside()) {
         return 1F;
      }
      return 1F - s * (1F - visibleAt(chr.getX(), chr.getY(), chr.getZ()) * CloudShadow.transmittanceAt(chr.getX(), chr.getY(), chr.getZ()));
   }

   /** How much of the sun a point one square above (x, y, z) sees through the grid (walls, upper floors, solid objects, trees). */
   static float visibleAt(float x, float y, float z) {
      int hx = (int)Math.floor(x * 2F), hy = (int)Math.floor(y * 2F), iz = (int)Math.floor(z);
      long key = ((long)(hx & 0xFFFFF) << 40 | (long)(hy & 0xFFFFF) << 20 | (iz + 64) & 0xFF) * 31L + stepSerial() + (Config.SUN_SHARE_WALL_HEIGHT ? 0L : 0x5DEECE66DL);
      long h = key * 0x9E3779B97F4A7C15L;
      h ^= h >>> 31;
      int slot = (int)(h & (CACHE - 1));
      long tag = h & 0xFFFFFFFF00000000L;
      long e = CACHE_E[slot];
      if (e != 0L && (e & 0xFFFFFFFF00000000L) == tag) {
         return Float.intBitsToFloat((int)e);
      }
      float v = march((hx + 0.5F) * 0.5F, (hy + 0.5F) * 0.5F, iz, null);
      CACHE_E[slot] = tag | Float.floatToRawIntBits(v) & 0xFFFFFFFFL;
      return v;
   }

   /** Dev (devCasterTrace): visibleAt's march again, uncached, with what it hit. */
   static String devMarch(float x, float y, float z) {
      StringBuilder why = new StringBuilder();
      int hx = (int)Math.floor(x * 2F), hy = (int)Math.floor(y * 2F);
      float v = march((hx + 0.5F) * 0.5F, (hy + 0.5F) * 0.5F, (int)Math.floor(z), why);
      return String.format(java.util.Locale.ROOT, "march %.3f sun %.2f,%.2f,%.2f%s", v, world[0], world[1], world[2], why);
   }

   private static float march(float x, float y, int z, StringBuilder why) {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      float lx = world[0], ly = world[1], lz = world[2];
      if (cell == null || lz <= 0.02F) {
         return 1F;
      }
      float h = Math.max(0.2F, (float)Math.sqrt(lx * lx + ly * ly));
      float step = 0.34F / h; // a third of a square across the ground per step
      float pz0 = z * LEVEL + 1.0F; // chest height
      float vis = 1F;
      int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastL = Integer.MIN_VALUE;
      for (int i = 1; i <= 40; i++) {
         float t = i * step;
         float px = x + lx * t, py = y + ly * t, pz = pz0 + lz * t;
         int lvl = (int)Math.floor(pz / LEVEL);
         if (lvl > z + 4 || Math.abs(lx * t) > 9F || Math.abs(ly * t) > 9F) {
            break;
         }
         int sx = (int)Math.floor(px), sy = (int)Math.floor(py);
         if (sx == lastX && sy == lastY && lvl == lastL) {
            continue;
         }
         int prevX = lastX, prevY = lastY;
         lastX = sx;
         lastY = sy;
         lastL = lvl;
         zombie.iso.IsoGridSquare sq = cell.getGridSquare(sx, sy, lvl);
         if (sq == null) {
            continue;
         }
         float above = pz - lvl * LEVEL; // the ray's height inside this level
         if (lvl > z && sq.getFloor() != null) {
            if (why != null) why.append(" roof@").append(sx).append(',').append(sy).append(',').append(lvl);
            return 0F; // an upper floor or a roof of a building between us and the sun
         }
         // a wall on the edge the ray crossed into this square (N edge: coming from y - 1; W edge: from x - 1), or leaving it
         // (moving north / west: the wall sits on the square we left). sunShareWallHeight: only one standing above the ray,
         // its top measured from the sprite as for sunShadowWallCut; a hoppable fence 5 squares off under an evening sun
         // took the whole sun share and the character's shadow went out on part of every lap round it (2026-10-02)
         boolean crossN = prevY != Integer.MIN_VALUE && prevY < sy, crossW = prevX != Integer.MIN_VALUE && prevX < sx;
         boolean leaveN = prevY != Integer.MIN_VALUE && prevY > sy, leaveW = prevX != Integer.MIN_VALUE && prevX > sx;
         zombie.iso.IsoGridSquare from = leaveN || leaveW ? cell.getGridSquare(prevX, prevY, lvl) : null;
         boolean wall;
         float top = 0F;
         if (Config.SUN_SHARE_WALL_HEIGHT) {
            if (crossN) top = Math.max(top, CapsuleShadow.edgeTop(sq, true));
            if (crossW) top = Math.max(top, CapsuleShadow.edgeTop(sq, false));
            if (from != null && leaveN) top = Math.max(top, CapsuleShadow.edgeTop(from, true));
            if (from != null && leaveW) top = Math.max(top, CapsuleShadow.edgeTop(from, false));
            wall = top * LEVEL > above;
         } else {
            wall = crossN && (sq.has(zombie.iso.SpriteDetails.IsoFlagType.collideN) || sq.has(zombie.iso.SpriteDetails.IsoFlagType.WallN))
               || crossW && (sq.has(zombie.iso.SpriteDetails.IsoFlagType.collideW) || sq.has(zombie.iso.SpriteDetails.IsoFlagType.WallW));
            if (!wall && from != null) {
               wall = leaveN && (from.has(zombie.iso.SpriteDetails.IsoFlagType.collideN) || from.has(zombie.iso.SpriteDetails.IsoFlagType.WallN))
                  || leaveW && (from.has(zombie.iso.SpriteDetails.IsoFlagType.collideW) || from.has(zombie.iso.SpriteDetails.IsoFlagType.WallW));
            }
         }
         if (why != null && (wall || top > 0F)) {
            why.append(wall ? " wall@" : " under@").append(sx).append(',').append(sy).append(',').append(lvl).append(" from ").append(prevX).append(',').append(prevY)
               .append(String.format(java.util.Locale.ROOT, " ray %.2f top %.2f", above, top * LEVEL));
         }
         if (wall) {
            return 0F;
         }
         if ((sq.has(zombie.iso.SpriteDetails.IsoFlagType.solid) || sq.has(zombie.iso.SpriteDetails.IsoFlagType.solidtrans)) && above < 0.5F * LEVEL) {
            vis *= 0.25F;
            if (why != null) why.append(" solid@").append(sx).append(',').append(sy);
         }
         if (sq.getTree() != null && above > 0.3F * LEVEL) {
            vis *= 0.45F; // a crown lets some light through
            if (why != null) why.append(" tree@").append(sx).append(',').append(sy).append(String.format(java.util.Locale.ROOT, " h %.2f", above));
         }
         if (vis < 0.05F) {
            return 0F;
         }
      }
      // sunShadowFar: past the grid walk, the far field's column tops along the ray (a low sun's building shadow reaches
      // 60 squares; the character in it was lit beyond 9)
      if (Config.SUN_SHADOW_FAR && ChunkAo.enabled()) {
         float reach = Math.min(Config.SUN_SHADOW_FAR_SQUARES, ChunkAo.FAR_MARGIN);
         float tz = lz / h; // height gained per square along the ground
         int lastSx = Integer.MIN_VALUE, lastSy = Integer.MIN_VALUE;
         float occ = 0F; // soft, as the kernel's far field: a penumbra growing with the distance, the reach's last quarter fading
         for (float d = 9F; d <= reach; d += 0.7F) {
            float pz = pz0 + tz * d;
            if (pz > 24F) {
               break; // above anything the map holds
            }
            int sx = (int)Math.floor(x + lx / h * d), sy = (int)Math.floor(y + ly / h * d);
            if (sx == lastSx && sy == lastSy) {
               continue;
            }
            lastSx = sx;
            lastSy = sy;
            float top = ChunkAo.columnTop(sx, sy) * 0.25F * LEVEL;
            float pen = 0.3F + d * 0.013F;
            if (top > pz - pen) {
               occ = Math.max(occ, smooth(-pen, pen, top - pz) * (1F - smooth(0.75F * reach, reach, d)));
            }
            if (occ > 0.99F) {
               if (why != null) why.append(" far@").append(sx).append(',').append(sy);
               if (Config.DEV_FAR_SHADE_LOG > devFarShadeLogged) {
                  devFarShadeLogged++;
                  Log.info(String.format(java.util.Locale.ROOT, "sun shadows: dev far shade at %.1f,%.1f,%d hit %d,%d top %.2f ray %.2f d %.1f sun %.3f,%.3f,%.3f",
                     x, y, z, sx, sy, top, pz, d, lx, ly, lz));
               }
               break;
            }
         }
         vis *= 1F - occ;
      }
      return vis;
   }

   private static float smooth(float e0, float e1, float x) {
      float t = clamp01((x - e0) / (e1 - e0));
      return t * t * (3F - 2F * t);
   }

   private static float clamp01(float v) {
      return Math.max(0F, Math.min(1F, v));
   }
}
