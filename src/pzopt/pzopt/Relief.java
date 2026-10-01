package pzopt;

import org.lwjgl.opengl.GL20;
import zombie.core.SpriteRenderer;

/**
 * Relief ("parallax textures", Config {@code relief}): the fine relief the art paints (mortar between bricks, gaps
 * between planks and floor tiles, cobbles, bark) as a height field, so light that moves (the torch, lamps, fires,
 * headlights, the sun and the moon) rakes it: the grooves face away from it and fall into shadow. The camera never turns
 * (an orthographic view from a fixed direction), so the view-dependent half of parallax mapping (the texture coordinate
 * shift of parallax offset / occlusion mapping) is already in the art; what changes on screen is the light, and that
 * is what the height is for: a normal per texel and self-shadowing along the light.
 *
 * <p>The height comes from the chunk texture's own colour where it is composited (pixelLight bakes it unlit: the albedo),
 * no extra texture, no bake work: {@code reliefHeight} picks the estimate (lum: brighter is higher, groove: the colour's
 * distance from its 4x4 texel neighbourhood's mean is a groove, mix). The tangent frame is the texel's surface plane from
 * the chunk depth (pixelLight's texel normal); a texel whose neighbours lie on another surface (a depth step) takes a
 * one-sided difference, so the edge of a wall against the floor does not become a ridge.
 */
public final class Relief {
   private Relief() {
   }

   /** Relief shading is compiled into pixelLight's chunk composite programs (the texel normal there feeds every dynamic light). */
   static boolean on() {
      return Config.RELIEF && Config.PIXEL_LIGHT && Config.PPL_TEXEL_POS && Overrides.enabled();
   }

   static int heightMode() {
      switch (Config.RELIEF_HEIGHT) {
         case "lum":
            return 0;
         case "groove":
            return 1;
         default:
            return 2;
      }
   }

   private static volatile boolean sunPatched;
   private static long sunPatchedPrograms, sunDraws;

   /** reliefSunMode=composite: the sun / moon relief as a post-main of every chunk composite program (+86 us at 5K on the 4090: every fragment fetches its code), needs the cloud shadows' direct-sun share; the default bakes it (ReliefAux). */
   static boolean sunWanted() {
      return Config.RELIEF && Config.RELIEF_SUN_PCT > 0 && "composite".equals(Config.RELIEF_SUN_MODE) && Config.SUN_SHADOWS && Config.CLOUD_SHADOWS && !CoreGl.legacyMac() && Overrides.enabled();
   }

   private static volatile boolean shareWanted;

   /** Render thread (CloudShadow.chunkDraw): the sun post-main reads the texture's direct-sun share this frame. */
   static boolean wantsSunShare() {
      return sunPatched && shareWanted;
   }

   public static String stats() {
      return "relief: " + (Config.RELIEF ? "on" : "off") + ", torch / lamps " + (on() ? "in pixelLight's composite" : "off") + ", sun " + (sunPatched ? "patched into " + sunPatchedPrograms + " programs, draws " + sunDraws : "off") + (Config.RELIEF_AUX ? " | " + ReliefAux.stats() : "");
   }

