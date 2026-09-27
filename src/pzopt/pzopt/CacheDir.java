package pzopt;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import zombie.ZomboidFileSystem;

/**
 * The on-disk caches under <cache dir>/pzopt/ (anims, packs, texcache), each stamped with the pzopt build that
 * filled it in <dir>/build.txt. A directory stamped with another build (an update, a reinstall of another commit)
 * is moved aside to <name>.stale-<n> and deleted on a daemon thread, so the boot never waits on the delete and the
 * cache fills again from that boot's stock work. Stale directories a quit left behind are swept on the next boot.
 */
public final class CacheDir {
   /** The pzopt build a cache belongs to: game revision + commit, + build time for a dirty or git-less build. */
   public static final String BUILD_KEY = buildKey();
   private static final HashMap<String, File> opened = new HashMap<>();

   private CacheDir() {
   }

   private static String buildKey() {
      String commit = BuildInfo.get("commit");
      String key = BuildInfo.targetRevision() + "|" + (commit == null ? "unknown" : commit);
      if (commit == null || commit.equals("unknown") || commit.endsWith("-dirty")) {
         key += "|" + BuildInfo.get("built");
      }
      return key;
   }

   /** <cache dir>/pzopt/<name>, emptied first if another build filled it. */
   public static synchronized File open(String name) {
      File d = opened.get(name);
      if (d == null) {
         d = open(new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt" + File.separator + name));
         opened.put(name, d);
      }
      return d;
   }

   private static File open(File d) {
      String name = d.getName();
      File root = d.getParentFile();
      File stamp = new File(d, "build.txt");
      String old = null;
      try {
         old = Files.readString(stamp.toPath()).trim();
      } catch (Exception ignored) {
      }
      ArrayList<File> leftovers = new ArrayList<>();
      if (!BUILD_KEY.equals(old)) {
         if (d.isDirectory()) {
            File stale = new File(root, name + ".stale-" + System.nanoTime());
            if (d.renameTo(stale)) {
               Log.info(name + " cache: build " + old + " -> " + BUILD_KEY + ", re-caching");
            } else {
               // the old entries can never match (the build is in their key); delete the ones present now
               File[] files = d.listFiles();
               if (files != null) {
                  for (File f : files) {
                     leftovers.add(f);
                  }
               }
               Log.warn(name + " cache: could not move " + d + " aside, deleting its " + leftovers.size() + " files in place");
            }
         }
         d.mkdirs();
         try {
            Files.writeString(stamp.toPath(), BUILD_KEY + "\n");
         } catch (Exception e) {
            Log.warn(name + " cache stamp " + stamp + ": " + e);
         }
      }
      File[] stale = root.listFiles((dir, n) -> n.startsWith(name + ".stale-"));
      if ((stale != null && stale.length > 0) || !leftovers.isEmpty()) {
         Thread t = new Thread(() -> {
            for (File f : leftovers) {
               f.delete();
            }
            if (stale != null) {
               for (File s : stale) {
                  deleteTree(s);
               }
            }
         }, "pzopt-cache-sweep-" + name);
         t.setDaemon(true);
         t.start();
      }
      return d;
   }

   private static void deleteTree(File f) {
      File[] children = f.listFiles();
      if (children != null) {
         for (File c : children) {
            deleteTree(c);
         }
      }
      f.delete();
   }
}
