package pzopt;

import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.model.VehicleModelInstance;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.sprite.SkyBox;
import zombie.iso.weather.ClimateManager;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleModelCamera;

/**
 * Car windows that reflect and let light through (Config {@code carGlass}; write-up docs/findings-car-glass-2026-10-01.md).
 *
 * <p>Stock draws car glass opaque: the window texture's dark blue mixed 30 % with a sky texture looked up by a view-space
 * sphere map (the same patch of sky whatever the car's heading). Here the vehicle shaders (ShaderUnit hook) shade every
 * window fragment as thin glass seen by the game's orthographic iso camera: Schlick Fresnel at the real view angle, what
 * the glass reflects (the game's own sky texture in the world frame, turned so its sun stands where the real one does;
 * below the horizon the ground around the car, looked up where the reflected ray meets it in a snapshot of the frame taken
 * just before the vehicles draw), GGX glints of the sun / moon and of the lamps the game already lights the car with, and
 * what it lets through (an analytic cabin ray-cast in the car's frame: seats, headrests, dashboard, the occupants as torso
 * and head; where the ray leaves the cabin through the glass on the far side, the scene behind the car from the
 * snapshot). Cracks, blood and a removed window (open: no glass) keep the stock overlays.
 *
 * <p>Frame "G" below is the car's chassis frame as the renderer places the mesh: the mesh goes in through the script's
 * model transform (BaseVehicle.updateTransform's translate / rotate / scale), G goes to eye space through the vehicle's
 * chassis rotation and the iso camera. Uniform scale throughout, so every direction is computed in G.
 */
public final class CarGlass {
   private CarGlass() {
   }

   static final int SNAP_UNIT = 10;
   static final int PROBE_TILE = 32, PROBE_COLS = 8, PROBE_MAX = PROBE_COLS * PROBE_COLS, PROBE_UNIT = 14;
   private static volatile boolean failed;
   private static long patchedFrag, patchedVert, draws, frames, lodSkipped;

   public static boolean active() {
      return Config.CAR_GLASS && Overrides.enabled() && !CoreGl.legacyMac() && !failed; // macOS: the 4.1 core context (macGlCore), not Apple's 2.1 one
   }

   // ------------------------------------------------------------------------------------------------ shaders

   /** ShaderUnit hook (innermost: the HDR glint patch reads the same final line afterwards). */
   public static String patchShader(String fileName, String code) {
      if (fileName == null || code == null || CoreGl.legacyMac() || !noCompileCheck && (!Overrides.enabled() || !Config.CAR_GLASS)) {
         return code;
      }
      String f = fileName.replace('\\', '/');
      int slash = f.lastIndexOf('/');
      String base = slash < 0 ? f : f.substring(slash + 1);
      if (!base.startsWith("pzopt_glass_")) {
         return code; // the game's own vehicle programs stay stock: the glass is a program of its own (build.sh's copies)
      }
      if (base.endsWith(".vert")) {
         return patchVert(fileName, code);
      }
      if (base.endsWith(".frag") && !base.contains("_common")) {
         return patchFrag(fileName, code);
      }
      return code;
   }

   private static String patchVert(String fileName, String code) {
      String anchor = "gl_Position = o;";
      int at = code.lastIndexOf(anchor);
      int main = code.indexOf("void main()");
      if (at < 0 || main < 0) {
         Log.warn("car glass: " + fileName + " has changed, no glass");
         return code;
      }
      boolean skinned = code.contains("boneEffect");
      boolean varyingStyle = code.contains("varying vec3 vertNormal");
      String q = varyingStyle ? "varying" : "out";
      // carGlassVertexEnv (off: measured a loss, 2026-10-02): the panel's environment per vertex needs the shared glass code here too
      String decl = q + " vec3 pzGP;\n" + q + " vec3 pzGN;\n" + q + " vec4 pzEnv;\nuniform mat4 pzGlassM;\n" + (Config.CAR_GLASS_VERTEX_ENV
            ? "#define PZ_GLASS_VERT\n#define PZ_GLASS_SKYTEX\n#define texture2D texture\nuniform sampler2D TextureReflectionA;\nuniform sampler2D TextureReflectionB;\n" + GLSL_COMMON + "\n" : "");
      String body = skinned
            ? "\n\tpzGP = (pzGlassM * (boneEffect * position)).xyz;\n\tpzGN = mat3(pzGlassM) * normal.xyz;"
            : "\n\tpzGP = (pzGlassM * (transform * position)).xyz;\n\tpzGN = mat3(pzGlassM) * (transform * normal).xyz;";
      if (Config.CAR_GLASS_VERTEX_ENV) body += String.join("\n",
            "",
            "\t{ // car glass: the panel's environment (reflection x Fresnel, Fresnel), constant across a flat pane",
            "\tvec3 pzV = normalize(pzGlassV.xyz), pzN = normalize(pzGN);",
            "\tif (dot(pzN, pzV) > 0.0) pzN = -pzN;",
            "\tvec3 pzR = reflect(pzV, pzN), pzW = pzGlassWorld(pzR);",
            "\tvec3 pzRefl = pzW.z < 0.0 ? pzGlassSkyH.rgb * pzGlassSkyR.w : pzGlassSky(pzW);",
            "\tif (pzGlassK.w >= 0.0 && mod(floor(pzGlassFr[8].x / 32.0), 2.0) < 0.5) {",
            "\t  vec4 hit = pzGlassProbe(pzW);",
            "\t  if (hit.a > 0.0 && pzGlassPC.w > 0.0) {",
            "\t    float t = (1.0 - hit.a) * 2.0 * pzGlassO.w;",
            "\t    vec4 h2 = pzGlassProbe(pzGlassWorld(normalize(pzGP - pzGlassPC.xyz + pzR * t)));",
            "\t    if (h2.a > 0.0) hit = h2;",
            "\t  }",
            "\t  pzRefl = mix(pzRefl, pzGlassLin(hit.rgb), hit.a);",
            "\t}",
            "\tfloat pzF = clamp(pzGlassFres(clamp(dot(pzN, -pzV), 0.0, 1.0), pzGlassA.y) * pzGlassT.w, 0.0, 1.0);",
            "\tpzEnv = vec4(pzRefl * pzF, pzF);",
            "\t}");
      else body += "\n\tpzEnv = vec4(0.0); // unread then, but written: Apple's linker refuses a fragment input the vertex stage never writes";
      String c = code.substring(0, main) + decl + code.substring(main, at + anchor.length()) + body + code.substring(at + anchor.length());
      if (!compiles(GL20.GL_VERTEX_SHADER, c, fileName)) {
         return code;
      }
      patchedVert++;
      Log.info("car glass: " + fileName + " passes the chassis-frame position and normal");
      return c;
   }

   /**
    * The glass program's fragment unit: not the stock shader with additions (its 27-zone masks, HSV paint and eleven
    * fetches for every window texel) but a compact one with the stock uniform names (the game's setters fill them): the
    * mask (a texel that is not a window is discarded), the window's own damage / blood / removed flags from the enables
    * matrices, the stock lighting, the glass, then the stock overlays on top in the stock order (blood, the two damage
    * shells), lit as stock lights them.
    */
   private static String patchFrag(String fileName, String code) {
      if (!code.contains("TextureMask") || !code.contains("TextureDamage1Shell") || !code.contains("TextureUninstall1") || !code.contains("Light4Colour")) {
         Log.warn("car glass: " + fileName + " has changed, no glass");
         return code;
      }
      boolean multiuv = code.contains("texCoords1");
      boolean skytex = code.contains("uniform sampler2D TextureReflectionA");
      if (!Config.CAR_GLASS_COMPACT) {
         return patchFragStock(fileName, code);
      }
      String c = "#version 120\n#define PZ_GLASS_COMPACT\n" + (skytex ? "#define PZ_GLASS_SKYTEX\n" : "") + (multiuv ? "#define PZ_GLASS_MULTIUV\n" : "") + MAIN_HEAD + glsl() + "\n" + MAIN;
      if (!compiles(GL20.GL_FRAGMENT_SHADER, c, fileName)) {
         return code;
      }
      patchedFrag++;
      Log.info("car glass: glass fragment program for " + fileName + (multiuv ? " (two UV sets)" : "") + (skytex ? ", sky texture" : ""));
      return c;
   }

   /** carGlassCompact=false (A/B): the stock fragment shader with the glass appended (window texels only, the rest discarded first). */
   private static String patchFragStock(String fileName, String code) {
      java.util.regex.Matcher am = java.util.regex.Pattern.compile("gl_FragColor\\s*=\\s*vec4\\(\\s*col\\s*,\\s*TexturePainColor\\.a\\s*\\);").matcher(code);
      int main = code.indexOf("void main()");
      if (!am.find() || main < 0 || !code.contains("ref_en") || !code.contains("t4en")) {
         Log.warn("car glass: " + fileName + " has changed, no glass");
         return code;
      }
      boolean multiuv = code.contains("addBlood(");
      String cover = multiuv
            ? "1.0 - (1.0 - texColorDamage1Shell.a * t2en * noTintAlpha) * (1.0 - texColorDamage2Shell.a * t3en * noTintAlpha) * (1.0 - pzGlassBlood(texColorBlood2, colmask, intensity) * maskAlpha * windowAlpha)"
            : "1.0 - (1.0 - texColorDamage1Shell.a * t2en) * (1.0 - texColorDamage1Overlay.a * t2en) * (1.0 - texColorDamage2Shell.a * t3en) * (1.0 - texColorDamage2Overlay.a * t3en)";
      String hook = "if (ref_en > 0.5) col = pzGlass(col, clamp(" + cover + ", 0.0, 1.0), t4en, lighting * TintColourNew);\n\t";
      String defs = (code.contains("uniform sampler2D TextureReflectionA") ? "#define PZ_GLASS_SKYTEX\n" : "") + glsl() + "\n";
      int brace = code.indexOf('{', main);
      String c = code.substring(0, main) + defs + code.substring(main, brace + 1) + "\n\tpzGlassWindowOnly(texCoords);" + code.substring(brace + 1, am.start()) + hook
            + code.substring(am.start());
      if (!compiles(GL20.GL_FRAGMENT_SHADER, c, fileName)) {
         return code;
      }
      patchedFrag++;
      Log.info("car glass: glass appended to the stock fragment shader " + fileName);
      return c;
   }

   static final String MAIN_HEAD = String.join("\n",
         "varying vec3 vertColour;",
         "varying vec3 vertNormal;",
         "varying vec2 texCoords;",
         "#ifdef PZ_GLASS_MULTIUV",
         "varying vec2 texCoords1;",
         "#endif",
         "varying vec4 positionEye;",
         "varying vec4 pzEnv; // the vertex unit's environment: reflection x Fresnel, Fresnel",
         "uniform sampler2D TextureMask;",
         "uniform sampler2D Texture0; // dev view 11 only (unit 0, bound by the stock draw)",
         "uniform sampler2D pzGlassClass; // the skin's glass map (CarGlass.GlassMap)",
         "uniform sampler2D TextureDamage1Overlay;",
         "uniform sampler2D TextureDamage1Shell;",
         "uniform sampler2D TextureDamage2Overlay;",
         "uniform sampler2D TextureDamage2Shell;",
         "#ifdef PZ_GLASS_SKYTEX",
         "uniform sampler2D TextureReflectionA;",
         "uniform sampler2D TextureReflectionB;",
         "#endif",
         "uniform vec4 TexturePainColor;",
         "");

   static final String MAIN = String.join("\n",
         "void main() {",
         "  // the skin's glass map: 1-6 the stock window zones 7-12, 7 glass painted outside them, 8 a mirror; 0 not glass",
         "  float cls = floor(texture2D(pzGlassClass, texCoords).r * 255.0 + 0.5);",
         "  float z = cls - 1.0;",
         "  if (pzGlassA.z > 9.5 && pzGlassA.z < 11.5) { // dev views 10 (every texel by its class: window red, extra glass green, mirror cyan, unmarked blue, other zones grey + zone colour), 11 (the diffuse, glass tinted red)",
         "    vec4 mk = texture2D(TextureMask, texCoords);",
         "    if (pzGlassA.z > 10.5) { vec3 d = texture2D(Texture0, texCoords).rgb; gl_FragColor = vec4(cls > 0.5 ? mix(d, cls > 7.5 ? vec3(0.0, 1.0, 1.0) : (cls > 6.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)), 0.6) : d, 1.0); return; }",
         "    gl_FragColor = vec4(cls > 7.5 ? vec3(0.0, 1.0, 1.0) : (cls > 6.5 ? vec3(0.1, 1.0, 0.1) : (cls > 0.5 ? vec3(1.0, 0.1, 0.1) : (mk.a < 0.5 ? vec3(0.1, 0.3, 1.0) : mix(vec3(0.5), mk.rgb, 0.5)))), 1.0);",
         "    return;",
         "  }",
         "  if (cls < 0.5) discard;",
         "  int zi = int(z);",
         "  // damage 1, damage 2, removed, blood intensity (the stock enables matrices' element of this window); extra glass and mirrors none",
         "  vec4 wf = zi < 6 ? pzGlassCar[33 + zi] : vec4(0.0);",
         "  pzGlassMirror = z > 6.5 ? 1.0 : 0.0;",
         "  vec4 litA = pzGlassCar[41];   // the car's stock lighting x tint, the paint alpha",
         "  vec3 lit = litA.rgb;",
         "#ifdef PZ_GLASS_MULTIUV",
         "  vec2 uv1 = texCoords1;",
         "#else",
         "  vec2 uv1 = texCoords;",
         "#endif",
         "  vec3 o = pzGlass(vec3(0.0), 0.0, wf.z, lit);",
         "  if (pzGlassA.z > 0.5) { gl_FragColor = vec4(o, litA.a); return; }",
         "  if (wf.x + wf.y + wf.w > 0.0) { // the overlays only on a window with damage or blood (most have none: no fetches)",
         "    vec4 s1 = texture2D(TextureDamage1Shell, uv1), s2 = texture2D(TextureDamage2Shell, uv1);",
         "#ifdef PZ_GLASS_MULTIUV",
         "    // blood under the damage on windows (stock addBlood)",
         "    vec4 b = texture2D(TextureDamage2Overlay, uv1), bm = texture2D(TextureDamage1Overlay, uv1);",
         "    float intens = clamp(wf.w, 0.0, 1.0);",
         "    if (intens > 0.0001) {",
         "      float mask2 = zi < 4 ? pzGlassCar[39][zi] : pzGlassCar[40][zi - 4];",
         "      float fa = clamp(((1.0 - pow(1.0 - b.a, 3.0)) * (1.0 - pow(1.0 - bm.a, 3.0)) - (1.0 - intens)) / intens, 0.0, 1.0);",
         "      o = mix(o, b.rgb * fa * lit, fa * b.a * mask2);",
         "    }",
         "    o = mix(o, s1.rgb * lit, s1.a * wf.x);",
         "    o = mix(o, s2.rgb * lit, s2.a * wf.y);",
         "#else",
         "    vec4 v1 = texture2D(TextureDamage1Overlay, uv1), v2 = texture2D(TextureDamage2Overlay, uv1);",
         "    o = mix(o, s1.rgb * lit, s1.a * wf.x);",
         "    o = mix(o, v1.rgb * lit, v1.a * wf.x);",
         "    o = mix(o, s2.rgb * lit, s2.a * wf.y);",
         "    o = mix(o, v2.rgb * lit, v2.a * wf.y);",
         "#endif",
         "  }",
         "  gl_FragColor = vec4(o, litA.a);",
         "}");

   /** Tests: generate the units without the GL test compile (no context there; CarGlassShaderTest links them headless). */
   static boolean noCompileCheck;