   /** The height estimate's defines (every program with relief code). */
   private static String reliefDefines() {
      StringBuilder d = new StringBuilder();
      d.append("#define RELIEF_H ").append(heightMode()).append('\n');
      d.append("#define RELIEF_S ").append(String.format(java.util.Locale.ROOT, "%.5f", 0.05F * Config.RELIEF_DEPTH_PCT / 100.0F)).append('\n');
      d.append("#define RELIEF_GMAX ").append(String.format(java.util.Locale.ROOT, "%.3f", Config.RELIEF_GMAX_PCT / 100.0F)).append('\n');
      d.append("#define RELIEF_SNAP ").append(String.format(java.util.Locale.ROOT, "%.3f", Config.RELIEF_SNAP_PCT / 100.0F)).append('\n');
      if (Config.RELIEF_AUX) {
         d.append("#define RELIEF_AUX\n"); // the relief from each texture's code (ReliefAux): one fetch
         if (Config.RELIEF_HORIZON) {
            d.append("#define RELIEF_HORIZON\n");
            d.append("#define RELIEF_HORIZON_TAN ").append(String.format(java.util.Locale.ROOT, "%.3f", ReliefAux.HORIZON_TAN_MAX)).append('\n');
            d.append("#define RELIEF_SHADOW_K ").append(String.format(java.util.Locale.ROOT, "%.3f", Config.RELIEF_SHADOW_PCT / 100.0F)).append('\n');
         } else if (Config.RELIEF_TORCH_SHADOW_STEPS > 0) {
            d.append("#define RELIEF_TORCH_SHADOW ").append(Config.RELIEF_TORCH_SHADOW_STEPS).append('\n');
            d.append("#define RELIEF_SHADOW_K ").append(String.format(java.util.Locale.ROOT, "%.3f", Config.RELIEF_SHADOW_PCT / 100.0F)).append('\n');
         }
      }
      if (Config.RELIEF_SHADOW_STEPS > 0 && !Config.RELIEF_AUX) {
         d.append("#define RELIEF_SHADOW ").append(Config.RELIEF_SHADOW_STEPS).append('\n');
         d.append("#define RELIEF_SHADOW_K ").append(String.format(java.util.Locale.ROOT, "%.3f", Config.RELIEF_SHADOW_PCT / 100.0F)).append('\n');
      }
      if (Config.RELIEF_OBJECTS) {
         d.append("#define RELIEF_OBJECTS\n");
      }
      return d.toString();
   }

   /** The defines every chunk composite program gets (empty when relief is off). */
   static String defines() {
      if (!on()) {
         return "";
      }
      StringBuilder d = new StringBuilder("#define PPL_RELIEF\n").append(reliefDefines());
      if (Config.DEV_RELIEF_VIEW != 0) {
         d.append("#define RELIEF_VIEW ").append(Config.DEV_RELIEF_VIEW).append('\n');
      }
      return d.toString();
   }

   /**
    * GLSL, after DIFFUSE / DEPTH and pplPos are declared: the relief normal of a texel. {@code n0} is the plane normal
    * (snapped), {@code tu} / {@code tv} the world step (z in squares) of one texel along +u / +v on that plane.
    */
   /** The uniforms both the texel normal and the sun's post-main read (declared once). */
   static final String UNIFORMS = String.join("\n",
      "#ifndef PZR_UNIFORMS",
      "#define PZR_UNIFORMS",
      "uniform vec4 pzRlfK;", // x - y per texel along u, x + y - 6z per texel along v (signed), x + y + 2z per unit of depth, w 1: this draw's texture has its relief code
      "uniform sampler2D pzRlfAuxTex;", // ReliefAux.AUX_UNIT (set by Relief.chunkDraw: the game renumbers sampler2D units after the link)
      "#endif");

