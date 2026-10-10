package pzopt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.core.opengl.RenderThread;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelScript;

/**
 * Shaders that a Model has already created, keyed by shader name and the static flag, so the next Model with the
 * same shader takes it from here instead of blocking on a round trip to the render thread.
 *
 * Stock Model.CreateShader always posts to the render thread and waits, even when ShaderManager already has the
 * shader: the caller (usually the loader thread, e.g. AnimalDefinitions loading 73 animal models that all use
 * animalEffect) then waits for the render thread's next queue drain, which is one render step. That is ~1 ms on the
 * desktop but ~220 ms on a laptop whose loading-screen render step is slow, where the 73 round trips were 16.5 s
 * of the load (GitHub issue #1). Only the first model per shader still pays the round trip.
 *
 * ShaderManager never removes shaders and a shader that is reloaded (debug file watcher) recompiles in place, so a
 * cached reference stays valid for the life of the process.
 */
public final class ModelShaders {
   private static final ConcurrentHashMap<String, Shader> cache = new ConcurrentHashMap<>();
   private static final AtomicLong hits = new AtomicLong();
   private static final AtomicLong roundTrips = new AtomicLong();
   private static final AtomicLong waitedNs = new AtomicLong();

   private ModelShaders() {
   }

   public static boolean enabled() {
      return Config.SHADER_CACHE && Overrides.enabled();
   }

   private static String key(String name, boolean isStatic) {
      return isStatic ? name + "&static" : name;
   }

   /** The shader a previous model created for this name, or null if this is the first (or the cache is off). */
   public static Shader get(String name, boolean isStatic) {
      if (name == null) {
         return null;
      }
      Shader s = cache.get(key(name, isStatic));
      if (s != null) {
         hits.incrementAndGet();
      }
      return s;
   }

   /** Called after the render-thread round trip that created (or found) the shader. */
   public static void created(String name, boolean isStatic, Shader shader, long ns) {
      roundTrips.incrementAndGet();
      waitedNs.addAndGet(ns);
      if (name != null && shader != null) {
         cache.putIfAbsent(key(name, isStatic), shader);
      }
   }

   // ---------------------------------------------------------------------------------------------- warm-up

   private static ArrayList<String> warmKeys; // name or name&static, from the model scripts at the main menu
   private static boolean warmQueued;

   /**
    * Main menu (first game state change, the scripts are loaded at boot and no loader thread runs): the distinct
    * shader / static pairs the model scripts name. A model's shader is otherwise built the first time a model with it
    * loads: the first car of a session (a burnt-car story at a chunk hand-off) linked the patched vehicle program on the
    * render thread mid-play while the game thread waited, 428 ms (docs/findings-frame-spikes-2026-10-09.md).
    */
   public static void collectWarmup() {
      if (warmKeys != null || !Config.SHADER_WARMUP || !enabled() || ModelManager.noOpenGL) {
         return;
      }
      LinkedHashSet<String> keys = new LinkedHashSet<>();
      try {
         for (ModelScript ms : ScriptManager.instance.getAllModelScripts()) {
            keys.add(key(ms.getShaderName(), ms.isStatic));
         }
      } catch (Throwable t) {
         Log.warn("model shaders: warm-up list failed (" + t + ")");
      }
      warmKeys = new ArrayList<>(keys);
   }

   /**
    * A world load starts (GameLoadingState): every listed shader not created yet is built on the render thread, one queued
    * job each, so the loader's own render-thread round trips interleave with them and the load does not wait for the set.
    */
   public static void warmup() {
      if (warmQueued || warmKeys == null || warmKeys.isEmpty()) {
         return;
      }
      warmQueued = true;
      long[] totalNs = new long[1];
      int[] left = {warmKeys.size()};
      for (String k : warmKeys) {
         boolean isStatic = k.endsWith("&static");
         String name = isStatic ? k.substring(0, k.length() - 7) : k;
         RenderThread.queueInvokeOnRenderContext(() -> {
            long t0 = System.nanoTime();
            try {
               Shader s = ShaderManager.instance.getOrCreateShader(name, isStatic, false);
               if (s != null) {
                  cache.putIfAbsent(k, s);
               }
            } catch (Throwable t) {
               Log.warn("model shaders: warm-up of " + k + " failed (" + t + ")");
            }
            totalNs[0] += System.nanoTime() - t0;
            if (--left[0] == 0) {
               Log.info(String.format(java.util.Locale.ROOT, "model shaders: warmed %d during the world load, %.3f s on the render thread", warmKeys.size(), totalNs[0] / 1e9));
            }
         });
      }
   }

   public static String summary() {
      return String.format("model shaders: %d cached, %d render-thread round trips (%.3f s waited), %d served from the cache",
            cache.size(), roundTrips.get(), waitedNs.get() / 1e9, hits.get());
   }
}