   private static boolean compiles(int type, String c, String fileName) {
      if (noCompileCheck) {
         return true;
      }
      int test = GL20.glCreateShader(type);
      GL20.glShaderSource(test, c);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("car glass: " + fileName + " patch does not compile, stays stock: " + log);
      }
      return ok;
   }

   /**
    * The window shading (GLSL 1.20: the vehicle shaders include util/math, whose float max / min / clamp hide the vector
    * overloads, so only max(float, float), min(float, float), clamp(float, ...) and clamp(vec3, float, float) are used).
    */
   /** The glass shading shared by the vertex unit (the per-panel environment) and the fragment unit: uniforms and helpers. */
   static final String GLSL_COMMON = String.join("\n",
         "// ---- pzopt car glass (pzopt.CarGlass) ----",
         "#ifdef PZ_GLASS_FRAG",
         "varying vec3 pzGP; // chassis frame G: position",
         "varying vec3 pzGN; // normal",
         "#endif",
         "// per frame (CarGlass.FR_*): x strength, y F0, z dev view, w seconds | key light colour, roughness | zenith, sky texture weight |",
         "// horizon, fog | sky texture turn cos, sin, frame mix, ground brightness | viewport | transmission, tint, lamp roughness, reflect |",
         "// depth -> w | spare",
         "uniform vec4 pzGlassFr[9];",
         "// per car (CarGlass.C_*), one upload a draw",
         "uniform vec4 pzGlassCar[" + CarGlass.CAR + "];",
         "#define pzGlassA pzGlassFr[0]",
         "#define pzGlassSunC pzGlassFr[1]",
         "#define pzGlassSkyZ pzGlassFr[2]",
         "#define pzGlassSkyH pzGlassFr[3]",
         "#define pzGlassSkyR pzGlassFr[4]",
         "#define pzGlassVp pzGlassFr[5]",
         "#define pzGlassT pzGlassFr[6]",
         "#define pzGlassDep pzGlassFr[7]",
         "#define pzGlassV pzGlassCar[0]",
         "#define pzGlassE (pzGlassCar[1].xyz)",
         "#define pzGlassS (pzGlassCar[2].xyz)",
         "#define pzGlassUp pzGlassCar[3]",
         "#define pzGlassSun pzGlassCar[4]",
         "#define pzGlassCab0 pzGlassCar[5]",
         "#define pzGlassCab1 pzGlassCar[6]",
         "#define pzGlassInt pzGlassCar[7]",
         "#define pzGlassRain pzGlassCar[8]",
         "#define pzGlassK pzGlassCar[9]",
         "#define pzGlassPC pzGlassCar[10]",
         "#define pzGlassO pzGlassCar[11]",
         "#define pzGlassTex pzGlassCar[12]",
         "#define pzGlassClip mat4(pzGlassCar[13], pzGlassCar[14], pzGlassCar[15], pzGlassCar[16])",
         "// 17-22 seats, 23-27 lamps, 28-32 lamp colours, 33-38 the windows' flags (damage 1, damage 2, removed, blood), 39-40 blood masks, 41 lit + alpha,",
         "// 42-43 the occupants' impostor tile (CarOccupant: the cabin's NDC rect min xy, 1 / size; its atlas uv rect)",
         "#define pzGlassOcc0 pzGlassCar[42]",
         "#define pzGlassOcc1 pzGlassCar[43]",
         "#define pzGlassOcc2 pzGlassCar[44] // x: the occupant's seat origin's depth in the tile",
         "#define pzGlassOcc3 pzGlassCar[45] // xyz: that seat origin in G",
         "#define pzGlassOcc4 pzGlassCar[46] // G -> the tile's world NDC x (a row)",
         "#define pzGlassOcc5 pzGlassCar[47] // y",
         "// 48-53 each seat's occupant colours for the capsule proxy (packed 8-bit RGB): top, legs, skin, hair",
         "uniform sampler2D pzOccC; // the occupants drawn by the game (CarOccupant), premultiplied",
         "uniform sampler2D pzOccD; // their depth (the world view's plain projected depth)",
         "uniform sampler2D pzGlassWorldT; // the world colour as drawn so far (the pixel march / carGlassLiveReads)",
         "uniform sampler2D pzGlassWorldD; // the world depth",
         "uniform sampler2D pzGlassProbeT; // the cars' reflection probes (octahedral tiles, alpha = a surface was hit)",
         "vec4 pzGlassProbe(vec3 d) {",
         "  d /= abs(d.x) + abs(d.y) + abs(d.z);",
         "  vec2 e = d.xy;",
         "  if (d.z < 0.0) e = (1.0 - abs(e.yx)) * vec2(e.x >= 0.0 ? 1.0 : -1.0, e.y >= 0.0 ? 1.0 : -1.0);",
         "  e = e * 0.5 + 0.5;",
         "  e = vec2(clamp(e.x, 0.5 / " + PROBE_TILE + ".0, 1.0 - 0.5 / " + PROBE_TILE + ".0), clamp(e.y, 0.5 / " + PROBE_TILE + ".0, 1.0 - 0.5 / " + PROBE_TILE + ".0));",
         "  float col = mod(pzGlassK.w, " + PROBE_COLS + ".0), row = floor(pzGlassK.w / " + PROBE_COLS + ".0);",
         "  return texture2D(pzGlassProbeT, (vec2(col, row) + e) / " + PROBE_COLS + ".0);",
         "}",
         "// the window zones of the mask (stock: zones 7-12, the same 0.01 test); the stock-based glass program discards the rest",
         "#if defined(PZ_GLASS_FRAG) && !defined(PZ_GLASS_COMPACT)",
         "void pzGlassWindowOnly(vec2 uv) {",
         "  vec3 m = texture2D(TextureMask, uv).rgb;",
         "  bool win = length(m - vec3(0.0, 0.5, 0.5)) < 0.01 || length(m - vec3(0.5, 0.5, 0.0)) < 0.01 || length(m - vec3(0.5, 0.0, 0.5)) < 0.01",
         "    || length(m - vec3(0.0, 0.0, 0.5)) < 0.01 || length(m - vec3(0.5, 0.0, 0.0)) < 0.01 || length(m - vec3(0.0, 0.5, 0.0)) < 0.01;",
         "  if (!win) discard;",
         "}",
         "#endif",
         "",
         "vec3 pzG3max(vec3 a, vec3 b) { return vec3(a.x > b.x ? a.x : b.x, a.y > b.y ? a.y : b.y, a.z > b.z ? a.z : b.z); }",
         "vec3 pzG3min(vec3 a, vec3 b) { return vec3(a.x < b.x ? a.x : b.x, a.y < b.y ? a.y : b.y, a.z < b.z ? a.z : b.z); }",
         "vec3 pzGlassLin(vec3 c) { return pow(clamp(c, 0.0, 1.0), vec3(2.2)); }",
         "vec3 pzGlassEnc(vec3 c) { return pow(pzG3max(c, vec3(0.0)), vec3(1.0 / 2.2)); }",
         "float pzGlassBlood(vec4 b, vec4 m, float intensity) {",
         "  float intens = clamp(intensity, 0.0, 1.0);",
         "  if (intens < 0.0001) return 0.0;",
         "  float a = 1.0 - pow(1.0 - b.a, 3.0);",
         "  float ma = 1.0 - pow(1.0 - m.a, 3.0);",
         "  return clamp((a * ma - (1.0 - intens)) / intens, 0.0, 1.0) * b.a;",
         "}",
         "vec3 pzGlassWorld(vec3 g) { return vec3(dot(g, pzGlassE), dot(g, pzGlassS), dot(g, pzGlassUp.xyz)); }",
         "vec3 pzGlassSky(vec3 w) {",
         "  float up = w.z < 0.0 ? 0.0 : w.z;",
         "  vec3 c = mix(pzGlassSkyZ.rgb, pzGlassSkyH.rgb, 1.0 - sqrt(up));",
         "#ifdef PZ_GLASS_SKYTEX",
         "  if (pzGlassSkyZ.w > 0.0 && mod(floor(pzGlassFr[8].x / 64.0), 2.0) < 0.5) {",
         "    vec3 d = vec3(-w.x, up, w.y); // the skybox frame: x west, y up, z south",
         "    vec2 xz = vec2(pzGlassSkyR.x * d.x - pzGlassSkyR.y * d.z, pzGlassSkyR.y * d.x + pzGlassSkyR.x * d.z);",
         "    float lam = 0.2 / sqrt(d.y * d.y + 0.04 * dot(xz, xz) + 1e-6);",
         "    vec2 uv = xz * lam * 0.5 + 0.5;",
         "    vec3 t = mix(texture2D(TextureReflectionB, uv).rgb, texture2D(TextureReflectionA, uv).rgb, pzGlassSkyR.z);",
         "    c = mix(c, pzGlassLin(t), pzGlassSkyZ.w);",
         "  }",
         "#endif",
         "  return c;",
         "}",
         "float pzGlassFres(float c, float f0) { float m = 1.0 - c; float m2 = m * m; return f0 + (1.0 - f0) * m2 * m2 * m; }",
         "float pzGlassSpec(vec3 n, vec3 e, vec3 l, float a, float f0) {",
         "  float nl = dot(n, l);",
         "  if (nl <= 0.0) return 0.0;",
         "  vec3 h = normalize(l + e);",
         "  float nh = max(dot(n, h), 0.0);",
         "  float nv = max(dot(n, e), 0.001);",
         "  float a2 = a * a;",
         "  float d = nh * nh * (a2 - 1.0) + 1.0;",
         "  float D = a2 / (3.14159 * d * d);",
         "  float k = a * 0.5;",
         "  float vis = 0.25 / ((nl * (1.0 - k) + k) * (nv * (1.0 - k) + k));",
         "  return D * vis * pzGlassFres(max(dot(e, h), 0.0), f0) * nl;",
         "}",
         "");

   /** The fragment unit's glass code (shared part + fragment part; a method: the constants initialise in textual order). */
   static String glsl() {
      return "#define PZ_GLASS_FRAG\n" + GLSL_COMMON + "\n" + GLSL_FRAG;
   }

   /** The fragment-only part of the glass shading. */
   static final String GLSL_FRAG = String.join("\n",
         "bool pzGlassInView(vec2 px) { return px.x > pzGlassVp.x && px.y > pzGlassVp.y && px.x < pzGlassVp.x + pzGlassVp.z && px.y < pzGlassVp.y + pzGlassVp.w; }",
         "// the scene at a window px (linear): the snapshot near the car (taken before it draws), the live world further out",
         "vec3 pzGlassScene(vec2 px, vec3 fall) {",
         "  if (pzGlassTex.x != 0.0 && pzGlassInView(px)) return pzGlassLin(texture2D(pzGlassWorldT, px * pzGlassTex.xy).rgb);",
         "  return fall;",
         "}",
         "float pzGlassSceneW(vec2 px) {",
         "  float d = 1.0;",
         "  if (pzGlassTex.x != 0.0 && pzGlassInView(px)) d = texture2D(pzGlassWorldD, px * pzGlassTex.xy).r;",
         "  else return -1e9;",
         "  return d * pzGlassDep.x + pzGlassDep.y;",
         "}",
         "float pzGlassWorldW(vec3 g) {",
         "  vec3 wv = pzGlassO.xyz + vec3(dot(g, pzGlassE), dot(g, pzGlassS), dot(g, pzGlassUp.xyz) / 2.44949);",
         "  return wv.x + wv.y + 2.0 * wv.z;",
         "}",
         "vec2 pzGlassPx(vec3 g) {",
         "  vec4 c = pzGlassClip * vec4(g, 1.0);",
         "  return (c.xy / c.w * 0.5 + 0.5) * pzGlassVp.zw + pzGlassVp.xy;",
         "}",
         "// screen-space reflection: march the reflected ray through the scene depth (iso depth: x + y + 2z), the first",
         "// surface it passes behind within the thickness is what the glass mirrors",
         "vec4 pzGlassMarch(vec3 p, vec3 r) {",
         "  float t = 0.12;",
         "  float k = pow(max(pzGlassO.w, 0.5) / 0.12, 1.0 / max(pzGlassTex.z - 1.0, 1.0));",
         "  float tPrev = 0.0;",
         "  for (int i = 0; i < 24; i++) {",
         "    if (float(i) >= pzGlassTex.z) break;",
         "    vec3 g = p + r * t;",
         "    vec2 px = pzGlassPx(g);",
         "    float ws = pzGlassSceneW(px);",
         "    if (ws < -1e8) break;",
         "    float dw = ws - pzGlassWorldW(g);",
         "    if (dw > 0.0 && dw < pzGlassTex.w + t * 0.15) {",
         "      float lo = tPrev, hi = t;",
         "      for (int j = 0; j < 3; j++) {",
         "        float m = 0.5 * (lo + hi);",
         "        vec3 gm = p + r * m;",
         "        float d2 = pzGlassSceneW(pzGlassPx(gm)) - pzGlassWorldW(gm);",
         "        if (d2 > 0.0) hi = m; else lo = m;",
         "      }",
         "      vec2 hp = pzGlassPx(p + r * hi);",
         "      return vec4(pzGlassScene(hp, vec3(0.0)), 1.0 - clamp(hi / pzGlassO.w, 0.0, 1.0) * 0.5);",
         "    }",
         "    tPrev = t;",
         "    t *= k;",
         "  }",
         "  return vec4(0.0);",
         "}",
         "float pzHitT; float pzHitK; vec3 pzHitN;",
         "float pzGlassMirror = 0.0; // 1 = a side mirror (silvered: it reflects, nothing shows through)",
         "vec3 pzGlassHdrSpec = vec3(0.0); // the glints above white, for the HDR glint target (Hdr.patchVehicle)",
         "void pzGlassBox(vec3 o, vec3 d, vec3 inv, vec3 b0, vec3 b1, float k) {",
         "  vec3 t0 = (b0 - o) * inv, t1 = (b1 - o) * inv;",
         "  vec3 tn = pzG3min(t0, t1), tf = pzG3max(t0, t1);",
         "  float n = max(max(tn.x, tn.y), tn.z), f = min(min(tf.x, tf.y), tf.z);",
         "  if (f >= n && n > 0.0 && n < pzHitT) {",
         "    pzHitT = n; pzHitK = k;",
         "    pzHitN = n == tn.x ? vec3(-sign(d.x), 0.0, 0.0) : (n == tn.y ? vec3(0.0, -sign(d.y), 0.0) : vec3(0.0, 0.0, -sign(d.z)));",
         "  }",
         "}",
         "void pzGlassBall(vec3 o, vec3 d, vec3 c, float r, float k) {",
         "  vec3 oc = o - c;",
         "  float b = dot(oc, d);",
         "  float h = b * b - dot(oc, oc) + r * r;",
         "  if (h > 0.0) {",
         "    float t = -b - sqrt(h);",
         "    if (t > 0.0 && t < pzHitT) { pzHitT = t; pzHitK = k; pzHitN = normalize(oc + d * t); }",
         "  }",
         "}",
         "// a capsule (Quilez): the ray's entry distance, -1 for a miss",
         "float pzGlassCapT(vec3 ro, vec3 rd, vec3 pa, vec3 pb, float ra) {",
         "  vec3 ba = pb - pa, oa = ro - pa;",
         "  float baba = dot(ba, ba), bard = dot(ba, rd), baoa = dot(ba, oa), rdoa = dot(rd, oa), oaoa = dot(oa, oa);",
         "  float a = baba - bard * bard, b = baba * rdoa - baoa * bard, c = baba * oaoa - baoa * baoa - ra * ra * baba;",
         "  float h = b * b - a * c;",
         "  if (h < 0.0) return -1.0;",
         "  float t = (-b - sqrt(h)) / a;",
         "  float y = baoa + t * bard;",
         "  if (y > 0.0 && y < baba) return t;",
         "  vec3 oc = y <= 0.0 ? oa : ro - pb;",
         "  b = dot(rd, oc); c = dot(oc, oc) - ra * ra; h = b * b - c;",
         "  return h > 0.0 ? -b - sqrt(h) : -1.0;",
         "}",
         "void pzGlassCap(vec3 o, vec3 d, vec3 pa, vec3 pb, float r, float k) {",
         "  float t = pzGlassCapT(o, d, pa, pb, r);",
         "  if (t > 0.0 && t < pzHitT) {",
         "    vec3 h = o + d * t, ba = pb - pa;",
         "    pzHitT = t; pzHitK = k;",
         "    pzHitN = normalize(h - (pa + ba * clamp(dot(h - pa, ba) / dot(ba, ba), 0.0, 1.0)));",
         "  }",
         "}",
         "vec3 pzGlassUnpack(float c) { return vec3(floor(c / 65536.0), mod(floor(c / 256.0), 256.0), mod(c, 256.0)) / 255.0; }",
         "float pzHitSeat, pzPerT, pzPerK, pzPerSeat; vec3 pzPerN;",
         "// the capsule proxy of a seated occupant (seat point q = the hip, fw = the chassis axis the seats face): torso, head, arms",
         "// (hands on the wheel in the front seats, in the lap behind), thighs; materials 6 top, 7 legs, 8 skin, 9 hair",
         "void pzGlassPerson(vec3 p, vec3 d, vec3 q, float fw, bool front, float seat) {",
         "  // (its own hit: the cabin's seats only hide it with carOccupantOcclusion)",
         "  float t0 = pzHitT, k0 = pzHitK; vec3 n0 = pzHitN;",
         "  pzHitT = pzPerT;",
         "  // (proportions measured on the game's own seated model through its impostor tile: feet 0.44 below the hip point, the",
         "  // crown 0.56 above, the feet 0.57 forward)",
         "  pzGlassCap(p, d, q + vec3(0.0, 0.02, -0.08 * fw), q + vec3(0.0, 0.30, -0.06 * fw), 0.14, 6.0);",
         "  pzGlassCap(p, d, q + vec3(0.0, 0.44, -0.03 * fw), q + vec3(0.0, 0.47, -0.04 * fw), 0.095, 8.0);",
         "  vec3 hand = front ? vec3(0.15, 0.24, 0.38 * fw) : vec3(0.10, 0.02, 0.26 * fw);",
         "  for (int s = 0; s < 2; s++) {",
         "    float sx = s == 0 ? 1.0 : -1.0;",
         "    vec3 sh = q + vec3(0.17 * sx, 0.28, -0.06 * fw), el = q + vec3(0.19 * sx, 0.09, 0.13 * fw);",
         "    pzGlassCap(p, d, sh, el, 0.05, 6.0);",
         "    pzGlassCap(p, d, el, q + vec3(hand.x * sx, hand.y, hand.z), 0.04, 8.0);",
         "    vec3 kn = q + vec3(0.10 * sx, 0.02, 0.40 * fw);",
         "    pzGlassCap(p, d, q + vec3(0.09 * sx, -0.04, -0.02 * fw), kn, 0.075, 7.0);",
         "    pzGlassCap(p, d, kn, q + vec3(0.10 * sx, -0.40, 0.54 * fw), 0.055, 7.0);",
         "  }",
         "  if (pzHitT < pzPerT) { pzPerT = pzHitT; pzPerK = pzHitK; pzPerN = pzHitN; pzPerSeat = seat; }",
         "  pzHitT = t0; pzHitK = k0; pzHitN = n0;",
         "}",
         "// what the glass lets through: the cabin (linear radiance), see = 1 when the ray leaves through the far glass",
         "vec3 pzGlassCabin(vec3 p, vec3 d, vec3 lit, out float see) {",
         "  vec3 inv = vec3(1.0) / (d + vec3(1e-6));",
         "  pzHitT = 1e6; pzHitK = 0.0; pzHitN = vec3(0.0, 1.0, 0.0); pzHitSeat = -1.0; pzPerT = 1e6; pzPerSeat = -1.0;",
         "  float fw = pzGlassV.w;",
         "  for (int i = 0; i < 6; i++) {",
         "    if (float(i) >= pzGlassK.y) break;",
         "    vec4 s = pzGlassCar[17 + i];",
         "    vec3 q = s.xyz;",
         "    if (s.w > 3.5) pzGlassPerson(p, d, q, fw, q.z * fw > pzGlassCab1.w * fw - 1.0, float(i)); // (before the seat's box test: its own hit)",
         "    // the seat's bounding box first (most rays miss most seats)",
         "    vec3 b0 = (q + vec3(-0.25, -0.17, -0.39) - p) * inv, b1 = (q + vec3(0.25, 0.68, 0.39) - p) * inv;",
         "    vec3 bn = pzG3min(b0, b1), bf = pzG3max(b0, b1);",
         "    float bnear = max(max(bn.x, bn.y), bn.z), bfar = min(min(bf.x, bf.y), bf.z);",
         "    if (bfar < bnear || bfar < 0.0 || bnear > pzHitT) continue;",
         "    pzGlassBox(p, d, inv, q + vec3(-0.24, -0.16, min(-0.26 * fw, 0.24 * fw)), q + vec3(0.24, 0.0, max(-0.26 * fw, 0.24 * fw)), 1.0);",
         "    pzGlassBox(p, d, inv, q + vec3(-0.24, 0.0, min(-0.38 * fw, -0.24 * fw)), q + vec3(0.24, 0.50, max(-0.38 * fw, -0.24 * fw)), 1.0);",
         "    pzGlassBox(p, d, inv, q + vec3(-0.13, 0.53, min(-0.38 * fw, -0.27 * fw)), q + vec3(0.13, 0.67, max(-0.38 * fw, -0.27 * fw)), 1.0);",
         "    if (s.w > 1.5 && s.w < 2.5) {",
         "      pzGlassBox(p, d, inv, q + vec3(-0.19, 0.0, min(-0.25 * fw, -0.04 * fw)), q + vec3(0.19, 0.45, max(-0.25 * fw, -0.04 * fw)), 2.0);",
         "      pzGlassBall(p, d, q + vec3(0.0, 0.60, -0.12 * fw), 0.10, 3.0);",
         "    }",
         "  }",
         "  float dz0 = min(pzGlassCab1.w, fw > 0.0 ? pzGlassCab1.z : pzGlassCab0.z), dz1 = max(pzGlassCab1.w, fw > 0.0 ? pzGlassCab1.z : pzGlassCab0.z);",
         "  pzGlassBox(p, d, inv, vec3(pzGlassCab0.x, pzGlassCab0.y, dz0), vec3(pzGlassCab1.x, pzGlassCab0.w + 0.03, dz1), 4.0);",
         "  vec3 t0 = (pzGlassCab0.xyz - p) * inv, t1 = (pzGlassCab1.xyz - p) * inv;",
         "  vec3 tf = pzG3max(t0, t1);",
         "  float tx = min(min(tf.x, tf.y), tf.z);",
         "  see = 0.0;",
         "  if (pzHitT >= tx) {",
         "    vec3 e = p + d * tx;",
         "    if ((tx == tf.x || tx == tf.z) && e.y > pzGlassCab0.w && pzPerT > 1e5) { see = 1.0; return vec3(0.0); }",
         "    pzHitT = tx; pzHitK = 5.0;",
         "    pzHitN = tx == tf.x ? vec3(-sign(d.x), 0.0, 0.0) : (tx == tf.y ? vec3(0.0, -sign(d.y), 0.0) : vec3(0.0, 0.0, -sign(d.z)));",
         "  }",
         "  if (pzPerT < 1e5 && (pzGlassFr[8].z < 0.5 || pzPerT < pzHitT)) { pzHitT = pzPerT; pzHitK = pzPerK; pzHitN = pzPerN; pzHitSeat = pzPerSeat; see = 0.0; }",
         "  vec3 h = p + d * pzHitT;",
         "  vec3 alb = pzGlassInt.rgb;",
         "  if (pzHitK > 1.5) alb = vec3(0.045, 0.045, 0.05);",
         "  if (pzHitK > 2.5) alb = vec3(0.30, 0.20, 0.15);",
         "  if (pzHitK > 3.5) alb = vec3(0.025);",
         "  if (pzHitK > 4.5) alb = pzGlassInt.rgb * 0.55;",
         "  if (pzHitK > 5.5) {",
         "    vec4 pal = vec4(0.0); float qy = 0.0;",
         "    for (int j = 0; j < 6; j++) if (float(j) == pzHitSeat) { pal = pzGlassCar[48 + j]; qy = pzGlassCar[17 + j].y; }",
         "    float m = pzHitK > 8.5 ? pal.w : pzHitK > 7.5 ? pal.z : pzHitK > 6.5 ? pal.y : pal.x;",
         "    alb = pzGlassLin(pzGlassUnpack(m)) * pzGlassFr[8].y;",
         "    if (pzHitK > 7.5 && pzHitK < 8.5 && (pzHitN.y > 0.45 || dot(pzHitN, vec3(0.0, 0.0, fw)) < -0.4) && h.y > qy + 0.44) alb = pzGlassLin(pzGlassUnpack(pal.w)) * pzGlassFr[8].y; // the head's crown and back: hair",
         "  }",
         "  float hgt = clamp((h.y - pzGlassCab0.y) / max(pzGlassCab1.y - pzGlassCab0.y, 0.1), 0.0, 1.0);",
         "  if (pzHitK > 5.5) return alb * lit * (0.45 + 0.55 * hgt) * (0.7 + 0.3 * clamp(dot(pzHitN, pzGlassUp.xyz), 0.0, 1.0)); // the proxy body: lit like the impostor's occupant",
         "  float top = 0.55 + 0.45 * dot(pzHitN, pzGlassUp.xyz);",
         "  return alb * lit * (0.25 + 0.75 * hgt) * top * pzGlassInt.w;",
         "}",
         "// raindrops on the glass: a cell grid on the pane (one drop a cell at most, sliding down, streaking back at speed);",
         "// returns the drop's slope in the pane (xy) and its coverage (z)",
         "float pzGlassHash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }",
         "vec3 pzGlassDropLayer(vec2 uv, float scale, float amount, float t, float seed) {",
         "  vec2 q = uv * scale;",
         "  q.y += t * (0.15 + 0.35 * seed);",
         "  vec2 id = floor(q);",
         "  vec2 f = fract(q) - 0.5;",
         "  float h = pzGlassHash(id + seed * 17.0);",
         "  if (h > amount) return vec3(0.0);",
         "  vec2 c = vec2(pzGlassHash(id + 3.1) - 0.5, pzGlassHash(id + 7.7) - 0.5) * 0.5;",
         "  float r = 0.18 + 0.2 * pzGlassHash(id + 1.3);",
         "  vec2 d = (f - c) / r;",
         "  d.y *= 0.8 + 0.4 * pzGlassRain.z;",
         "  float l2 = dot(d, d);",
         "  if (l2 >= 1.0) return vec3(0.0);",
         "  return vec3(d * (1.0 / sqrt(1.0 - l2 + 0.05)), 1.0);",
         "}",
         "vec3 pzGlassDrops(vec3 p, vec3 n) {",
         "  vec3 up = pzGlassUp.xyz;",
         "  vec3 t1 = cross(n, up);",
         "  if (dot(t1, t1) < 1e-4) t1 = vec3(1.0, 0.0, 0.0);",
         "  t1 = normalize(t1);",
         "  vec3 t2 = cross(n, t1);",
         "  vec2 uv = vec2(dot(p, t1), dot(p, t2));",
         "  float t = pzGlassA.w;",
         "  vec3 a = pzGlassDropLayer(uv, 9.0, pzGlassRain.x * 0.55, t, 0.0);",
         "  vec3 b = pzGlassDropLayer(uv + 0.37, 17.0, pzGlassRain.x * 0.7, t * 0.3, 1.0);",
         "  vec3 r = a.z > 0.0 ? a : b;",
         "  if (r.z <= 0.0) return n * 0.0;",
         "  return normalize(n + (t1 * r.x + t2 * r.y) * 0.6);",
         "}",
         "vec3 pzGlass(vec3 stock, float cover, float open, vec3 litDisplay) {",
         "  vec3 V = normalize(pzGlassV.xyz);",
         "  vec3 N = normalize(pzGN);",
         "  vec3 e = -V;",
         "  if (dot(N, e) < 0.0) N = -N;",
         "  float drop = 0.0;",
         "  if (pzGlassRain.x > 0.0 && open < 0.5) {",
         "    vec3 dn = pzGlassDrops(pzGP, N);",
         "    if (dot(dn, dn) > 0.5) { drop = 1.0; N = dn; if (dot(N, e) < 0.05) N = normalize(N + e * (0.05 - dot(N, e))); }",
         "  }",
         "  float nv = clamp(dot(N, e), 0.0, 1.0);",
         "  vec3 lit = pzGlassLin(litDisplay);",
         "  float view = pzGlassA.z;",
         "  vec2 px = gl_FragCoord.xy;",
         "  float see;",
         "  vec3 tint = mix(vec3(1.0), vec3(0.80, 0.87, 0.85), pzGlassT.y);",
         "  vec3 inside;",
         "  pzHitT = 1e6;",
         "  if (pzGlassMirror > 0.5) { see = 0.0; inside = vec3(0.0); }",
         "  else if (pzGlassK.z > 0.5 && mod(floor(pzGlassFr[8].x / 16.0), 2.0) < 0.5) inside = pzGlassCabin(pzGP, V, lit, see);",
         "  else { see = 0.0; inside = pzGlassInt.rgb * lit * 0.5 * pzGlassInt.w; }",
         "  vec3 horizon = pzGlassSkyH.rgb;",
         "  if (see > 0.5) {",
         "    vec3 back = horizon * pzGlassSkyR.w;",
         "    if (pzGlassTex.x == 0.0 && pzGlassK.w >= 0.0) { vec4 pb = pzGlassProbe(pzGlassWorld(V)); back = mix(back, pzGlassLin(pb.rgb), pb.a); }",
         "    inside = pzGlassScene(px, back) * tint * pzGlassT.x;",
         "  }",
         "  // the occupants (CarOccupant): the game's own model of each, drawn into a tile with the world view's projection; the",
         "  // texel's chassis position through the same projection is where the tile shows what lies behind it on the view ray,",
         "  // the depth difference over the projection's depth per unit along the ray is how far behind: in front of the cabin's",
         "  // hit (seats, headrests, dashboard), the occupant is what the glass lets through",
         "  vec3 occDbg = vec3(0.0);",
         "  vec3 occDbg2 = vec3(0.0, 1.0, 0.0);",
         "  if (pzGlassOcc0.z > 0.0 && pzGlassMirror < 0.5) {",
         "    vec4 cl = pzGlassClip * vec4(pzGP, 1.0);",
         "    vec2 ndc = vec2(dot(pzGlassOcc4, vec4(pzGP, 1.0)), dot(pzGlassOcc5, vec4(pzGP, 1.0))); // where the tile drew this chassis point",
         "    vec2 ouv = (ndc - pzGlassOcc0.xy) * pzGlassOcc0.zw;",
         "    occDbg = vec3(0.25, 0.0, 0.25);",
         "    occDbg2 = pzGlassA.z > 14.5 ? vec3(cl.xy / cl.w * 0.5 + 0.5, 0.0) : pzGlassA.z > 13.5 ? vec3(ndc * 0.5 + 0.5, 0.0) : vec3(clamp(ouv.x, 0.0, 1.0), clamp(ouv.y, 0.0, 1.0), 0.5);",
         "    if (ouv.x > 0.0 && ouv.y > 0.0 && ouv.x < 1.0 && ouv.y < 1.0) {",
         "      vec2 au = pzGlassOcc1.xy + ouv * pzGlassOcc1.zw;",
         "      vec4 oc = texture2D(pzOccC, au);",
         "      occDbg = vec3(0.0, 0.0, 0.3);",
         "      if (oc.a > 0.004) {",
         "        float od = texture2D(pzOccD, au).r;",
         "        float k = 0.5 * (pzGlassClip * vec4(V, 0.0)).z;",
         "        float ot = dot(pzGlassOcc3.xyz - pzGP, V) + (od - pzGlassOcc2.x) / k; // along the view: the glass to the seat's origin, then the occupant's surface from its origin (the tile's own depths)",
         "        occDbg = vec3(1.0, 0.0, 0.0);",
         "        if (pzGlassA.z > 16.5) occDbg2 = oc.rgb / oc.a; // 17: the tile's occupant wherever it covers the glass (no depth test)",
         "        if (pzGlassA.z > 15.5 && pzGlassA.z < 16.5) occDbg2 = vec3(clamp(ot * 0.5 + 0.5, 0.0, 1.0), clamp(pzHitT * 0.5, 0.0, 1.0), od < 0.99999 ? 1.0 : 0.0); // 16: the occupant's distance behind the glass (red, 0.5 = 0, 1 square a step of 0.5), the cabin hit's (green)",
         "        if (od < 0.99999 && (pzGlassOcc2.y < 0.5 || ot > -0.05 && ot < pzHitT)) { // (carOccupantOcclusion: the cabin's seats in front hide it)",
         "          vec3 hp = pzGP + V * ot;",
         "          float hg = clamp((hp.y - pzGlassCab0.y) / max(pzGlassCab1.y - pzGlassCab0.y, 0.1), 0.0, 1.0);",
         "          vec3 ol = pzGlassLin(oc.rgb / oc.a) * pzGlassFr[8].y * (0.45 + 0.55 * hg);",
         "          inside = mix(inside, ol, clamp(oc.a, 0.0, 1.0));",
         "          if (see > 0.5) see = 1.0 - clamp(oc.a, 0.0, 1.0);",
         "          occDbg = pzGlassEnc(ol);",
         "        }",
         "      }",
         "    }",
         "  }",
         "  vec3 R = reflect(V, N);",
         "  vec3 w = pzGlassWorld(R);",
         "  vec3 refl;",
         "  float F;",
         "  // the per-panel environment from the vertex unit (flat panes, ortho camera: exact), unless a drop bends the normal",
         "  // or the pixel march / a dev view needs the per-pixel path",
         "  bool perPixel = drop > 0.5 || pzGlassTex.z > 0.5 || view > 0.5 || pzGlassK.x < 0.5;",
         "  if (!perPixel) {",
         "    F = pzEnv.a;",
         "    refl = pzEnv.rgb / max(F, 1e-4);",
         "  } else {",
         "  if (w.z < 0.0) {",
         "    float t = (pzGlassUp.w - dot(pzGlassUp.xyz, pzGP)) / min(dot(pzGlassUp.xyz, R), -0.02);",
         "    vec3 fall = horizon * pzGlassSkyR.w;",
         "    refl = pzGlassTex.x != 0.0 ? pzGlassScene(pzGlassPx(pzGP + R * t), fall) : fall;",
         "    refl = mix(refl, horizon, clamp(t / 40.0, 0.0, 1.0));",
         "  } else {",
         "    refl = pzGlassSky(w);",
         "  }",
         "  if (pzGlassK.w >= 0.0 && mod(floor(pzGlassFr[8].x / 32.0), 2.0) < 0.5) {",
         "    vec4 hit = pzGlassProbe(w);",
         "    if (hit.a > 0.0 && pzGlassPC.w > 0.0) {",
         "      // parallax: the probe saw the hit from its centre; aim at where this texel's ray lands (alpha = 1 - 0.5 t / reach)",
         "      float t = (1.0 - hit.a) * 2.0 * pzGlassO.w;",
         "      vec4 h2 = pzGlassProbe(pzGlassWorld(normalize(pzGP - pzGlassPC.xyz + R * t)));",
         "      if (h2.a > 0.0) hit = h2;",
         "    }",
         "    refl = mix(refl, pzGlassLin(hit.rgb), hit.a);",
         "  } else if (pzGlassTex.z > 0.5) {",
         "    vec4 hit = pzGlassMarch(pzGP + N * 0.02, R);",
         "    refl = mix(refl, hit.rgb, hit.a);",
         "  }",
         "  F = clamp(pzGlassFres(nv, pzGlassA.y) * pzGlassT.w, 0.0, 1.0);",
         "  }",
         "  if (drop > 0.5) inside = mix(inside, pzGlassSky(pzGlassWorld(-reflect(-V, N))) * 0.35, 0.5);",
         "  if (pzGlassMirror > 0.5) F = 0.75; // silvered glass: ~75 % at every angle",
         "  vec3 c = open > 0.5 ? inside : inside * tint * pzGlassT.x * (1.0 - F) + refl * F;",
         "  vec3 spec = vec3(0.0);",
         "  if (open < 0.5) {",
         "    spec = pzGlassSpec(N, e, normalize(pzGlassSun.xyz), pzGlassSunC.w, pzGlassA.y) * pzGlassSunC.rgb * pzGlassSun.w;",
         "    for (int i = 0; i < 5; i++) {",
         "      if (mod(floor(pzGlassFr[8].x / 128.0), 2.0) > 0.5) break;",
         "      vec4 L = pzGlassCar[23 + i];",
         "      if (L.w <= 0.0) continue;",
         "      vec3 dl = L.xyz - pzGP;",
         "      float dist = length(dl);",
         "      float att = clamp(1.0 - dist / L.w, 0.0, 1.0);",
         "      spec += pzGlassSpec(N, e, dl / max(dist, 0.001), pzGlassT.z, pzGlassA.y) * pzGlassCar[28 + i].rgb * (att * att);",
         "    }",
         "    c += spec;",
         "    pzGlassHdrSpec = spec * pzGlassA.x * (1.0 - cover);",
         "  }",
         "  vec3 o = pzGlassEnc(c);",
         "  if (view > 0.5) {",
         "    if (view < 1.5) o = N * 0.5 + 0.5;",
         "    else if (view < 2.5) o = pzGlassEnc(refl);",
         "    else if (view < 3.5) o = pzGlassEnc(inside);",
         "    else if (view < 4.5) o = clamp(vec3((pzGP.y + 1.5) / 3.0, pzGP.x * 0.25 + 0.5, pzGP.z * 0.125 + 0.5), 0.0, 1.0);",
         "    else if (view < 5.5) { o = pzGlassEnc(inside); for (int i = 0; i < 6; i++) { vec4 s = pzGlassCar[17 + i]; if (s.w < 0.5) continue; vec3 q = s.xyz - pzGP; float b = dot(q, V); if (length(q - V * b) < 0.12) o = vec3(1.0, s.w > 1.5 ? 1.0 : 0.0, 0.0); } }",
         "    else if (view < 6.5) o = vec3(F * 4.0);",
         "    else if (view < 7.5) { vec4 pr = pzGlassK.w >= 0.0 ? pzGlassProbe(w) : vec4(1.0, 0.0, 1.0, 1.0); o = mix(vec3(1.0, 0.0, 1.0), pr.rgb, pr.a); }",
         "    else if (view < 8.5) o = pzGlassEnc(spec);",
         "    else if (view < 9.5) o = vec3(see, drop, pzGlassK.w >= 0.0 ? 1.0 : 0.0);",
         "    else if (view > 17.5) o = vec3(pzPerT < 1e5 ? 1.0 : 0.0, pzHitK > 5.5 ? 1.0 : 0.0, float(pzGlassK.y) / 6.0); // 18: the proxy body hit (red), the cabin shading it (green), seats (blue)",
         "    else if (view > 12.5) o = occDbg2; // 13: the texel's tile uv (red, green; yellow / black clamped outside), 14: its world NDC (green: no tile)",
         "    else o = occDbg; // 12: the occupant tile (dark blue in the tile, magenta outside it, red behind the cabin's hit, the occupant where it shows)",
         "    return o;",
         "  }",
         "  return mix(stock, o, pzGlassA.x * (1.0 - cover));",
         "}");

   // ------------------------------------------------------------------------------------------------ per frame (game thread)

   /** Per-frame state handed to the render thread (several in flight: the game thread runs ahead). */
   static final class Frame extends TextureDraw.GenericDrawer {
      boolean on;
      long serial; // beforeMoving's count when this frame was set up
      int skip; // dev skip bits of this frame (devCarGlassSkip / the cycle's entry)
      final float[] sun = new float[4]; // world dir to the key light (x east, y south, z up) + strength
      final float[] sunC = new float[4];
      final float[] skyZ = new float[4], skyH = new float[4];
      float skyCos = 1F, skySin = 0F, groundK = 0.25F, seconds, rain, view;
      float screenW, screenH, pxScale = 1F;
      int ox, oy;
      int player; // the view this frame state draws
      float d0;
      final Ssr.View iso = new Ssr.View();
      int np; // probes marched this frame
      float[] probe = new float[PROBE_MAX * 4];
      final IdentityHashMap<Object, Integer> probeIndex = new IdentityHashMap<>(); // every car on screen -> its probe tile

      @Override
      public void render() {
         renderFrame = this;
         // the world framebuffer and viewport are asked (glGet: a sync on threaded drivers) only on the frames that march the
         // probes, or every frame when the glass reads the live world
         if (this.on && !this.probeIndex.isEmpty() && (this.np > 0 || !PROBE || Config.CAR_GLASS_LIVE_READS)) {
            liveSetup();
            if (PROBE && worldInfoFbo > 0) {
               try {
                  probes(this, worldInfoFbo);
               } catch (Throwable t) {
                  failed = true;
                  Log.warn("car glass: probes failed, off: " + t);
               }
            }
         }
      }
   }

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int frameIndex;
   static volatile Frame renderFrame;
   private static long alternateT0, viewT0;

   /**
    * Game thread: a GPU section's name split by the dev alternation (devCarGlassAlternate: ".cgon" / ".cgoff"; with
    * devCarGlassCycle a list of variants, ".cg<variant>", "off" the glass off).
    */
   public static String section(String name) {
      if (Config.DEV_CAR_GLASS_ALTERNATE <= 0 || !active()) {
         return name;
      }
      if (CYCLE != null) {
         return name + ".cg" + CYCLE[phase()];
      }
      return alternateOn() ? name + ".cgon" : name + ".cgoff";
   }

   /** devCarGlassCycle=v1,v2,...: each period of devCarGlassAlternate ms one entry; "off" = glass off, a number = devCarGlassSkip bits. */
   static final String[] CYCLE = Config.DEV_CAR_GLASS_CYCLE.isEmpty() ? null : Config.DEV_CAR_GLASS_CYCLE.split(",");

   private static int phase() {
      long now = System.currentTimeMillis();
      if (alternateT0 == 0L) {
         alternateT0 = now;
         Log.info("car glass: alternating every " + Config.DEV_CAR_GLASS_ALTERNATE + " ms from epoch_ms " + now + (CYCLE != null ? " cycle " + Config.DEV_CAR_GLASS_CYCLE : " (on first)"));
      }
      long n = (now - alternateT0) / Math.max(1, Config.DEV_CAR_GLASS_ALTERNATE);
      return (int)(n % (CYCLE != null ? CYCLE.length : 2));
   }

   private static boolean alternateOn() {
      if (Config.DEV_CAR_GLASS_ALTERNATE <= 0) {
         return true;
      }
      int ph = phase();
      if (CYCLE != null) {
         String v = CYCLE[ph].trim();
         cycleSkip = v.equals("off") ? 0 : Integer.parseInt(v);
         return !v.equals("off");
      }
      return ph == 0;
   }

   static final String[] VIEW_LIST = Config.DEV_CAR_GLASS_VIEW_LIST.isEmpty() ? null : Config.DEV_CAR_GLASS_VIEW_LIST.split(",");
   static volatile int cycleSkip;
   private static final float A32 = 32F, A16 = 16F;

   /** The dev skip bits in force this frame (devCarGlassSkip, or the cycle's current entry). */
   static int skip() {
      Frame f = renderFrame;
      return f != null ? f.skip : Config.DEV_CAR_GLASS_SKIP;
   }

   /** Game thread, FBORenderCell right before the moving objects: this frame's sky / sun and the cars to snapshot. */
   public static void beforeMoving(int playerIndex) {
      if (!active()) {
         return;
      }
      Frame f = FRAMES[frameIndex = (frameIndex + 1) & 3];
      f.serial = frames + 1;
      f.on = alternateOn();
      f.skip = CYCLE != null ? cycleSkip : Config.DEV_CAR_GLASS_SKIP;
      f.view = Config.DEV_CAR_GLASS_VIEW;
      f.player = playerIndex;
      if (Config.DEV_CAR_GLASS_VIEW_CYCLE > 0) {
         long now = System.currentTimeMillis();
         if (viewT0 == 0L) {
            viewT0 = now;
            Log.info("car glass: dev view cycle every " + Config.DEV_CAR_GLASS_VIEW_CYCLE + " ms from epoch_ms " + now + " (view 0 first)");
         }
         long k = (now - viewT0) / Config.DEV_CAR_GLASS_VIEW_CYCLE;
         f.view = VIEW_LIST != null ? Float.parseFloat(VIEW_LIST[(int)(k % VIEW_LIST.length)].trim()) : (float)(k % 10);
      }
      if (++frames % 1200L == 120L) {
         Log.info(stats());
      }
      try {
         f.ox = (int)Math.floor(IsoCamera.frameState.camCharacterX);
         f.oy = (int)Math.floor(IsoCamera.frameState.camCharacterY);
         f.d0 = zombie.iso.IsoDepthHelper.getSquareDepthData(f.ox, f.oy, f.ox, f.oy, 0.0F).depthStart;
         f.iso.capture(playerIndex);
         f.np = 0;
         f.probeIndex.clear();
         sky(f);
         if (f.on) {
            collect(f, playerIndex);
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("car glass: frame setup failed, off: " + t);
         return;
      }
      boolean sec = f.np > 0;
      if (sec) {
         GpuSections.begin("carGlassProbe");
      }
      SpriteRenderer.instance.drawGeneric(f);
      if (sec) {
         GpuSections.end("carGlassProbe");
      }
      try {
         CarOccupant.queue(f, playerIndex); // the occupants into their impostor tiles, before every moving object
      } catch (Throwable t) {
         Log.warn("car occupant: queue failed: " + t);
      }
   }

   /** The world viewport's size (render thread; cached: asked once, again on probe frames). */
   static float viewportW() {
      return VP[2] > 0F ? VP[2] : Core.getInstance().getOffscreenWidth(0);
   }

   static float viewportH() {
      return VP[3] > 0F ? VP[3] : Core.getInstance().getOffscreenHeight(0);
   }

   private static void sky(Frame f) {
      Sky.update(-1F);
      ClimateManager cm = ClimateManager.getInstance();
      float day = cm != null ? Math.max(0F, Math.min(1F, cm.getDayLightStrength())) : 1F;
      float night = cm != null ? Math.max(0F, Math.min(1F, cm.getNightStrength())) : 0F;
      float cloud = cm != null ? Math.max(0F, Math.min(1F, Math.max(cm.getCloudIntensity(), cm.getPrecipitationIntensity()))) : 0F;
      float fog = cm != null ? Math.max(0F, Math.min(1F, cm.getFogIntensity())) : 0F;
      boolean sunUp = Sky.sunElevDeg > -1.0;
      double[] key = sunUp ? Sky.sun : Sky.moon;
      double elev = sunUp ? Sky.sunElevDeg : Sky.moonElevDeg;
      float strength = (float)Math.max(0.0, Math.min(1.0, (elev + 1.0) / 4.0)); // the disk fades in over its last degrees
      float clear = 1F - 0.92F * cloud - 0.6F * fog;
      if (sunUp) {
         strength *= Math.max(0F, clear) * Config.CAR_GLASS_SUN_PCT / 100F * 40F;
      } else {
         strength *= Math.max(0F, clear) * (float)Sky.moonBrightness * Config.CAR_GLASS_SUN_PCT / 100F * 0.6F;
      }
      f.sun[0] = (float)key[0];
      f.sun[1] = (float)key[1];
      f.sun[2] = (float)key[2];
      f.sun[3] = elev > -1.0 ? strength : 0F;
      if (cm != null && sunUp) {
         zombie.iso.weather.ClimateColorInfo gl = cm.getGlobalLight();
         zombie.core.Color ext = gl != null ? gl.getExterior() : null;
         if (ext != null) {
            f.sunC[0] = lin(Math.max(0.3F, ext.r));
            f.sunC[1] = lin(Math.max(0.3F, ext.g));
            f.sunC[2] = lin(Math.max(0.3F, ext.b));
         } else {
            f.sunC[0] = f.sunC[1] = f.sunC[2] = 1F;
         }
      } else {
         f.sunC[0] = 0.75F;
         f.sunC[1] = 0.82F;
         f.sunC[2] = 1F;
      }
      f.sunC[3] = Config.CAR_GLASS_SUN_ROUGH_PCT / 100F;
      SkyBox sb = SkyBox.getInstance();
      zombie.core.Color h = sb.getShaderSkyHColour(), l = sb.getShaderSkyLColour();
      // the skybox's own gradient (H at the zenith, L at the horizon) through its pow(0.6) "moody" gamma, then linear
      f.skyZ[0] = skyLin(h.r);
      f.skyZ[1] = skyLin(h.g);
      f.skyZ[2] = skyLin(h.b);
      f.skyH[0] = skyLin(l.r);
      f.skyH[1] = skyLin(l.g);
      f.skyH[2] = skyLin(l.b);
      // overcast / fog: the sky greys towards the fog colour (the skybox mixes 0.5 grey with its fog)
      float grey = Math.max(fog, cloud * 0.7F);
      for (int i = 0; i < 3; i++) {
         float g = lin(0.5F) * (0.25F + 0.75F * day);
         f.skyZ[i] += (g - f.skyZ[i]) * grey;
         f.skyH[i] += (g - f.skyH[i]) * grey;
      }
      f.skyZ[3] = Config.CAR_GLASS_SKY_TEXTURE && Core.getInstance().getPerfReflectionsOnLoad() ? 1F : 0F;
      f.skyH[3] = fog;
      // the sky texture's sun stands where SkyBox puts it (an east-west arc): turn the lookup so its azimuth is the real one's
      f.skyCos = 1F;
      f.skySin = 0F;
      Vector3f ss = sb.getShaderSunLight();
      if (ss != null && sunUp && (Math.abs(ss.x) + Math.abs(ss.z)) > 1e-3F) {
         double azTex = Math.atan2(ss.z, ss.x);
         double azReal = Math.atan2(key[1], -key[0]); // the real sun in the skybox frame (x west, z south)
         double d = azTex - azReal;
         f.skyCos = (float)Math.cos(d);
         f.skySin = (float)Math.sin(d);
      }
      f.groundK = 0.2F + 0.15F * day;
      f.seconds = (float)((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000F;
      f.rain = cm != null ? cm.getPrecipitationIntensity() : 0F;
   }

   private static float lin(float c) {
      return (float)Math.pow(Math.max(0F, Math.min(1F, c)), 2.2);
   }

   private static float skyLin(float c) {
      return (float)Math.pow(Math.pow(Math.max(0F, Math.min(1F, c)), 0.6), 2.2);
   }

   private static final IdentityHashMap<BaseVehicle, long[]> TILES = new IdentityHashMap<>(); // vehicle -> {tile, last frame seen}
   private static final boolean[] TILE_USED = new boolean[PROBE_MAX];
   private static boolean tileFresh;

   /** Game thread: the car's probe tile, kept while it is seen (freed 120 frames after it was last on screen). */
   private static int probeTile(BaseVehicle v) {
      long[] t = TILES.get(v);
      tileFresh = false;
      if (t != null) {
         t[1] = frames;
         return (int)t[0];
      }
      if (frames % 60 == 0 || TILES.size() >= PROBE_MAX) {
         TILES.entrySet().removeIf(en -> {
            if (frames - en.getValue()[1] > 120) {
               TILE_USED[(int)en.getValue()[0]] = false;
               return true;
            }
            return false;
         });
      }
      for (int i = 0; i < PROBE_MAX; i++) {
         if (!TILE_USED[i]) {
            TILE_USED[i] = true;
            TILES.put(v, new long[] {i, frames});
            tileFresh = true;
            return i;
         }
      }
      return -1;
   }

   /** Game thread: the cars on screen this frame (a screen box of the car), each with its probe tile, the probes due. */
   private static void collect(Frame f, int playerIndex) {
      if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
         return;
      }
      java.util.Collection<BaseVehicle> list = IsoWorld.instance.currentCell.getVehicles();
      float zoom = Core.getInstance().getZoom(playerIndex);
      float offX = IsoCamera.getOffX(), offY = IsoCamera.getOffY();
      f.screenW = IsoCamera.getScreenWidth(playerIndex);
      f.screenH = IsoCamera.getScreenHeight(playerIndex);
      f.pxScale = Core.getInstance().getScreenWidth() / Math.max(1F, f.screenW);
      float ts = Core.tileScale;
      for (BaseVehicle v : list) {
         if (v == null || v.getScript() == null || v.getCurrentSquare() == null || v.getAlpha(playerIndex) <= 0.01F) {
            continue;
         }
         Vector3f e = v.getScript().getExtents();
         float r = 0.5F * (float)Math.sqrt(e.x * e.x + e.z * e.z);
         float x = v.getX(), y = v.getY(), z = v.getZ();
         float cx = (IsoUtils.XToScreen(x, y, z, 0) - offX) / zoom;
         float cy = (IsoUtils.YToScreen(x, y, z, 0) - offY) / zoom;
         // a square is 64 x 32 tile px (x tileScale); the height 96 px a level, the car's e.y squares ~ e.y / 2.449 levels
         float hx = r * A32 * ts * 2F / zoom, hy = r * A16 * ts * 2F / zoom, hz = e.y / 2.44949F * 96F * ts / zoom;
         if (cx + hx < 0F || cy + hy < 0F || cx - hx > f.screenW || cy - hy - hz > f.screenH) {
            continue;
         }
         // size LOD: a car smaller on screen than carGlassMinPx (its length; screen px of the display) keeps the stock windows
         if (2F * hx * f.pxScale < Config.CAR_GLASS_MIN_PX) {
            lodSkipped++;
            continue;
         }
         int tile = PROBE ? probeTile(v) : -1;
         f.probeIndex.put(v, tile);
         if (tile < 0) {
            continue;
         }
         // every probe marched again together every carGlassProbeEvery frames (one pass then, none between), a new tile at
         // once, a moving car's every carGlassProbeMovingEvery frames
         boolean moving = Math.abs(v.getCurrentSpeedKmHour()) > 1F;
         boolean due = tileFresh || frames % Math.max(1, Config.CAR_GLASS_PROBE_EVERY) == 0 || (moving && frames % Math.max(1, Config.CAR_GLASS_PROBE_MOVING_EVERY) == 0);
         if (due && f.np < PROBE_MAX) {
            float z0 = (float)Math.floor(z + 0.05F);
            f.probe[f.np * 4] = x - f.ox;
            f.probe[f.np * 4 + 1] = y - f.oy;
            f.probe[f.np * 4 + 2] = z0 * 2.44949F + e.y * Config.CAR_GLASS_PROBE_HEIGHT_PCT / 100F;
            f.probe[f.np * 4 + 3] = tile;
            f.np++;
         }
      }
   }

   // ------------------------------------------------------------------------------------------------ the world framebuffer (render thread)

   private static int worldColor, worldDepth, worldW, worldH, worldInfoFbo = -1;
   static final int WORLD_UNIT = 11, WORLD_DEPTH_UNIT = 12;
   private static final int[] VPI = new int[4];
   private static final float[] VP = new float[4];

   /** Render thread, carGlassSnapMode=live: the world framebuffer's textures and the viewport, once a frame; no copies. */
   private static void liveSetup() {
      try {
         int worldFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, VPI);
         VP[0] = VPI[0];
         VP[1] = VPI[1];
         VP[2] = VPI[2];
         VP[3] = VPI[3];
         if (worldFbo != worldInfoFbo) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, worldFbo);
            worldInfo(worldFbo);
         }
         if (PROBE && Config.CAR_GLASS_FRAME_BARRIER) {
            org.lwjgl.opengl.GL45.glTextureBarrier(); // the world as drawn before the vehicles: the glass's reads (the probes read it from their own framebuffer)
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("car glass: live setup failed, off: " + t);
      }
   }

   /** Render thread, the world framebuffer bound for reading: its colour / depth textures and their size (asked once per framebuffer). */
   private static void worldInfo(int fbo) {
      worldInfoFbo = fbo;
      worldColor = attachment(GL30.GL_COLOR_ATTACHMENT0);
      worldDepth = attachment(GL30.GL_DEPTH_STENCIL_ATTACHMENT);
      if (worldDepth == 0) {
         worldDepth = attachment(GL30.GL_DEPTH_ATTACHMENT);
      }
      if (worldColor != 0) {
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldColor);
         worldW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
         worldH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         zombie.core.textures.Texture.lastTextureID = -1;
      }
      Log.info("car glass: world framebuffer " + fbo + " colour " + worldColor + " " + worldW + "x" + worldH + ", depth " + worldDepth);
   }

   private static int attachment(int attachment) {
      int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
      if (type != GL11.GL_TEXTURE) {
         return 0;
      }
      return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
   }


   // ------------------------------------------------------------------------------------------------ reflection probes

   static final boolean PROBE = "probe".equals(Config.CAR_GLASS_SSR_MODE);
   private static int probeProg, probeTex, probeFbo;
   private static int[] probeLoc;
   private static final FloatBuffer PROBE_CARS = BufferUtils.createFloatBuffer(PROBE_MAX * 4);
   private static final float[] MAPK = new float[6];
   static long probeFrames, probeCars;

   /**
    * One probe per car: PROBE_TILE^2 octahedral directions around a point at the car's window height; each texel marches
    * its direction through the world depth as drawn before the vehicles (iso depth x + y + 2z), the colour where it
    * passes behind a surface, alpha 1; 0 where it leaves the screen or reaches nothing.
    */
   static final String PROBE_VERT = String.join("\n",
         "#version 330",
         "uniform vec4 pzCars[" + PROBE_MAX + "]; // xyz the probe centre (squares, z metric) relative to the origin square; w its tile",
         "out vec2 vUv;",
         "flat out vec4 vCar;",
         "void main() {",
         "  vec4 car = pzCars[gl_InstanceID];",
         "  vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));",
         "  float col = mod(car.w, " + PROBE_COLS + ".0), row = floor(car.w / " + PROBE_COLS + ".0);",
         "  vec2 xy = (vec2(col, row) + c) / " + PROBE_COLS + ".0;",
         "  vUv = c;",
         "  vCar = car;",
         "  gl_Position = vec4(xy * 2.0 - 1.0, 0.0, 1.0);",
         "}");

   static final String PROBE_FRAG = String.join("\n",
         "#version 330",
         "in vec2 vUv;",
         "flat in vec4 vCar;",
         "out vec4 fragColor;",
         "uniform sampler2D pzWorldC;",
         "uniform sampler2D pzWorldD;",
         "uniform vec4 pzMap;   // px = (u - y) / x for u = x - y: (kA, cA, kB, cB)",
         "uniform vec4 pzDep;   // w = depth * x + y",
         "uniform vec4 pzTex;   // 1 / world texture w, h; steps; thickness",
         "uniform vec4 pzVp;    // viewport",
         "uniform vec4 pzReach; // x reach (squares), y first step",
         "vec3 octDecode(vec2 e) {",
         "  e = e * 2.0 - 1.0;",
         "  vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));",
         "  if (n.z < 0.0) n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);",
         "  return normalize(n);",
         "}",
         "bool px(vec3 p, out vec2 q, out float wr) {",
         "  float zl = p.z / 2.44949;",
         "  q = vec2((p.x - p.y - pzMap.y) / pzMap.x, (p.x + p.y - 6.0 * zl - pzMap.w) / pzMap.z);",
         "  wr = p.x + p.y + 2.0 * zl;",
         "  return q.x > pzVp.x && q.y > pzVp.y && q.x < pzVp.x + pzVp.z && q.y < pzVp.y + pzVp.w;",
         "}",
         "void main() {",
         "  vec3 d = octDecode(vUv);",
         "  if (pzReach.z > 0.5) { fragColor = vec4(d * 0.5 + 0.5, 0.0); return; } // dev: no world reads",
         "  vec3 o = vCar.xyz;",
         "  float t = pzReach.y;",
         "  float k = pow(pzReach.x / pzReach.y, 1.0 / max(pzTex.z - 1.0, 1.0));",
         "  float tPrev = 0.0;",
         "  fragColor = vec4(0.0);",
         "  for (int i = 0; i < 32; i++) {",
         "    if (float(i) >= pzTex.z) break;",
         "    vec2 q; float wr;",
         "    if (!px(o + d * t, q, wr)) break;",
         "    float ws = texture(pzWorldD, q * pzTex.xy).r * pzDep.x + pzDep.y;",
         "    float dw = ws - wr;",
         "    if (dw > 0.0 && dw < pzTex.w + t * 0.15) {",
         "      float lo = tPrev, hi = t;",
         "      for (int j = 0; j < 3; j++) {",
         "        float m = 0.5 * (lo + hi);",
         "        vec2 qm; float wm;",
         "        px(o + d * m, qm, wm);",
         "        if (texture(pzWorldD, qm * pzTex.xy).r * pzDep.x + pzDep.y - wm > 0.0) hi = m; else lo = m;",
         "      }",
         "      vec2 qh; float wh;",
         "      px(o + d * hi, qh, wh);",
         "      fragColor = vec4(texture(pzWorldC, qh * pzTex.xy).rgb, 1.0 - 0.5 * clamp(hi / pzReach.x, 0.0, 1.0));",
         "      return;",
         "    }",
         "    tPrev = t;",
         "    t *= k;",
         "  }",
         "}");

   /** Render thread, before the vehicles draw (the world framebuffer bound, its textures known): this frame's probes. */
   private static void probes(Frame f, int worldFbo) {
      if (f.np == 0 || worldColor == 0 || worldDepth == 0) {
         return;
      }
      if (probeProg == 0) {
         probeProg = Shaders.program("car glass probes", PROBE_VERT, PROBE_FRAG);
         if (probeProg == 0) {
            failed = true;
            return;
         }
         String[] names = {"pzCars", "pzWorldC", "pzWorldD", "pzMap", "pzDep", "pzTex", "pzVp", "pzReach"};
         probeLoc = new int[names.length];
         for (int i = 0; i < names.length; i++) {
            probeLoc[i] = GL20.glGetUniformLocation(probeProg, names[i]);
         }
         int size = PROBE_TILE * PROBE_COLS;
         probeTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, probeTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         probeFbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, probeFbo);
         GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, probeTex, 0);
         GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, worldFbo);
         zombie.core.textures.Texture.lastTextureID = -1;
         Log.info("car glass: reflection probes " + size + "x" + size + " (" + PROBE_MAX + " tiles of " + PROBE_TILE + "^2)");
      }
      f.iso.mapping(VP, MAPK);
      PROBE_CARS.clear();
      for (int i = 0; i < f.np; i++) {
         PROBE_CARS.put(f.probe[i * 4]).put(f.probe[i * 4 + 1]).put(f.probe[i * 4 + 2]).put(f.probe[i * 4 + 3]);
      }
      PROBE_CARS.flip();
      int size = PROBE_TILE * PROBE_COLS;
      if ((skip() & 1) != 0) {
         return; // dev: no probe pass at all (its setup only)
      }
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, probeFbo);
      GL11.glViewport(0, 0, size, size);
      GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_ALPHA_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glDepthMask(false);
      GL11.glColorMask(true, true, true, true);
      zombie.core.ShaderHelper.glUseProgramObjectARB(probeProg);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + WORLD_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldColor);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + WORLD_DEPTH_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldDepth);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL20.glUniform4fv(probeLoc[0], PROBE_CARS);
      GL20.glUniform1i(probeLoc[1], WORLD_UNIT);
      GL20.glUniform1i(probeLoc[2], WORLD_DEPTH_UNIT);
      GL20.glUniform4f(probeLoc[3], MAPK[0], MAPK[1], MAPK[2], MAPK[3]);
      GL20.glUniform4f(probeLoc[4], (float)(-1.0 / PixelLight.DEPTH_PER_XY), (float)(f.d0 / PixelLight.DEPTH_PER_XY), 0F, 0F);
      GL20.glUniform4f(probeLoc[5], 1F / worldW, 1F / worldH, Config.CAR_GLASS_SSR_STEPS, Config.CAR_GLASS_SSR_THICKNESS_PCT / 100F);
      GL20.glUniform4f(probeLoc[6], VP[0], VP[1], VP[2], VP[3]);
      GL20.glUniform4f(probeLoc[7], Config.CAR_GLASS_SSR_REACH_PCT / 100F, 0.15F, (skip() & 4) != 0 ? 1F : 0F, 0F);
      if ((skip() & 2) == 0) {
         org.lwjgl.opengl.GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, f.np); // (dev bit 2: the framebuffer switch alone)
      }
      zombie.core.ShaderHelper.glUseProgramObjectARB(0);
      if (!Config.CAR_GLASS_LIVE_READS) {
         // the vehicles draw into the world framebuffer next: none of its own textures stays bound for sampling there
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + WORLD_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + WORLD_DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }
      GL11.glPopAttrib();
      zombie.core.ShaderHelper.forgetCurrentlyBound();
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, worldFbo);
      GL11.glViewport(VPI[0], VPI[1], VPI[2], VPI[3]);
      zombie.core.textures.Texture.lastTextureID = -1;
      probeFrames++;
      probeCars += f.np;
   }

   // ------------------------------------------------------------------------------------------------ per draw (render thread)

   // per-frame array pzGlassFr (uploaded once a frame per program) and per-car array pzGlassCar (one upload a draw)
   static final int FR = 9, CAR = 54;
   private static final int C_V = 0, C_E = 1, C_S = 2, C_UP = 3, C_SUN = 4, C_CAB0 = 5, C_CAB1 = 6, C_INT = 7, C_RAIN = 8, C_K = 9, C_PC = 10, C_O = 11,
      C_TEX = 12, C_CLIP = 13, C_SEAT = 17, C_L = 23, C_LC = 28, C_WIN = 33, C_BLOOD = 39, C_LIT = 41, C_OCC = 42, C_PAL = 48;
   private static final int U_A = 0, U_M = 1, U_FR = 2, U_CAR = 3;
   private static final String[] UNIFORMS = {"pzGlassFr", "pzGlassM", "pzGlassFr", "pzGlassCar"};
   private static final java.util.HashMap<Integer, int[]> LOCATIONS = new java.util.HashMap<>();
   private static final java.util.HashMap<Integer, Long> FRAME_SET = new java.util.HashMap<>(); // program -> the frame serial its pzGlassFr holds

   /** A glass program's uniform locations, looked up once; its samplers set then (they keep their units for good). */
   private static int[] locations(int prog) {
      int[] l = LOCATIONS.get(prog);
      if (l == null) {
         l = new int[UNIFORMS.length];
         for (int i = 0; i < l.length; i++) {
            l[i] = GL20.glGetUniformLocation(prog, UNIFORMS[i]);
         }
         LOCATIONS.put(prog, l);
         if (l[U_A] >= 0) {
            String[] samplers = {"TextureMask", "TextureDamage1Overlay", "TextureDamage1Shell", "TextureDamage2Overlay", "TextureDamage2Shell", "TextureReflectionA",
               "TextureReflectionB", "pzGlassWorldT", "pzGlassWorldD", "pzGlassProbeT", "pzGlassClass", "pzOccC", "pzOccD"};
            int[] units = {2, 4, 5, 6, 7, 8, 9, WORLD_UNIT, WORLD_DEPTH_UNIT, PROBE_UNIT, CLASS_UNIT, CarOccupant.COLOR_UNIT, CarOccupant.DEPTH_UNIT};
            for (int i = 0; i < samplers.length; i++) {
               int loc = GL20.glGetUniformLocation(prog, samplers[i]);
               if (loc >= 0) {
                  // direct state: the program need not be bound (glassFor asks before it binds it)
                  org.lwjgl.opengl.GL41.glProgramUniform1i(prog, loc, units[i]);
               }
            }
         }
      }
      return l;
   }

   private static final Matrix4f MV = new Matrix4f(), P = new Matrix4f(), MESH_TO_G = new Matrix4f(), G_TO_EYE = new Matrix4f(), CLIP = new Matrix4f(), TMP = new Matrix4f();
   private static final Matrix3f A_INV = new Matrix3f(), W = new Matrix3f();
   static final Matrix3f ISO = new Matrix3f().rotateX(0.5235988F).rotateY(2.3561945F);
   private static final Vector3f V3 = new Vector3f();
   private static final org.joml.Vector4f V4A = new org.joml.Vector4f();
   private static final FloatBuffer M16 = BufferUtils.createFloatBuffer(16), FRB = BufferUtils.createFloatBuffer(FR * 4), CARB = BufferUtils.createFloatBuffer(CAR * 4);
   private static final float[] C = new float[CAR * 4];
   private static final org.joml.Quaternionf Q = new org.joml.Quaternionf();
   private static long lastDiagNs, lastOccDiagNs, lastPalNs;

   private static final java.util.HashMap<String, Shader> GLASS = new java.util.HashMap<>();

   /**
    * Render thread, Model.drawVehicle right after the stock draw of a car body: the glass program to draw over the windows
    * (build.sh's pzopt_glass_ copy of the same vehicle shader, glass-patched), or null when this draw gets none (glass off, a
    * sub-model, not on the frame's list, no program).
    */
   public static Shader glassFor(Shader effect, ModelSlotRenderData slot, ModelInstanceRenderData inst, VehicleModelInstance vmi) {
      Frame f = renderFrame;
      if (!active() || f == null || !f.on || effect == null || vmi == null || inst.modelInstance != vmi || !(slot.object instanceof BaseVehicle v)
            || !f.probeIndex.containsKey(v)) {
         return null;
      }
      if (zombie.core.skinnedmodel.ModelCamera.instance != VehicleModelCamera.instance) {
         sunViewSkips++;
         return null; // the same car drawn from another camera (the sun into the shadow atlas): no glass there
      }
      if (glassMap(vmi) == null) {
         return null; // this skin's glass map is being built (a few frames once per skin): the stock windows meanwhile
      }
      String key = effect.getName() + (effect.isStatic() ? "#s" : "#k");
      Shader g = GLASS.get(key);
      if (g == null && !GLASS.containsKey(key)) {
         try {
            g = zombie.core.skinnedmodel.shader.ShaderManager.instance.getOrCreateShader("pzopt_glass_" + effect.getName(), effect.isStatic(), false);
            if (g == null || g.getID() == 0 || locations(g.getID())[U_CAR] < 0) {
               int id = g == null ? 0 : g.getID();
               Log.warn("car glass: no glass program for " + key + " (program " + id + (id != 0 ? ", link " + GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) + ": "
                     + GL20.glGetProgramInfoLog(id, 2048) : "") + ")");
               g = null;
            }
         } catch (Throwable t) {
            Log.warn("car glass: glass program for " + key + " failed: " + t);
            g = null;
         }
         GLASS.put(key, g);
      }
      return g;
   }

   /** Is the glass program the compact one (the game's setters are not needed then: CarGlass.draw fills everything it reads)? */
   public static boolean compact() {
      return Config.CAR_GLASS_COMPACT;
   }

   static long drawNs, sunViewSkips;

   /**
    * Render thread, Model.pzoptDrawGlass (the glass program bound, the matrix stacks holding the mesh's model view): this
    * car's glass data, one upload (and the frame's, once per frame and program).
    */
   public static void draw(Shader glass, ModelSlotRenderData slot, ModelInstanceRenderData inst, VehicleModelInstance vmi, float ambR, float ambG, float ambB,
         float tintR, float tintG, float tintB) {
      if (patchedFrag == 0) {
         return;
      }
      long t0 = System.nanoTime();
      try {
         drawUniforms(glass, slot, inst, vmi, ambR, ambG, ambB, tintR, tintG, tintB);
      } catch (Throwable t) {
         failed = true;
         Log.warn("car glass: draw uniforms failed, off: " + t);
      } finally {
         drawNs += System.nanoTime() - t0;
      }
   }

   private static void put(int slot, float x, float y, float z, float w) {
      C[slot * 4] = x;
      C[slot * 4 + 1] = y;
      C[slot * 4 + 2] = z;
      C[slot * 4 + 3] = w;
   }

   private static void drawUniforms(Shader glass, ModelSlotRenderData slot, ModelInstanceRenderData inst, VehicleModelInstance vmi, float ambR, float ambG, float ambB,
         float tintR, float tintG, float tintB) {
      int prog = glass.getID();
      int[] l = locations(prog);
      Frame f = renderFrame;
      if (l[U_CAR] < 0 || f == null || !(slot.object instanceof BaseVehicle v) || v.getScript() == null) {
         return;
      }
      Long held = FRAME_SET.get(prog);
      if (held == null || held != f.serial) {
         frameUniforms(f, l[U_FR]);
         FRAME_SET.put(prog, f.serial);
      }
      VehicleScript script = v.getScript();
      // mesh -> G: BaseVehicle.updateTransform's model transform, then the mesh's own (ModelInstanceRenderData.postMultiplyMeshTransform)
      float s = script.getModelScale();
      float s2 = vmi.scale != 1F ? vmi.scale : 1F;
      float invX = vmi.modelScript != null && vmi.modelScript.invertX ? -1F : 1F;
      Vector3f mo = script.getModel().getOffset();
      Vector3f mr = script.getModel().getRotate();
      Q.rotationXYZ(mr.x * 0.017453292F, mr.y * 0.017453292F, mr.z * 0.017453292F);
      MESH_TO_G.translationRotateScale(-mo.x, mo.y, mo.z, Q.x, Q.y, Q.z, Q.w, s * s2 * invX, s * s2, s * s2);
      if (vmi.model != null && vmi.model.mesh != null && vmi.model.mesh.transform != null && vmi.model.mesh.isReady()) {
         TMP.set(vmi.model.mesh.transform).transpose();
         MESH_TO_G.mul(TMP);
      }
      MV.set(Core.getInstance().modelViewMatrixStack.peek());
      P.set(Core.getInstance().projectionMatrixStack.peek());
      TMP.set(MESH_TO_G).invert();
      G_TO_EYE.set(MV).mul(TMP);
      CLIP.set(P).mul(G_TO_EYE);
      if (CarOccupant.impostor() && hasOccupant(v)) {
         CarOccupant.chassis(v, G_TO_EYE); // the occupants' seats are placed in this frame next frame
      }
      // eye <- G (scaled rotation, mirrored for vehicles); world GL directions -> G through the iso camera's rotation
      G_TO_EYE.get3x3(A_INV);
      float scale = (float)Math.cbrt(Math.abs(A_INV.determinant()));
      A_INV.invert();
      W.set(A_INV).mul(ISO).scale(scale);
      M16.clear();
      MESH_TO_G.get(M16);
      GL20.glUniformMatrix4fv(l[U_M], false, M16);
      A_INV.transform(V3.set(0F, 0F, -1F)).normalize();
      put(C_V, V3.x, V3.y, V3.z, Config.CAR_GLASS_SEAT_FWD);
      // world east / south / up in G: GL world g = (-x, z, -y)
      W.transform(V3.set(-1F, 0F, 0F)).normalize();
      put(C_E, V3.x, V3.y, V3.z, 0F);
      W.transform(V3.set(0F, 0F, -1F)).normalize();
      put(C_S, V3.x, V3.y, V3.z, 0F);
      W.transform(V3.set(0F, 1F, 0F)).normalize();
      float upx = V3.x, upy = V3.y, upz = V3.z;
      // the ground: (floor - z) levels below the car's origin, the G origin centerOfMassMagic above the slot's centre of mass (calibrated)
      float zFloor = (float)Math.floor(slot.z + 0.05F);
      float ground = (zFloor - slot.z) * 2.44949F - slot.centerOfMassY - BaseVehicle.centerOfMassMagic + Config.CAR_GLASS_GROUND_OFFSET_PCT / 100F;
      put(C_UP, upx, upy, upz, ground);
      W.transform(V3.set(-f.sun[0], f.sun[2], -f.sun[1])).normalize();
      put(C_SUN, V3.x, V3.y, V3.z, f.sun[3]);
      float rain = f.rain * Config.CAR_GLASS_RAIN_PCT / 100F;
      zombie.iso.IsoGridSquare vsq = v.getCurrentSquare();
      if (rain > 0F && (vsq == null || !vsq.isOutside())) {
         rain = 0F;
      }
      put(C_RAIN, Math.min(1F, rain), 0F, Math.min(1F, Math.abs(v.getCurrentSpeedKmHour()) / 80F), 0F);
      CabinData cd = cabinData(script);
      // the occupants: the game's models through the impostor tile (seat 3: no proxy), the torso box / head ball (2), none (1)
      CarOccupant.Tile occ = (skip() & (2048 | 1024)) != 0 ? null : CarOccupant.tileFor(v, f.serial, f.player);
      float occupied = occ != null ? 3F : CarOccupant.seatProxy();
      if (occ != null) {
         CarOccupant.bindAtlas();
         put(C_OCC, occ.minX, occ.minY, occ.invW, occ.invH);
         // the tile drawn this frame or a few frames ago: G -> its world NDC then
         put(C_OCC + 4, occ.rx[0], occ.rx[1], occ.rx[2], occ.rx[3]);
         put(C_OCC + 5, occ.ry[0], occ.ry[1], occ.ry[2], occ.ry[3]);
         put(C_OCC + 1, occ.u0, occ.v0, occ.du, occ.dv);
         put(C_OCC + 2, occ.seatDepth, Config.CAR_OCCUPANT_OCCLUSION ? 1F : 0F, 0F, 0F);
         put(C_OCC + 3, 0F, 0F, 0F, 0F);
         for (int si = 0; si < cd.n; si++) {
            if (cd.index[si] == occ.seat) {
               // the model's origin: the script's inside position (the cabin's seat point is that + the hip height)
               put(C_OCC + 3, cd.seat[si * 3], cd.seat[si * 3 + 1] - Config.CAR_GLASS_SEAT_HIP_PCT / 100F + Config.DEV_CAR_OCCUPANT_Y_PCT / 100F, cd.seat[si * 3 + 2], 1F);
            }
         }
      } else {
         put(C_OCC, 0F, 0F, 0F, 0F);
         put(C_OCC + 1, 0F, 0F, 0F, 0F);
         put(C_OCC + 2, 0F, 0F, 0F, 0F);
         put(C_OCC + 3, 0F, 0F, 0F, 0F);
         put(C_OCC + 4, 0F, 0F, 0F, 0F);
         put(C_OCC + 5, 0F, 0F, 0F, 0F);
      }
      for (int i = 0; i < 6; i++) {
         if (i < cd.n) {
            zombie.characters.IsoGameCharacter sc = v.getCharacter(cd.index[i]);
            put(C_SEAT + i, cd.seat[i * 3], cd.seat[i * 3 + 1], cd.seat[i * 3 + 2], sc != null ? occupied : 1F);
            if (sc != null && occupied > 3.5F) {
               float[] pal = CarOccupant.palette(sc);
               put(C_PAL + i, pal[0], pal[1], pal[2], pal[3]);
               if (Config.INSTRUMENT && System.nanoTime() - lastPalNs > 3_000_000_000L) {
                  lastPalNs = System.nanoTime();
                  Log.info(String.format(java.util.Locale.ROOT, "car occupant: proxy colours seat %d: top %06x legs %06x skin %06x hair %06x%s", cd.index[i], (int)pal[0], (int)pal[1], (int)pal[2], (int)pal[3], pal[5] > 0.5F ? "" : " (pending)"));
               }
            }
         } else {
            put(C_SEAT + i, 0F, 0F, 0F, 0F);
         }
      }
      put(C_CAB0, cd.cab0[0], cd.cab0[1], cd.cab0[2], cd.cab0[3]);
      put(C_CAB1, cd.cab1[0], cd.cab1[1], cd.cab1[2], cd.cab1[3]);
      put(C_INT, cd.fabric[0], cd.fabric[1], cd.fabric[2], Config.CAR_GLASS_INTERIOR_PCT / 100F);
      // the five model lights (ModelSlotRenderData: chassis-local positions); the car's stock-like light for the cabin and the overlays
      float lr = ambR, lg = ambG, lb = ambB;
      for (int i = 0; i < 5; i++) {
         ModelInstance.EffectLight el = slot.effectLights[i];
         if (el == null || el.radius <= 0 || el.r + el.g + el.b <= 0F) {
            put(C_L + i, 0F, 0F, 0F, 0F);
            put(C_LC + i, 0F, 0F, 0F, 0F);
            continue;
         }
         put(C_L + i, -el.x, el.y, el.z, el.radius);
         put(C_LC + i, el.r, el.g, el.b, 0F);
         float d = (float)Math.sqrt(el.x * el.x + el.y * el.y + el.z * el.z);
         float att = Math.max(0F, Math.min(1F, 1F - d / el.radius)) * 0.5F;
         lr += el.r * att;
         lg += el.g * att;
         lb += el.b * att;
      }
      float grey = 0.2126F * tintR + 0.7152F * tintG + 0.0722F * tintB;
      put(C_LIT, Math.min(1F, lr) * (tintR + (grey - tintR) * 0.3F), Math.min(1F, lg) * (tintG + (grey - tintG) * 0.3F), Math.min(1F, lb) * (tintB + (grey - tintB) * 0.3F),
            slot.alpha);
      // the windows' flags: the stock enables matrices (uploaded transposed: m[c][r] = a[r * 4 + c]); zones 7..12 = m[1][2] m[1][3] m[2][0] m[2][1] m[2][2] m[2][3]
      int[] at = {9, 13, 2, 6, 10, 14};
      for (int k = 0; k < 6; k++) {
         int i = at[k];
         put(C_WIN + k, el(vmi.textureDamage1Enables1, i) >= 0.5F ? 1F : 0F, el(vmi.textureDamage2Enables1, i) >= 0.5F ? 1F : 0F,
               el(vmi.textureUninstall1, i) >= 0.5F ? 1F : 0F, el(vmi.matrixBlood1Enables1, i));
      }
      put(C_BLOOD, b2(vmi, at[0]), b2(vmi, at[1]), b2(vmi, at[2]), b2(vmi, at[3]));
      put(C_BLOOD + 1, b2(vmi, at[4]), b2(vmi, at[5]), 0F, 0F);
      GlassMap gmap = glassMap(vmi);
      if (gmap != null) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + CLASS_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, gmap.tex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }
      Integer tile = f.probeIndex.get(v);
      float seats = cd.n;
      if (tile != null && tile >= 0 && probeTex != 0) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + PROBE_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, probeTex);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         put(C_K, vertexEnv(), seats, CABIN, tile);
         // the probe centre (the car's x, y at its floor + carGlassProbeHeightPct of its height) from the G origin, along world up
         float zFloorM = zFloor * 2.44949F + script.getExtents().y * Config.CAR_GLASS_PROBE_HEIGHT_PCT / 100F;
         float dy = zFloorM - (slot.z * 2.44949F + slot.centerOfMassY + BaseVehicle.centerOfMassMagic);
         put(C_PC, upx * dy, upy * dy, upz * dy, Config.CAR_GLASS_PROBE_PARALLAX ? 1F : 0F);
      } else {
         put(C_K, vertexEnv(), seats, CABIN, -1F);
         put(C_PC, 0F, 0F, 0F, 0F);
      }
      // the world as drawn so far (the pixel march, the scene behind the far window, the ground per pixel): only without
      // probes or with carGlassLiveReads; a barrier per car makes it coherent for this draw
      boolean liveReads = (!PROBE || Config.CAR_GLASS_LIVE_READS) && worldColor != 0 && worldDepth != 0 && (skip() & 8) == 0;
      if (liveReads) {
         org.lwjgl.opengl.GL45.glTextureBarrier();
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + WORLD_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldColor);
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + WORLD_DEPTH_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldDepth);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
      }
      put(C_TEX, liveReads ? 1F / worldW : 0F, liveReads ? 1F / worldH : 0F, PROBE || !liveReads ? 0F : Config.CAR_GLASS_SSR_STEPS, Config.CAR_GLASS_SSR_THICKNESS_PCT / 100F);
      float comZ = (slot.centerOfMassY + BaseVehicle.centerOfMassMagic) / 2.44949F;
      put(C_O, slot.x - f.ox, slot.y - f.oy, slot.z + comZ, Config.CAR_GLASS_SSR_REACH_PCT / 100F);
      CLIP.get(C, C_CLIP * 4);
      CARB.clear();
      CARB.put(C);
      CARB.flip();
      GL20.glUniform4fv(l[U_CAR], CARB);
      draws++;
      if (occ != null && Config.INSTRUMENT && System.nanoTime() - lastOccDiagNs > 2_000_000_000L) {
         lastOccDiagNs = System.nanoTime();
            org.joml.Vector4f o4 = CLIP.transform(new org.joml.Vector4f(0F, 0F, 0F, 1F));
            for (int si = 0; si < cd.n; si++) {
               if (v.getCharacter(cd.index[si]) != null) {
                  org.joml.Vector4f sq = CLIP.transform(new org.joml.Vector4f(cd.seat[si * 3], cd.seat[si * 3 + 1], cd.seat[si * 3 + 2], 1F));
                  float sw = (sq.z * 0.5F + 0.5F) + (inst.modelInstance != null ? inst.modelInstance.targetDepth : 0.5F) - 0.5F;
                  float ow = (CarOccupant.lastSeatClip[2] * 0.5F + 0.5F) + (inst.modelInstance != null ? inst.modelInstance.targetDepth : 0.5F) - occ.td;
                  float tx = (CarOccupant.lastSeatClip[0] + 1F) / 2F / occ.invW + occ.minX, ty = (CarOccupant.lastSeatClip[1] + 1F) / 2F / occ.invH + occ.minY;
                  float cz = (ow - (inst.modelInstance != null ? inst.modelInstance.targetDepth : 0.5F)) * 2F; // the model's origin, the car draw's clip z
                  org.joml.Vector4f mg = new Matrix4f(CLIP).invert().transform(new org.joml.Vector4f(tx, ty, cz, 1F));
                  Log.info(String.format(java.util.Locale.ROOT, "car occupant: seat %d (G %.3f %.3f %.3f): the glass's ndc %.4f %.4f depth %.5f; the model's seat origin ndc %.4f %.4f depth %.5f = G %.3f %.3f %.3f",
                        cd.index[si], cd.seat[si * 3], cd.seat[si * 3 + 1], cd.seat[si * 3 + 2], sq.x, sq.y, sw, tx, ty, ow, mg.x / mg.w, mg.y / mg.w, mg.z / mg.w));
               }
            }
            if (CarOccupant.devCentroid[2] > 0F) {
               float ccz = (CarOccupant.devCentroid[2] - occ.td) * 2F; // the centroid's clip z (the occupants' terms = the car's without its targetDepth)
               org.joml.Vector4f cg = new Matrix4f(CLIP).invert().transform(new org.joml.Vector4f(CarOccupant.devCentroid[0], CarOccupant.devCentroid[1], ccz, 1F));
               Log.info(String.format(java.util.Locale.ROOT, "car occupant: the drawn occupant's centroid is at G %.3f %.3f %.3f", cg.x / cg.w, cg.y / cg.w, cg.z / cg.w));
               if (CarOccupant.devSampleN > 0) {
                  Matrix4f inv = new Matrix4f(CLIP).invert();
                  int n = CarOccupant.devSampleN;
                  float[] ys = new float[n], zs = new float[n];
                  for (int si = 0; si < n; si++) {
                     org.joml.Vector4f g = inv.transform(new org.joml.Vector4f(CarOccupant.DEV_SAMPLES[si * 3], CarOccupant.DEV_SAMPLES[si * 3 + 1], (CarOccupant.DEV_SAMPLES[si * 3 + 2] - occ.td) * 2F, 1F));
                     ys[si] = g.y / g.w;
                     zs[si] = g.z / g.w;
                  }
                  java.util.Arrays.sort(ys);
                  java.util.Arrays.sort(zs);
                  Log.info(String.format(java.util.Locale.ROOT, "car occupant: the drawn occupant's G height p2 %.3f p50 %.3f p98 %.3f max %.3f; along z p2 %.3f p50 %.3f p98 %.3f (%d texels)", ys[n / 50], ys[n / 2], ys[n * 49 / 50], ys[n - 1], zs[n / 50], zs[n / 2], zs[n * 49 / 50], n));
               }
            }
            org.joml.Vector4f kv = CLIP.transform(new org.joml.Vector4f(C[C_V * 4], C[C_V * 4 + 1], C[C_V * 4 + 2], 0F));
            Log.info(String.format(java.util.Locale.ROOT, "car occupant: k (window depth per G unit along the view) %.6f, view G (%.3f %.3f %.3f), clip of V %.4f %.4f; G origin window depth %.5f",
                  0.5F * kv.z, C[C_V * 4], C[C_V * 4 + 1], C[C_V * 4 + 2], kv.x, kv.y, (o4.z * 0.5F + 0.5F) + (inst.modelInstance != null ? inst.modelInstance.targetDepth : 0.5F) - 0.5F));
            Log.info("car occupant: viewport " + VP[0] + "," + VP[1] + " " + VP[2] + "x" + VP[3] + ", G origin px " + ((o4.x * 0.5F + 0.5F) * VP[2] + VP[0]) + "," + ((o4.y * 0.5F + 0.5F) * VP[3] + VP[1]) + " targetDepth " + (inst.modelInstance != null ? inst.modelInstance.targetDepth : -1F) + " occupants' " + occ.td);
            Log.info(String.format(java.util.Locale.ROOT, "car occupant: glass G origin ndc %.4f %.4f z %.5f w %.4f; tile ndc rect %.4f..%.4f, %.4f..%.4f; P %s MV %s",
                  o4.x / o4.w, o4.y / o4.w, o4.z, o4.w, occ.minX, occ.minX + 1F / occ.invW, occ.minY, occ.minY + 1F / occ.invH, P.toString(new java.text.DecimalFormat("0.0000")), MV.toString(new java.text.DecimalFormat("0.0000"))));
         }
      if (Config.INSTRUMENT && System.nanoTime() - lastDiagNs > 10_000_000_000L) {
         lastDiagNs = System.nanoTime();
         Log.info(String.format(java.util.Locale.ROOT, "car glass: %s scale %.3f view G (%.3f %.3f %.3f) up G (%.3f %.3f %.3f) ground %.3f comY %.3f z %.3f tile %s seats %d",
               script.getName(), scale, C[0], C[1], C[2], upx, upy, upz, ground, slot.centerOfMassY, slot.z, tile, cd.n));
      }
   }

   private static boolean hasOccupant(BaseVehicle v) {
      for (int i = 0, n = v.getMaxPassengers(); i < n; i++) {
         if (v.getCharacter(i) != null) {
            return true;
         }
      }
      return false;
   }

   /** 1 = the fragment unit takes the vertex unit's per-panel environment (carGlassVertexEnv; dev skip bit 512 turns it off). */
   private static float vertexEnv() {
      return Config.CAR_GLASS_VERTEX_ENV && (skip() & 512) == 0 ? 1F : 0F;
   }

   private static float el(float[] m, int i) {
      return m != null && i < m.length ? m[i] : 0F;
   }

   private static float b2(VehicleModelInstance vmi, int i) {
      return el(vmi.matrixBlood2Enables1, i) >= 0.5F ? 1F : 0F;
   }

   /** The frame's constants into a program's pzGlassFr (once per frame and program). */
   private static void frameUniforms(Frame f, int loc) {
      if (VP[2] == 0F) {
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, VPI);
         VP[0] = VPI[0];
         VP[1] = VPI[1];
         VP[2] = VPI[2];
         VP[3] = VPI[3];
      }
      FRB.clear();
      FRB.put(Config.CAR_GLASS_STRENGTH_PCT / 100F).put(Config.CAR_GLASS_F0_PCT / 100F).put(f.view).put(f.seconds);
      FRB.put(f.sunC[0]).put(f.sunC[1]).put(f.sunC[2]).put(f.sunC[3]);
      FRB.put(f.skyZ[0]).put(f.skyZ[1]).put(f.skyZ[2]).put(f.skyZ[3]);
      FRB.put(f.skyH[0]).put(f.skyH[1]).put(f.skyH[2]).put(f.skyH[3]);
      FRB.put(f.skyCos).put(f.skySin).put(SkyBox.getInstance().getTextureShift()).put(f.groundK);
      FRB.put(VP[0]).put(VP[1]).put(VP[2]).put(VP[3]);
      FRB.put(Config.CAR_GLASS_TRANSMIT_PCT / 100F).put(Config.CAR_GLASS_TINT_PCT / 100F).put(Config.CAR_GLASS_LAMP_ROUGH_PCT / 100F).put(Config.CAR_GLASS_REFLECT_PCT / 100F);
      FRB.put((float)(-1.0 / PixelLight.DEPTH_PER_XY)).put((float)(f.d0 / PixelLight.DEPTH_PER_XY)).put(0F).put(0F);
      FRB.put(skip()).put(Config.CAR_OCCUPANT_LIGHT_PCT / 100F).put(Config.CAR_OCCUPANT_OCCLUSION ? 1F : 0F).put(0F);
      FRB.flip();
      GL20.glUniform4fv(loc, FRB);
   }

   private static final float CABIN = Config.CAR_GLASS_CABIN ? 1F : 0F;

   /** A vehicle script's cabin (G): computed once per script. */
   static final class CabinData {
      int n;
      final int[] index = new int[6];
      final float[] seat = new float[18];
      final float[] cab0 = new float[4], cab1 = new float[4], fabric = new float[3];
   }

   private static final java.util.HashMap<VehicleScript, CabinData> CABINS = new java.util.HashMap<>();

   /** The cabin of a vehicle script (G): the body box narrowed by the doors, the seats from the script's inside positions. */
   static CabinData cabinData(VehicleScript script) {
      CabinData cd = CABINS.get(script);
      if (cd != null) {
         return cd;
      }
      cd = new CabinData();
      Vector3f ext = script.getExtents();
      Vector3f com = script.getCenterOfMassOffset();
      Vector3f mo = script.getModel().getOffset();
      float yOff = Config.CAR_GLASS_CABIN_Y_PCT / 100F;
      float cx = -com.x, cy = com.y + yOff, cz = com.z;
      float hx = ext.x * 0.5F - 0.08F, hy = ext.y * 0.5F, hz = ext.z * 0.5F;
      float minSeatY = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
      int seats = Math.min(6, script.getPassengerCount());
      float hip = Config.CAR_GLASS_SEAT_HIP_PCT / 100F;
      for (int i = 0; i < seats; i++) {
         VehicleScript.Passenger p = script.getPassenger(i);
         VehicleScript.Position pos = p == null ? null : p.getPositionById("inside");
         if (pos == null) {
            continue;
         }
         Vector3f o = pos.getOffset();
         float sxp = -(mo.x + o.x), syp = mo.y + o.y + yOff + hip, szp = mo.z + o.z;
         cd.index[cd.n] = i;
         cd.seat[cd.n * 3] = sxp;
         cd.seat[cd.n * 3 + 1] = syp;
         cd.seat[cd.n * 3 + 2] = szp;
         minSeatY = Math.min(minSeatY, syp);
         minZ = Math.min(minZ, szp);
         maxZ = Math.max(maxZ, szp);
         cd.n++;
      }
      int n = cd.n;
      float fwd = Config.CAR_GLASS_SEAT_FWD;
      float floorY = n > 0 ? minSeatY - 0.30F : cy - hy * 0.5F;
      float roofY = cy + hy - 0.06F;
      float belt = n > 0 ? minSeatY + 0.22F : cy;
      float z0 = n > 0 ? minZ - 0.75F : cz - hz * 0.5F, z1 = n > 0 ? maxZ + 0.95F : cz + hz * 0.5F;
      if (fwd < 0F && n > 0) {
         z0 = minZ - 0.95F;
         z1 = maxZ + 0.75F;
      }
      z0 = Math.max(z0, cz - hz);
      z1 = Math.min(z1, cz + hz);
      float dash = fwd > 0F ? (n > 0 ? maxZ + 0.55F : z1 - 0.4F) : (n > 0 ? minZ - 0.55F : z0 + 0.4F);
      cd.cab0[0] = cx - hx;
      cd.cab0[1] = floorY;
      cd.cab0[2] = z0;
      cd.cab0[3] = belt;
      cd.cab1[0] = cx + hx;
      cd.cab1[1] = roofY;
      cd.cab1[2] = z1;
      cd.cab1[3] = dash;
      int hue = Math.floorMod(script.getName().hashCode(), 4);
      float[][] fabric = {{0.035F, 0.035F, 0.038F}, {0.09F, 0.075F, 0.055F}, {0.06F, 0.045F, 0.035F}, {0.05F, 0.055F, 0.065F}};
      System.arraycopy(fabric[hue], 0, cd.fabric, 0, 3);
      CABINS.put(script, cd);
      return cd;
   }


   // ------------------------------------------------------------------------------------------------ glass maps and window triangles

   /**
    * A car skin's glass map (per mask + diffuse texture, R8 at the mask's size): 1-6 the stock window zones 7-12; 7 glass
    * the artist painted outside the window zones (the window colour in an unmarked or body area: the CarLuxury coupe's rear
    * quarter windows); 8 a mirror (the window colour inside a door zone: the side mirrors hang on the doors). Built once per
    * skin: the two textures read back asynchronously (staging buffers + a fence), classified on a worker, uploaded.
    */
   static final class GlassMap {
      int maskId, diffId, w, h, dw, dh, tex;
      int maskBuf, diffBuf;
      long fence;
      volatile byte[] cls; // set by the worker
      boolean uploaded, failedMap;
      String name;
      int extra, mirrors;
   }

   static final byte CLS_EXTRA = 7, CLS_MIRROR = 8;
   static final int CLASS_UNIT = 15;
   private static final java.util.HashMap<Long, GlassMap> GLASS_MAPS = new java.util.HashMap<>();
   private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "pzopt-carglass-maps");
      t.setDaemon(true);
      return t;
   });
   static long mapsBuilt;

   /** Render thread: this skin's glass map with its texture uploaded, or null while it is being built (the stock windows meanwhile). */
   static GlassMap glassMap(VehicleModelInstance vmi) {
      zombie.core.textures.Texture mask = vmi.textureMask, diff = vmi.tex;
      if (mask == null || diff == null || mask.getID() <= 0 || diff.getID() <= 0) {
         return null;
      }
      long key = ((long)mask.getID() << 32) | (diff.getID() & 0xFFFFFFFFL);
      GlassMap m = GLASS_MAPS.get(key);
      if (m == null) {
         m = new GlassMap();
         m.maskId = mask.getID();
         m.diffId = diff.getID();
         m.name = mask.getName() + " + " + diff.getName();
         GLASS_MAPS.put(key, m);
         try {
            int[] wh = new int[2];
            m.maskBuf = readTextureAsync(m.maskId, wh);
            m.w = wh[0];
            m.h = wh[1];
            m.diffBuf = readTextureAsync(m.diffId, wh);
            m.dw = wh[0];
            m.dh = wh[1];
            m.fence = org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
         } catch (Throwable t) {
            m.failedMap = true;
            Log.warn("car glass: glass map readback failed for " + m.name + ": " + t);
         }
         return null;
      }
      if (m.failedMap) {
         return null;
      }
      if (m.uploaded) {
         return m;
      }
      if (m.fence != 0L) {
         int st = org.lwjgl.opengl.GL32.glClientWaitSync(m.fence, 0, 0L);
         if (st != org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED && st != org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED) {
            return null;
         }
         org.lwjgl.opengl.GL32.glDeleteSync(m.fence);
         m.fence = 0L;
         final java.nio.ByteBuffer mk = read(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, m.maskBuf, (long)m.w * m.h * 4L);
         final java.nio.ByteBuffer df = read(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, m.diffBuf, (long)m.dw * m.dh * 4L);
         org.lwjgl.opengl.GL15.glDeleteBuffers(new int[] {m.maskBuf, m.diffBuf});
         final GlassMap gm = m;
         WORKER.execute(() -> {
            try {
               gm.cls = classify(mk, gm.w, gm.h, df, gm.dw, gm.dh, gm);
            } catch (Throwable t) {
               gm.failedMap = true;
               Log.warn("car glass: glass map of " + gm.name + " failed: " + t);
            }
         });
         return null;
      }
      byte[] cls = m.cls;
      if (cls == null) {
         return null;
      }
      java.nio.ByteBuffer b = BufferUtils.createByteBuffer(cls.length);
      b.put(cls).flip();
      m.tex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, m.tex);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, m.w, m.h, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, b);
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      zombie.core.textures.Texture.lastTextureID = -1;
      m.uploaded = true;
      mapsBuilt++;
      Log.info("car glass: glass map " + m.name + " (" + m.w + "x" + m.h + "): " + m.extra + " texels of extra glass, " + m.mirrors + " of mirror glass");
      return m;
   }

   /** A texture's level 0 (RGBA8) into a new pixel-pack buffer; its size into wh. */
   private static int readTextureAsync(int texId, int[] wh) {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
      wh[0] = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
      wh[1] = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
      int buf = org.lwjgl.opengl.GL15.glGenBuffers();
      org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, buf);
      org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, (long)wh[0] * wh[1] * 4L, org.lwjgl.opengl.GL15.GL_STREAM_READ);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
      org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      zombie.core.textures.Texture.lastTextureID = -1;
      return buf;
   }

   /** The stock window zone of a mask texel (1-6 for zones 7-12, 0 none). */
   static int windowClass(int r, int g, int b) {
      int qr = r < 4 ? 0 : (r > 122 && r < 133 ? 1 : 9), qg = g < 4 ? 0 : (g > 122 && g < 133 ? 1 : 9), qb = b < 4 ? 0 : (b > 122 && b < 133 ? 1 : 9);
      if (qr == 9 || qg == 9 || qb == 9) {
         return 0;
      }
      switch (qr * 4 + qg * 2 + qb) {
         case 3: return 1; // (0, .5, .5) zone 7
         case 6: return 2; // (.5, .5, 0) zone 8
         case 5: return 3; // (.5, 0, .5) zone 9
         case 1: return 4; // (0, 0, .5) zone 10
         case 4: return 5; // (.5, 0, 0) zone 11
         case 2: return 6; // (0, .5, 0) zone 12
         default: return 0;
      }
   }

   static boolean windowZone(int r, int g, int b) {
      return windowClass(r, g, b) != 0;
   }

   /** vehicle_common.frag.h's 27 zones (1-27; 0 none / unmarked). */
   private static final int[][] ZONES = {{255, 0, 0}, {0, 255, 0}, {0, 255, 255}, {255, 255, 0}, {255, 0, 255}, {0, 0, 255}, {0, 127, 127}, {127, 127, 0},
      {127, 0, 127}, {0, 0, 127}, {127, 0, 0}, {0, 127, 0}, {0, 192, 192}, {192, 192, 0}, {192, 0, 192}, {0, 0, 192}, {0, 0, 0}, {64, 0, 0}, {192, 0, 0},
      {0, 192, 0}, {0, 64, 0}, {127, 64, 0}, {127, 192, 0}, {192, 192, 192}, {64, 64, 64}, {255, 0, 127}, {0, 255, 127}};

   static int zoneId(int r, int g, int b, int a) {
      if (a == 0) {
         return 0;
      }
      for (int z = 0; z < ZONES.length; z++) {
         if (Math.abs(r - ZONES[z][0]) + Math.abs(g - ZONES[z][1]) + Math.abs(b - ZONES[z][2]) <= 9) {
            return z + 1;
         }
      }
      return 0;
   }

   /** The stock door zones 3-6 (Door RH, RT, LH, LT): (0,1,1) (1,1,0) (1,0,1) (0,0,1). */
   static boolean doorZone(int r, int g, int b) {
      int qr = r < 8 ? 0 : (r > 247 ? 1 : 9), qg = g < 8 ? 0 : (g > 247 ? 1 : 9), qb = b < 8 ? 0 : (b > 247 ? 1 : 9);
      if (qr == 9 || qg == 9 || qb == 9) {
         return false;
      }
      int c = qr * 4 + qg * 2 + qb;
      return c == 3 || c == 6 || c == 5 || c == 1;
   }

   /**
    * Worker: the glass map. Window zones from the mask; the window paint = the diffuse's median under them; extra glass =
    * connected blobs (4-neighbour) of unpainted diffuse texels (alpha > 200) within the paint's spread, outside the window
    * zones, at least 30 texels, 5 x 5 across and half filled (thin trim lines and edges drop out); a blob mostly in a door
    * zone is a mirror.
    */
   static byte[] classify(java.nio.ByteBuffer mk, int w, int h, java.nio.ByteBuffer df, int dw, int dh, GlassMap gm) {
      byte[] cls = new byte[w * h];
      int[] hist = new int[3 * 256];
      int nwin = 0;
      for (int i = 0, n = w * h; i < n; i++) {
         int o = i * 4;
         if ((mk.get(o + 3) & 0xFF) == 0) {
            continue;
         }
         int c = windowClass(mk.get(o) & 0xFF, mk.get(o + 1) & 0xFF, mk.get(o + 2) & 0xFF);
         if (c != 0) {
            cls[i] = (byte)c;
            int d = diffAt(df, dw, dh, i % w, i / w, w, h);
            hist[(d >> 16) & 0xFF]++;
            hist[256 + ((d >> 8) & 0xFF)]++;
            hist[512 + (d & 0xFF)]++;
            nwin++;
         }
      }
      if (nwin < 50 || !Config.CAR_GLASS_EXTRA) {
         return cls;
      }
      int[] med = new int[3], mad = new int[3];
      for (int ch = 0; ch < 3; ch++) {
         med[ch] = percentile(hist, ch * 256, nwin, 0.5);
      }
      int[] hd = new int[3 * 256];
      for (int i = 0, n = w * h; i < n; i++) {
         if (cls[i] != 0) {
            int d = diffAt(df, dw, dh, i % w, i / w, w, h);
            hd[Math.min(255, Math.abs(((d >> 16) & 0xFF) - med[0]))]++;
            hd[256 + Math.min(255, Math.abs(((d >> 8) & 0xFF) - med[1]))]++;
            hd[512 + Math.min(255, Math.abs((d & 0xFF) - med[2]))]++;
         }
      }
      boolean[] cand = new boolean[w * h];
      float[] tol = new float[3];
      for (int ch = 0; ch < 3; ch++) {
         mad[ch] = percentile(hd, ch * 256, nwin, 0.5) + 4;
         tol[ch] = 2.5F * mad[ch] + 10F;
      }
      for (int i = 0, n = w * h; i < n; i++) {
         if (cls[i] != 0) {
            continue;
         }
         int d = diffAt(df, dw, dh, i % w, i / w, w, h);
         if (((d >>> 24) & 0xFF) <= 200) {
            continue;
         }
         cand[i] = Math.abs(((d >> 16) & 0xFF) - med[0]) < tol[0] && Math.abs(((d >> 8) & 0xFF) - med[1]) < tol[1] && Math.abs((d & 0xFF) - med[2]) < tol[2];
      }
      int[] stack = new int[w * h];
      int[] comp = new int[w * h];
      for (int s = 0, n = w * h; s < n; s++) {
         if (!cand[s]) {
            continue;
         }
         int sp = 0, cnt = 0, x0 = w, y0 = h, x1 = -1, y1 = -1;
         int[] zh = new int[28];
         stack[sp++] = s;
         cand[s] = false;
         while (sp > 0) {
            int p = stack[--sp];
            comp[cnt++] = p;
            int x = p % w, y = p / w;
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            y0 = Math.min(y0, y);
            y1 = Math.max(y1, y);
            int o = p * 4;
            zh[zoneId(mk.get(o) & 0xFF, mk.get(o + 1) & 0xFF, mk.get(o + 2) & 0xFF, mk.get(o + 3) & 0xFF)]++;
            if (x > 0 && cand[p - 1]) { cand[p - 1] = false; stack[sp++] = p - 1; }
            if (x < w - 1 && cand[p + 1]) { cand[p + 1] = false; stack[sp++] = p + 1; }
            if (y > 0 && cand[p - w]) { cand[p - w] = false; stack[sp++] = p - w; }
            if (y < h - 1 && cand[p + w]) { cand[p + w] = false; stack[sp++] = p + w; }
         }
         int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
         int minSide = Math.max(5, w / 100);
         if (cnt < Math.max(60, w * h / 4400) || bw < minSide || bh < minSide || cnt < 0.4F * bw * bh) {
            continue;
         }
         int major = 0;
         for (int z = 1; z < zh.length; z++) {
            if (zh[z] > zh[major]) {
               major = z;
            }
         }
         // head / tail (the bumpers, the lamps) and the lamp / stop light / light bar zones 18-25 are not glass; the doors
         // carry the mirrors, and so do the front guards (fenders) on some (a mirror-sized blob there)
         if (major == 1 || major == 2 || major >= 18 && major <= 25) {
            continue;
         }
         int doorGuard = zh[3] + zh[4] + zh[5] + zh[6] + zh[13] + zh[14] + zh[15] + zh[16];
         boolean small = cnt < w * h / 440;
         boolean mirror = major >= 3 && major <= 6 || small && (major >= 13 && major <= 16 || doorGuard * 4 >= cnt);
         byte c = mirror ? CLS_MIRROR : CLS_EXTRA;
         for (int k = 0; k < cnt; k++) {
            cls[comp[k]] = c;
         }
         if (c == CLS_MIRROR) {
            gm.mirrors += cnt;
         } else {
            gm.extra += cnt;
         }
      }
      return cls;
   }

   /** The diffuse texel (ARGB int) under mask texel (x, y): nearest at the diffuse's own size. */
   private static int diffAt(java.nio.ByteBuffer df, int dw, int dh, int x, int y, int w, int h) {
      int dx = Math.min(dw - 1, x * dw / w), dy = Math.min(dh - 1, y * dh / h);
      int o = (dy * dw + dx) * 4;
      return ((df.get(o + 3) & 0xFF) << 24) | ((df.get(o) & 0xFF) << 16) | ((df.get(o + 1) & 0xFF) << 8) | (df.get(o + 2) & 0xFF);
   }

   private static int percentile(int[] hist, int off, int n, double q) {
      int want = (int)(n * q), acc = 0;
      for (int v = 0; v < 256; v++) {
         acc += hist[off + v];
         if (acc > want) {
            return v;
         }
      }
      return 255;
   }

   /**
    * The window triangles of a car mesh for a glass map (index buffer of its own), so the glass pass rasterises the glass
    * only: the mesh's index and vertex buffers read back asynchronously once per (mesh, glass map), a triangle kept when the
    * map under any of its corners, edge midpoints or centre is glass.
    */
   private static final java.util.HashMap<Long, int[]> WINDOW_TRIS = new java.util.HashMap<>(); // key -> {ebo, count}
   private static java.lang.reflect.Field fHandle, fFormat, fElements, fStride, fEbo, fNum, fVbo;
   static long windowMeshes, windowTris, windowAll;

   /** Render thread: the glass index buffer {ebo, count} of this mesh / glass map, null while not ready (no glass draw then). */
   /** Render thread, Model.pzoptDrawGlass: this mesh's glass triangles for the car's skin, or null (the full mesh then). */
   public static int[] glassTriangles(zombie.core.skinnedmodel.model.ModelMesh mesh, VehicleModelInstance vmi) {
      return Config.CAR_GLASS_WINDOW_TRIS && !Config.carGlassWindowTrisFailed && Config.DEV_CAR_GLASS_VIEW < 10 ? windowTriangles(mesh, glassMap(vmi)) : null;
   }

   static int[] windowTriangles(zombie.core.skinnedmodel.model.ModelMesh mesh, GlassMap map) {
      if (mesh == null || mesh.vb == null || map == null || map.cls == null) {
         return null;
      }
      try {
         if (fHandle == null) {
            Class<?> vbo = zombie.core.skinnedmodel.model.VertexBufferObject.class;
            fHandle = vbo.getDeclaredField("handle");
            fFormat = vbo.getDeclaredField("vertexFormat");
            Class<?> hc = zombie.core.skinnedmodel.model.VertexBufferObject.Vbo.class;
            fEbo = hc.getDeclaredField("eboId");
            fNum = hc.getDeclaredField("numElements");
            fVbo = hc.getDeclaredField("vboId");
            Class<?> vf = zombie.core.skinnedmodel.model.VertexBufferObject.VertexFormat.class;
            fElements = vf.getDeclaredField("elements");
            fStride = vf.getDeclaredField("stride");
            for (java.lang.reflect.Field fl : new java.lang.reflect.Field[] {fHandle, fFormat, fEbo, fNum, fVbo, fElements, fStride}) {
               fl.setAccessible(true);
            }
         }
         Object handle = fHandle.get(mesh.vb);
         if (handle == null) {
            return null;
         }
         int ebo = fEbo.getInt(handle), vbo = fVbo.getInt(handle), num = fNum.getInt(handle);
         long key = ((long)ebo << 42) ^ ((long)vbo << 21) ^ (map.tex & 0x1FFFFFL);
         int[] got = WINDOW_TRIS.get(key);
         if (got != null || WINDOW_TRIS.containsKey(key)) {
            return got;
         }
         Object format = fFormat.get(mesh.vb);
         Object[] elements = (Object[])fElements.get(format);
         int stride = fStride.getInt(format);
         int uvOffset = -1;
         for (Object el : elements) {
            zombie.core.skinnedmodel.model.VertexBufferObject.VertexElement e = (zombie.core.skinnedmodel.model.VertexBufferObject.VertexElement)el;
            if (e.type == zombie.core.skinnedmodel.model.VertexBufferObject.VertexType.TextureCoordArray) {
               uvOffset = e.byteOffset;
               break;
            }
         }
         if (uvOffset < 0 || num <= 0 || num % 3 != 0) {
            WINDOW_TRIS.put(key, null);
            return null;
         }
         // asynchronous readback (a synchronous one stalled the render thread for milliseconds per new car model)
         Pending pd = PENDING.get(key);
         if (pd == null) {
            pd = new Pending();
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, vbo);
            pd.vboSize = org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, org.lwjgl.opengl.GL15.GL_BUFFER_SIZE);
            pd.idx = copyBuffer(ebo, (long)num * 4L);
            pd.vtx = copyBuffer(vbo, pd.vboSize);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
            zombie.core.SpriteRenderer.ringBuffer.restoreVbos = true;
            pd.fence = org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            PENDING.put(key, pd);
            return null;
         }
         int st = org.lwjgl.opengl.GL32.glClientWaitSync(pd.fence, 0, 0L);
         if (st != org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED && st != org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED) {
            return null;
         }
         long t0 = System.nanoTime();
         org.lwjgl.opengl.GL32.glDeleteSync(pd.fence);
         PENDING.remove(key);
         java.nio.IntBuffer idx = read(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, pd.idx, (long)num * 4L).asIntBuffer();
         java.nio.ByteBuffer vb = read(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, pd.vtx, pd.vboSize);
         org.lwjgl.opengl.GL15.glDeleteBuffers(new int[] {pd.idx, pd.vtx});
         byte[] cls = map.cls;
         int tw = map.w, th = map.h;
         java.nio.IntBuffer out = BufferUtils.createIntBuffer(num);
         int kept = 0;
         float[][] wq = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0.5F, 0.5F, 0}, {0, 0.5F, 0.5F}, {0.5F, 0, 0.5F}, {0.3333F, 0.3333F, 0.3334F}};
         for (int t = 0; t < num; t += 3) {
            int a = idx.get(t), b = idx.get(t + 1), c = idx.get(t + 2);
            float ua = vb.getFloat(a * stride + uvOffset), va = vb.getFloat(a * stride + uvOffset + 4);
            float ub = vb.getFloat(b * stride + uvOffset), vbv = vb.getFloat(b * stride + uvOffset + 4);
            float uc = vb.getFloat(c * stride + uvOffset), vc = vb.getFloat(c * stride + uvOffset + 4);
            boolean win = false;
            for (float[] q : wq) {
               float u = q[0] * ua + q[1] * ub + q[2] * uc, v = q[0] * va + q[1] * vbv + q[2] * vc;
               u -= (float)Math.floor(u);
               v -= (float)Math.floor(v);
               int x = Math.min(tw - 1, (int)(u * tw)), y = Math.min(th - 1, (int)(v * th));
               if (cls[y * tw + x] != 0) {
                  win = true;
                  break;
               }
            }
            if (win) {
               out.put(a).put(b).put(c);
               kept += 3;
            }
         }
         out.flip();
         int[] res = null;
         if (kept > 0) {
            int buf = org.lwjgl.opengl.GL15.glGenBuffers();
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, buf);
            org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, out, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            res = new int[] {buf, kept};
         }
         zombie.core.SpriteRenderer.ringBuffer.restoreVbos = true;
         WINDOW_TRIS.put(key, res);
         windowMeshes++;
         windowTris += kept / 3;
         windowAll += num / 3;
         Log.info("car glass: glass triangles of mesh " + vbo + "/" + ebo + " (" + map.name + "): " + kept / 3 + " of " + num / 3
               + String.format(java.util.Locale.ROOT, " (read back asynchronously, %.2f ms on the render thread)", (System.nanoTime() - t0) / 1e6));
         return res;
      } catch (Throwable t) {
         Log.warn("car glass: window triangles failed: " + t);
         Config.carGlassWindowTrisFailed = true;
         return null;
      }
   }

   /** A buffer's first bytes copied into a new staging buffer (GPU side, asynchronous). */
   private static int copyBuffer(int src, long size) {
      int dst = org.lwjgl.opengl.GL15.glGenBuffers();
      org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, dst);
      org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, size, org.lwjgl.opengl.GL15.GL_STREAM_READ);
      org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, src);
      org.lwjgl.opengl.GL31.glCopyBufferSubData(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, 0L, 0L, size);
      org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER, 0);
      org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER, 0);
      return dst;
   }

   private static final class Pending {
      int idx, vtx;
      long vboSize, fence;
   }

   private static final java.util.HashMap<Long, Pending> PENDING = new java.util.HashMap<>();

   /** A staging buffer's contents (mapped for reading after its fence passed). */
   private static java.nio.ByteBuffer read(int target, int buf, long size) {
      org.lwjgl.opengl.GL15.glBindBuffer(target, buf);
      java.nio.ByteBuffer m = org.lwjgl.opengl.GL30.glMapBufferRange(target, 0L, size, org.lwjgl.opengl.GL30.GL_MAP_READ_BIT);
      java.nio.ByteBuffer copy = BufferUtils.createByteBuffer((int)size);
      if (m != null) {
         copy.put(m);
         copy.flip();
      }
      org.lwjgl.opengl.GL15.glUnmapBuffer(target);
      org.lwjgl.opengl.GL15.glBindBuffer(target, 0);
      return copy.order(java.nio.ByteOrder.nativeOrder());
   }

   public static String stats() {
      return "car glass: shaders " + patchedFrag + " frag / " + patchedVert + " vert, frames " + frames + ", glass draws " + draws + String.format(java.util.Locale.ROOT, " (uniforms %.1f us each on the render thread)", draws == 0 ? 0.0 : drawNs / 1000.0 / draws) + ", probe frames " + probeFrames
            + " (" + probeCars + " probes), programs " + GLASS.keySet() + ", small cars left stock " + lodSkipped + ", glass maps " + mapsBuilt + ", window meshes " + windowMeshes + " (" + windowTris + " of " + windowAll + " triangles)" + (failed ? ", failed" : "");
   }
}