   static final String GLSL = String.join("\n",
      "#ifdef PPL_RELIEF",
      UNIFORMS,
      "vec3 rlfN0;", // the plane normal under the relief (dev views, the self-shadow ray)
      "float rlfH0;", // the height at the texel (the mean of the four 2x2 blocks around it)
      "vec3 rlfTu, rlfTv;", // world steps (z in squares) of one texel along u and v on the plane
      "vec2 rlfC;", // the texel's centre, texels
      "vec2 rlfIs;", // 1 / the texture's size
      "float rlfS = 0.0;", // the relief depth this pixel got (0: none; rlfShadow does nothing)
      "vec2 rlfG0;", // the texel's slopes and plane (the torch's self-shadow ray on the codes)
      "float rlfCls;",
      "ivec2 rlfTi;",
      // the height of a 2x2 texel block around a texel corner: the art's green (the channel luminance leans on; a textureGather
      // is one fetch), their mean (the art's one-texel dither is noise, not relief)
      "float rlfBlock(vec2 corner) { return dot(textureGather(DIFFUSE, corner * rlfIs, 1), vec4(0.25)); }",
      // n: the texel normal (snapped, or the raw one near a plane); ti: the texel; dA / dB: x - y and x + y - 6z per texel
      // along u / v (signed); planar: the depth around the texel is one plane (no edge); fade: 1 at a texel a window pixel
      "vec3 rlfNormal(vec3 n, ivec2 ti, vec2 size, float dA, float dB, bool planar, float fade) {",
      "   rlfS = 0.0; rlfH0 = 0.0; rlfN0 = n;",
      "   if (fade <= 0.0 || !planar) return n;",
      // the plane's exact tangents (the depth's DEPTH16 steps over two texels tilt a wall's reconstructed normal ~20
      // degrees: a relaxed snap, then the plane's own geometry)
      "   const float L6 = 2.4494897 / 6.0;", // squares per level / 6
      "   vec3 n0, tu, tv;",
      "   if (n.z > RELIEF_SNAP) { n0 = vec3(0.0, 0.0, 1.0); tu = vec3(0.5 * dA, -0.5 * dA, 0.0); tv = vec3(0.5 * dB, 0.5 * dB, 0.0); }",
      "   else if (n.x > RELIEF_SNAP) { n0 = vec3(1.0, 0.0, 0.0); tu = vec3(0.0, -dA, -dA * L6); tv = vec3(0.0, 0.0, -dB * L6); }",
      "   else if (n.y > RELIEF_SNAP) { n0 = vec3(0.0, 1.0, 0.0); tu = vec3(dA, 0.0, dA * L6); tv = vec3(0.0, 0.0, -dB * L6); }",
      "   else return n;", // furniture, slopes: their box
      "   rlfIs = 1.0 / size;",
      "   vec2 c = vec2(ti);",
      "   float tl = rlfBlock(c), tr = rlfBlock(c + vec2(1.0, 0.0)), bl = rlfBlock(c + vec2(0.0, 1.0)), br = rlfBlock(c + vec2(1.0, 1.0));",
      "   float gu = 0.5 * (tr + br - tl - bl), gv = 0.5 * (bl + br - tl - tr);",
      // a soft limit: the art's relief is a gentle slope; a step to another sprite or to an empty texel (a sprite's outline,
      // a leaf against the ground) is an edge, not a cliff
      "   float gl = length(vec2(gu, gv));",
      "   float lim = RELIEF_GMAX / (RELIEF_GMAX + gl);",
      "   gu *= lim; gv *= lim;",
      "   float s = RELIEF_S * fade;",
      "   rlfN0 = n0; rlfTu = tu; rlfTv = tv; rlfC = c + 0.5; rlfS = s; rlfH0 = 0.25 * (tl + tr + bl + br);",
      "   vec3 nr = cross(tu + n0 * (s * gu), tv + n0 * (s * gv));",
      "   float l = length(nr);",
      "   if (l < 1e-12) return n0;",
      "   nr /= l;",
      "   return dot(nr, n0) < 0.0 ? -nr : nr;",
      "}",
      "#ifdef RELIEF_AUX",
      // the texel's relief from its code (ReliefAux): false = none (an object, an edge, an empty texel)
      // a code's plane (float math: integer division is slow on GPUs): 0 floor, 1 the wall facing +x, 2 the wall facing +y
      "float rlfClass(float v) { return floor((v - 0.5) * (1.0 / 81.0)); }",
      "vec3 rlfPlane(float cls) { return cls < 0.5 ? vec3(0.0, 0.0, 1.0) : cls < 1.5 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0); }",
      // the relief normal from a code v (1..244, the byte x 255) of plane cls
      "vec3 rlfDecode(float v, float cls, float fade) {",
      "   float r = v - 1.0 - cls * 81.0;",
      "   float ku = floor((r + 0.5) * (1.0 / 9.0));",
      "   vec2 t = vec2(ku - 4.0, r - ku * 9.0 - 4.0) * 0.25;",
      "   vec2 g = sign(t) * t * t * RELIEF_GMAX;",
      "   float dA = pzRlfK.x, dB = pzRlfK.y;",
      "   const float L6 = 2.4494897 / 6.0;",
      "   vec3 n0 = rlfPlane(cls), tu, tv;",
      "   if (cls < 0.5) { tu = vec3(0.5 * dA, -0.5 * dA, 0.0); tv = vec3(0.5 * dB, 0.5 * dB, 0.0); }",
      "   else if (cls < 1.5) { tu = vec3(0.0, -dA, -dA * L6); tv = vec3(0.0, 0.0, -dB * L6); }",
      "   else { tu = vec3(dA, 0.0, dA * L6); tv = vec3(0.0, 0.0, -dB * L6); }",
      "   float s = RELIEF_S * fade;",
      "   rlfN0 = n0; rlfS = s; rlfTu = tu; rlfTv = tv; rlfG0 = g; rlfCls = cls;",
      "   vec3 nr = normalize(cross(tu + n0 * (s * g.x), tv + n0 * (s * g.y)));",
      "   return dot(nr, n0) < 0.0 ? -nr : nr;",
      "}",
      // the texel's relief from its code (ReliefAux): false = none (an object, an edge, an empty texel)
      "bool rlfCode(ivec2 ti, float fade, out vec3 nr) {",
      "   rlfS = 0.0; rlfN0 = vec3(0.0, 0.0, 1.0); nr = rlfN0; rlfTi = ti;",
      "   float v = texelFetch(pzRlfAuxTex, ti, 0).r * 255.0;",
      "   if (v < 0.5 || fade <= 0.0) return false;",
      "   nr = rlfDecode(v, rlfClass(v), fade);",
      "   return true;",
      "}",
      "#ifdef RELIEF_HORIZON",
      // the torch's self-shadow from the texel's horizons (ReliefAux's RGBA4: tan of the horizon along +u, -u, +v, -v), one
      // fetch: the horizon along the light's direction across the plane (the two axes around it, weighted by cos^2) against
      // the light's elevation
      "uniform sampler2D pzRlfHorTex;",
      "float rlfHorizon(vec3 ld) {",
      "   if (rlfS <= 0.0) return 1.0;",
      "   float ln = dot(ld, rlfN0);",
      "   if (ln <= 0.0) return 1.0;",
      "   vec3 lp = ld - rlfN0 * ln;",
      "   float lpl = length(lp);",
      "   if (lpl < 1e-5) return 1.0;",
      "   float g00 = dot(rlfTu, rlfTu), g01 = dot(rlfTu, rlfTv), g11 = dot(rlfTv, rlfTv);",
      "   vec2 st = vec2(g11 * dot(rlfTu, lp) - g01 * dot(rlfTv, lp), g00 * dot(rlfTv, lp) - g01 * dot(rlfTu, lp));",
      "   st /= max(length(st), 1e-9);",
      "   vec4 hz = texelFetch(pzRlfHorTex, rlfTi, 0) * RELIEF_HORIZON_TAN * (rlfS / RELIEF_S);", // the fade scales the heights
      "   float H = (st.x >= 0.0 ? hz.x : hz.y) * st.x * st.x + (st.y >= 0.0 ? hz.z : hz.w) * st.y * st.y;",
      "   float E = ln / lpl;", // tan of the light's elevation over the plane
      "   return mix(1.0, smoothstep(H - 0.08, H + 0.08, E), RELIEF_SHADOW_K);",
      "}",
      "#endif",
      "#ifdef RELIEF_TORCH_SHADOW",
      // the torch's self-shadow ray on the codes: the height along the light rebuilt from the stored slopes (the baked sun's
      // ray, per fragment); ld: towards the light (world, z in squares)
      "float rlfShadowCode(vec3 ld) {",
      "   if (rlfS <= 0.0) return 1.0;",
      "   float ln = dot(ld, rlfN0);",
      "   if (ln <= 0.0) return 1.0;",
      "   vec3 lp = ld - rlfN0 * ln;",
      "   float g00 = dot(rlfTu, rlfTu), g01 = dot(rlfTu, rlfTv), g11 = dot(rlfTv, rlfTv);",
      "   vec2 st = vec2(g11 * dot(rlfTu, lp) - g01 * dot(rlfTv, lp), g00 * dot(rlfTv, lp) - g01 * dot(rlfTu, lp)) / (g00 * g11 - g01 * g01);",
      "   float m = max(abs(st.x), abs(st.y));",
      "   if (m < 1e-6) return 1.0;",
      "   st /= m;",
      "   float rise = length(st.x * rlfTu + st.y * rlfTv) * ln / max(length(lp), 1e-6);",
      "   if (rise * float(RELIEF_TORCH_SHADOW) > rlfS * 2.0 * RELIEF_GMAX * float(RELIEF_TORCH_SHADOW)) return 1.0;", // too steep for any groove to shade
      "   ivec2 mx = textureSize(pzRlfAuxTex, 0) - 1;",
      "   vec2 p = vec2(rlfTi) + 0.5, gp = rlfG0;",
      "   float h = 0.0, vis = 1.0;",
      "   for (int k = 1; k <= RELIEF_TORCH_SHADOW; k++) {",
      "      p += st;",
      "      float vk = texelFetch(pzRlfAuxTex, clamp(ivec2(floor(p)), ivec2(0), mx), 0).r * 255.0;",
      "      if (vk < 0.5 || abs(rlfClass(vk) - rlfCls) > 0.5) break;",
      "      float c = rlfCls, r = vk - 1.0 - c * 81.0, ku = floor((r + 0.5) * (1.0 / 9.0));",
      "      vec2 t = vec2(ku - 4.0, r - ku * 9.0 - 4.0) * 0.25;",
      "      vec2 gk = sign(t) * t * t * RELIEF_GMAX;",
      "      h += 0.5 * dot(gp + gk, st);",
      "      gp = gk;",
      "      vis = min(vis, 1.0 - smoothstep(0.0, 0.25 * rlfS, rlfS * h - rise * float(k)));",
      "   }",
      "   return mix(1.0, vis, RELIEF_SHADOW_K);",
      "}",
      "#endif",
      "#endif",
      "#ifdef RELIEF_SHADOW",
      // self-shadowing (parallax occlusion mapping's shadow ray, Tatarchuk 2006): from the texel towards the light across the
      // plane, RELIEF_SHADOW texels, the art's height (2x2 blocks) against the ray's; ld: towards the light (world, z in squares)
      "float rlfShadow(vec3 ld) {",
      "   if (rlfS <= 0.0) return 1.0;",
      "   float ln = dot(ld, rlfN0);",
      "   if (ln <= 0.0) return 1.0;", // behind the plane: the facing term already took it
      "   vec3 lp = ld - rlfN0 * ln;",
      "   float g00 = dot(rlfTu, rlfTu), g01 = dot(rlfTu, rlfTv), g11 = dot(rlfTv, rlfTv);",
      "   float r0 = dot(rlfTu, lp), r1 = dot(rlfTv, lp);",
      "   float det = g00 * g11 - g01 * g01;",
      "   if (abs(det) < 1e-20) return 1.0;",
      "   vec2 st = vec2(g11 * r0 - g01 * r1, g00 * r1 - g01 * r0) / det;", // texels per unit of the in-plane light direction
      "   float m = max(abs(st.x), abs(st.y));",
      "   if (m < 1e-6) return 1.0;", // the light straight above
      "   st /= m;", // one texel a step along the major axis
      "   float rise = length(st.x * rlfTu + st.y * rlfTv) * ln / max(length(lp), 1e-6);", // squares the ray climbs a step
      "   float vis = 1.0;",
      "   for (int k = 1; k <= RELIEF_SHADOW; k++) {",
      "      float occ = rlfS * (rlfBlock(rlfC + st * float(k)) - rlfH0) - rise * float(k);",
      "      vis = min(vis, 1.0 - smoothstep(0.0, 0.25 * rlfS, occ));",
      "   }",
      "   return mix(1.0, vis, RELIEF_SHADOW_K);",
      "}",
      "#endif",
      "#endif");

