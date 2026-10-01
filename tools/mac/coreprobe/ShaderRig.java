import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import pzopt.CoreGlsl;

/**
 * macGlCore offline rig (2026-10-01): every .vert / .frag under a shader directory, preprocessed like the game's
 * ShaderUnit (#include "x" -> x.h), translated by pzopt.CoreGlsl and compiled + linked on a 4.1 core context.
 * Usage: java -XstartOnFirstThread -cp .:classes:projectzomboid.jar ShaderRig <media/shaders> [extra dirs...]
 * Prints one line per failure (compile log + translated source with line numbers) and a summary.
 */
public final class ShaderRig {
   public static void main(String[] args) throws Exception {
      if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit");
      GLFW.glfwDefaultWindowHints();
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, 0);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
      GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
      GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, 1);
      long w = GLFW.glfwCreateWindow(64, 64, "rig", 0L, 0L);
      GLFW.glfwMakeContextCurrent(w);
      GL.createCapabilities();
      Set<String> exts = new HashSet<>();
      int n = GL11C.glGetInteger(GL30C.GL_NUM_EXTENSIONS);
      for (int i = 0; i < n; i++) exts.add(GL30C.glGetStringi(GL11C.GL_EXTENSIONS, i));
      boolean verbose = System.getProperty("rig.verbose") != null;
      int ok = 0, bad = 0, linkOk = 0, linkBad = 0;
      for (String dirName : args) {
         Path dir = Path.of(dirName);
         List<Path> files = new ArrayList<>();
         try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".vert") || p.toString().endsWith(".frag")).sorted().forEach(files::add);
         }
         for (Path f : files) {
            boolean vert = f.toString().endsWith(".vert");
            String src = preprocess(dir, f, new HashSet<>());
            CoreGlsl.Result r = CoreGlsl.translate(src, vert, exts, true);
            int sh = compile(vert, r.source);
            if (sh == 0) {
               bad++;
               continue;
            }
            int status = GL20C.glGetShaderi(sh, GL20C.GL_COMPILE_STATUS);
            if (status == 0) {
               bad++;
               System.out.println("FAIL " + f.getFileName() + ": " + GL20C.glGetShaderInfoLog(sh).trim().replace('\n', ' '));
               if (verbose) dump(r.source);
            } else {
               ok++;
            }
            GL20C.glDeleteShader(sh);
            if (vert) {
               String base = f.getFileName().toString().replace(".vert", "");
               Path frag = dir.resolve(base + ".frag");
               if (Files.exists(frag)) {
                  CoreGlsl.Result fr = CoreGlsl.translate(preprocess(dir, frag, new HashSet<>()), false, exts, true);
                  int vs = compile(true, r.source), fs = compile(false, fr.source);
                  int p = GL20C.glCreateProgram();
                  GL20C.glAttachShader(p, vs);
                  GL20C.glAttachShader(p, fs);
                  for (String[] a : CoreGlsl.ATTRIBS) GL20C.glBindAttribLocation(p, Integer.parseInt(a[1]), a[0]);
                  GL30C.glBindFragDataLocation(p, 0, "pz_FragColor");
                  GL30C.glBindFragDataLocation(p, 0, "pz_FragData");
                  GL20C.glLinkProgram(p);
                  if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0) {
                     linkBad++;
                     if (GL20C.glGetShaderi(vs, GL20C.GL_COMPILE_STATUS) != 0 && GL20C.glGetShaderi(fs, GL20C.GL_COMPILE_STATUS) != 0) {
                        System.out.println("LINKFAIL " + base + ": " + GL20C.glGetProgramInfoLog(p).trim().replace('\n', ' '));
                     }
                  } else {
                     linkOk++;
                  }
                  GL20C.glDeleteProgram(p);
                  GL20C.glDeleteShader(vs);
                  GL20C.glDeleteShader(fs);
               }
            }
         }
      }
      System.out.println("RIG compiled ok=" + ok + " failed=" + bad + " linked ok=" + linkOk + " failed=" + linkBad);
      GLFW.glfwDestroyWindow(w);
      GLFW.glfwTerminate();
   }

   static int compile(boolean vert, String src) {
      int sh = GL20C.glCreateShader(vert ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER);
      GL20C.glShaderSource(sh, src);
      GL20C.glCompileShader(sh);
      return sh;
   }

   static void dump(String src) {
      String[] lines = src.split("\n", -1);
      for (int i = 0; i < lines.length; i++) System.out.printf("   %4d  %s%n", i + 1, lines[i]);
   }

   static String preprocess(Path dir, Path f, Set<String> seen) throws IOException {
      StringBuilder sb = new StringBuilder();
      for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
         String t = line.trim();
         if (t.startsWith("#include ")) {
            String inc = t.substring(9).trim().replace("\"", "").toLowerCase();
            if (seen.add(inc)) {
               Path h = dir.resolve(inc + ".h");
               if (!Files.exists(h)) h = dir.resolve(inc + ".glsl");
               if (Files.exists(h)) sb.append(preprocess(dir, h, seen)).append('\n');
            }
            continue;
         }
         sb.append(line).append('\n');
      }
      // the game drops a second #version from includes when sources are combined: keep the first one only
      String s = sb.toString();
      int first = s.indexOf("#version");
      if (first >= 0) {
         int next = s.indexOf("#version", first + 8);
         while (next >= 0) {
            int eol = s.indexOf('\n', next);
            s = s.substring(0, next) + "//" + s.substring(next, eol < 0 ? s.length() : eol) + (eol < 0 ? "" : s.substring(eol));
            next = s.indexOf("#version", next + 10);
         }
         if (first > 0) s = s.substring(first);
      }
      return s;
   }
}