   // ------------------------------------------------------------------------------------------------ sun / moon relief

   /**
    * ShaderUnit hook, right after the cloud shadows' patch (which gives every chunk composite program the texture's
    * direct-sun share, {@code pzCloudE} / {@code PZC_TERM}): the composite's main is renamed and a new one relights the
    * pixel's direct-sun share by the relief: the texel's plane from the chunk depth, the art's height across it, the key light
    * (the sun or the moon, {@link Sky}, every frame, no re-bakes) facing it and its self-shadow ray. Stock composite and
    * pixelLight's programs alike.
    */
   public static String patchComposite(String fileName, String code) {
      if (fileName == null || code == null || !sunWanted()) {
         return code;
      }
      String f = fileName.replace('\\', '/');
      if (!(f.endsWith("/chunkShader.frag") || f.endsWith("/pzopt_chunkBase.frag") || f.endsWith("/pzopt_chunkStock.frag"))) {
         return code;
      }
      if (!code.contains("#define PZC_OUT") || !code.contains("void main()")) {
         Log.warn("relief: " + fileName + " has no cloud shadows' direct-sun share (cloudShadows off?), no sun relief there");
         return code;
      }
      int nl = code.indexOf('\n');
      String first = code.substring(0, nl).trim();
      String head = first.equals("#version 120") ? "#version 420 compatibility" : first; // textureGather, textureSize, texelFetch
      // the relief functions come with pixelLight's programs when its relief is compiled in (PPL_RELIEF), else here, after
      // the program's own code (a define at the top would switch on pixelLight's copy as well)
      String c = head + "\n" + (Config.DEV_RELIEF_VIEW != 0 ? "#define PZR_VIEW " + Config.DEV_RELIEF_VIEW + "\n" : "") + "#define PZR_SKIP " + Config.DEV_RELIEF_SKIP + "\n"
         + code.substring(nl).replace("void main()", "void pzReliefInner()") + "\n#ifndef PPL_RELIEF\n#define PPL_RELIEF\n" + reliefDefines() + GLSL + "\n#endif\n" + SUN_GLSL + "\n";
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, c);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("relief: " + fileName + " sun patch does not compile, stays as it was: " + log);
         return code;
      }
      sunPatched = true;
      sunPatchedPrograms++;
      Log.info("relief: sun / moon relief patched into " + fileName);
      return c;
   }

   private static final java.util.HashMap<Integer, int[]> LOCATIONS = new java.util.HashMap<>();
   private static final java.util.HashMap<Integer, float[]> LAST = new java.util.HashMap<>();

   /**
    * ChunkRenderShader.startRenderThread, after the cloud shadows (the variant program for this texture is bound): this
    * texture's relief code on its unit (or none) and the key light (set when they change).
    */
   public static void chunkDraw(zombie.core.textures.TextureDraw texd) {
      if (!sunPatched && !on()) {
         return;
      }
      boolean off = renderDevOff; // devReliefAlternate: this frame draws without relief (the same programs, no code bound, no key light)
      try {
         int prog = Ssr.boundProgram();
         int[] l = LOCATIONS.get(prog);
         if (l == null) {
            l = new int[] {GL20.glGetUniformLocation(prog, "pzRlfSun"), GL20.glGetUniformLocation(prog, "pzRlfK"), GL20.glGetUniformLocation(prog, "pzRlfAuxTex"),
               GL20.glGetUniformLocation(prog, "pzRlfHorTex")};
            LOCATIONS.put(prog, l);
            if (l[2] >= 0) {
               GL20.glUniform1i(l[2], ReliefAux.AUX_UNIT);
            }
            if (l[3] >= 0) {
               GL20.glUniform1i(l[3], ReliefAux.HORIZON_UNIT);
            }
         }
         if (l[1] < 0) {
            return; // not a program with relief
         }
         float[] last = LAST.get(prog);
         if (last == null) {
            LAST.put(prog, last = new float[] {Float.NaN, 0F, 0F, 0F, -1F});
         }
         float aux = Config.RELIEF_AUX && !off && ReliefAux.bind(texd) != 0 ? 1F : 0F;
         if (aux != last[4]) {
            last[4] = aux;
            float ts = zombie.core.Core.tileScale;
            GL20.glUniform4f(l[1], 1F / (32F * ts), (Config.AO_CHUNK_FLIP ? 1F : -1F) / (16F * ts), (float)(-1.0 / PixelLight.DEPTH_PER_XY), aux);
         }
         if (l[0] < 0) {
            return;
         }
         sunDraws++;
         double[] w = SunShadow.lightBody == 0 ? Sky.sun : Sky.moon;
         double len = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
         float k = !off && SunShadow.liveStrength > 0F && len > 0.0 && w[2] > 0.0 ? Config.RELIEF_SUN_PCT / 100F : 0F;
         float x = k > 0F ? (float)(w[0] / len) : 0F, y = k > 0F ? (float)(w[1] / len) : 0F, z = k > 0F ? (float)(w[2] / len) : 0F;
         shareWanted = k > 0F || Config.DEV_RELIEF_VIEW != 0;
         if (last[0] != x || last[1] != y || last[2] != z || last[3] != k) {
            last[0] = x;
            last[1] = y;
            last[2] = z;
            last[3] = k;
            GL20.glUniform4f(l[0], x, y, z, k);
         }
      } catch (Throwable t) {
         Log.warn("relief: uniforms failed, relief off: " + t);
         sunPatched = false;
      }
   }

   // ------------------------------------------------------------------------------------------------ dev: same-run A/B

   private static volatile boolean gameDevOff, renderDevOff;
   private static long altT0;

   /** Game thread, once a frame before the composite (devReliefAlternate): which half this frame is in, handed to the render thread. */
   static void devFrame() {
      if (Config.DEV_RELIEF_ALTERNATE <= 0) {
         return;
      }
      long now = System.currentTimeMillis();
      if (altT0 == 0L) {
         altT0 = now;
         Log.info("relief: alternating every " + Config.DEV_RELIEF_ALTERNATE + " ms from epoch_ms " + now + " (on first)");
      }
      final boolean off = (now - altT0) / Config.DEV_RELIEF_ALTERNATE % 2L == 1L;
      gameDevOff = off;
      SpriteRenderer.instance.drawGeneric(new zombie.core.textures.TextureDraw.GenericDrawer() {
         @Override
         public void render() {
            renderDevOff = off;
         }
      });
   }

   /** Game thread: a GPU section's name split by the dev alternation (".rlon" / ".rloff"). */
   public static String section(String name) {
      return Config.DEV_RELIEF_ALTERNATE <= 0 ? name : name + (gameDevOff ? ".rloff" : ".rlon");
   }

   /** The composite's post-main: the direct-sun share relit by the relief. */
   static final String SUN_GLSL = String.join("\n",
      "uniform vec4 pzRlfSun;", // towards the key light (world: x east, y south, z up in squares; unit), w strength (0: none)
      UNIFORMS,
      "vec3 pzRlfPos(float a, float b, float c) {", // (x - y, x + y - 6z, x + y + 2z) -> world, z in squares
      "   float z = (c - b) * 0.125;",
      "   float s = c - 2.0 * z;",
      "   return vec3((s + a) * 0.5, (s - a) * 0.5, z * 2.4494897);",
      "}",
      "void main() {",
      "   pzReliefInner();",
      "   vec2 rsz = vec2(textureSize(DEPTH, 0));",
      "   vec2 rtt = texCoord.st * rsz;",
      "   vec2 rdx = dFdx(rtt), rdy = dFdy(rtt);", // before any branch
      "#if defined(PZR_VIEW) && defined(RELIEF_AUX)",
      // dev view 6: the texture's relief code as read (red / green the slope levels along u / v, blue the plane; magenta: no
      // code bound for this texture, dark grey: code 0)
      "   if (PZR_VIEW == 6 && PZC_OUT.a > 0.004) {",
      "      ivec2 vt = clamp(ivec2(floor(rtt)), ivec2(0), ivec2(rsz) - 1);",
      "      int cv6 = int(texelFetch(pzRlfAuxTex, vt, 0).r * 255.0 + 0.5);",
      "      if (pzRlfK.w < 0.5) PZC_OUT.rgb = vec3(1.0, 0.0, 1.0) * PZC_OUT.a;",
      "      else if (cv6 == 0) PZC_OUT.rgb = vec3(0.15) * PZC_OUT.a;",
      "      else { cv6 -= 1; int c6 = cv6 / 81, r6 = cv6 - c6 * 81; PZC_OUT.rgb = vec3(float(r6 / 9) / 8.0, float(r6 - (r6 / 9) * 9) / 8.0, float(c6) / 2.0) * PZC_OUT.a; }",
      "      return;",
      "   }",
      "#endif",
      "   if (pzRlfSun.w <= 0.0 || PZC_OUT.a < 0.004) return;",
      "   float tpp = max(abs(rdx.x), abs(rdy.y));", // texels a window pixel
      "   float fade = 1.0 - smoothstep(1.1, 3.0, tpp);", // minified: the art's detail is below a pixel
      "   if (fade <= 0.0) return;",
      "   ivec2 mx = ivec2(rsz) - 1;",
      "   ivec2 ti = clamp(ivec2(floor(rtt)), ivec2(0), mx);",
      "   vec3 L = pzRlfSun.xyz;",
      "#ifdef RELIEF_AUX",
      // the code first (one byte): nothing to relight on an object or an edge, nor on a plane facing away from the key light
      // (its own shade), and then no share fetch either
      "   if (pzRlfK.w < 0.5) return;", // no code for this texture yet
      "   float cv = texelFetch(pzRlfAuxTex, ti, 0).r * 255.0;",
      "#if (PZR_SKIP & 2) != 0",
      "   if (cv > -1.0) { PZC_OUT.rgb *= 1.0 + 0.0001 * cv; return; }", // dev: the code fetch alone
      "#endif",
      "   if (cv < 0.5) return;",
      "   float cls = rlfClass(cv);",
      "   float f0 = dot(rlfPlane(cls), L);",
      "#ifndef PZR_VIEW",
      "   if (f0 <= 0.0) return;",
      "#endif",
      "#endif",
      "#if (PZR_SKIP & 4) != 0",
      "   float q = 0.4;", // dev: no share fetch
      "#else",
      "   float q = pzCloudE.x >= 0.0 ? pzCloudE.x : PZC_TERM(texCoord.st).g;", // the texel's direct-sun share (0: shade, indoors)
      "#endif",
      "#if (PZR_SKIP & 1) != 0",
      "   if (q > -1.0) { PZC_OUT.rgb *= 1.0 + 0.0001 * q; return; }", // dev: the fetches alone
      "#endif",
      "#ifdef PZR_VIEW",
      "   if (PZR_VIEW == 7) { PZC_OUT.rgb = vec3(q) * PZC_OUT.a; return; }", // dev view 7: the direct-sun share, grey
      "   if (PZR_VIEW == 3) q = 1.0;",
      "#endif",
      "   if (q < 0.02) return;",
      "#ifdef RELIEF_AUX",
      "   vec3 nr = rlfDecode(cv, cls, fade);",
      "#else",
      "   const int K = 2;",
      "   float d0 = texelFetch(DEPTH, ti, 0).r;",
      "   float xp = texelFetch(DEPTH, min(ti + ivec2(K, 0), mx), 0).r, xm = texelFetch(DEPTH, max(ti - ivec2(K, 0), ivec2(0)), 0).r;",
      "   float yp = texelFetch(DEPTH, min(ti + ivec2(0, K), mx), 0).r, ym = texelFetch(DEPTH, max(ti - ivec2(0, K), ivec2(0)), 0).r;",
      "   bool planar = d0 < 1.0 && xp < 1.0 && xm < 1.0 && abs(xp + xm - 2.0 * d0) < 4.0 / 65535.0 + 0.25 * abs(xp - xm)",
      "      && yp < 1.0 && ym < 1.0 && abs(yp + ym - 2.0 * d0) < 4.0 / 65535.0 + 0.25 * abs(yp - ym);",
      "   if (!planar) return;",
      "   float dA = pzRlfK.x, dB = pzRlfK.y;",
      "   vec3 pu = pzRlfPos(float(K) * dA, 0.0, pzRlfK.z * (xp - d0));",
      "   vec3 pv = pzRlfPos(0.0, float(K) * dB, pzRlfK.z * (yp - d0));",
      "   vec3 n = cross(pu, pv);",
      "   n *= (dot(n, vec3(3.0, 3.0, 2.4494897)) < 0.0 ? -1.0 : 1.0) / max(length(n), 1e-12);",
      "   vec3 nr = rlfNormal(n, ti, rsz, dA, dB, true, fade);",
      "#endif",
      "   if (rlfS <= 0.0) return;",
      "   float f0n = dot(rlfN0, L);",
      "   if (f0n <= 0.0) return;", // the plane faces away: its own shade, no direct light to relight
      "   float r = max(dot(nr, L), 0.0) / max(f0n, 0.15);",
      "#ifdef RELIEF_SHADOW",
      "   r *= rlfShadow(L);",
      "#endif",
      "   float k = q * pzRlfSun.w;",
      "#ifdef PZR_VIEW",
      "   if (PZR_VIEW == 3) { PZC_OUT.rgb = vec3(clamp(0.5 * r, 0.0, 1.0)) * PZC_OUT.a; return; }",
      "#endif",
      "   PZC_OUT.rgb *= clamp(1.0 + k * (r - 1.0), 0.0, 2.0);",
      "}");
}
