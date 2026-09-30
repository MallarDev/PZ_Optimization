package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The player's own Config choices, made in the "Optimizations" tab of the options screen
 * (media/lua/client/pzopt/pzopt_optimizations_options.lua) and kept in
 * {@code Zomboid/pzopt/options.ini}. Read by {@link Config} at class init, below
 * {@code -Dpzopt.<key>} and the install dir's {@code pzopt.properties}: the harness writes that
 * file per run, and a run's flags must not depend on what was clicked in the menu.
 *
 * Every key takes effect on the next launch, except the Profiler tab's (the overlay and its
 * game-thread profiler, {@link Config#isLive}): {@link #set} re-reads those at once and the
 * overlay picks them up from the next frame. The tab shows the values in force
 * ({@link Config#value}) and the stock "restart required" dialog when a change to a next-launch
 * key differs from them. A key that is absent from the file uses the default, so "Default" in the tab
 * removes the key rather than writing the default's value: defaults may change per build and
 * per machine (worker counts).
 *
 * The file is located like {@code ZomboidFileSystem.getCacheDir()} does (deployment.user.cachedir
 * or user.home, plus /Zomboid) without touching that class, because Config initializes inside
 * {@code GameWindow}'s static initializer, before the game's file system is set up.
 */
public final class UserOptions {
   static final String FILE_NAME = "options.ini";
   private static final Properties live = read(file());

   private UserOptions() {
   }

   static File file() {
      String override = System.getProperty("pzopt.userOptionsFile");
      if (override != null && !override.isEmpty()) {
         return new File(override);
      }
      String root = System.getProperty("deployment.user.cachedir");
      if (root == null || System.getProperty("os.name", "").startsWith("Win")) {
         root = System.getProperty("user.home");
      }
      return new File(root + File.separator + "Zomboid", "pzopt" + File.separator + FILE_NAME);
   }

   /** The values as they were at boot; {@link Config} reads through this object once. */
   static Properties load() {
      return live;
   }

   static Properties read(File f) {
      Properties p = new Properties();
      if (f.isFile()) {
         try (InputStream in = new FileInputStream(f)) {
            p.load(in);
         } catch (Exception e) {
            Log.warn("could not read " + f.getAbsolutePath() + ": " + e);
         }
      }
      return p;
   }

   /** The saved value of a key, or null when the key is absent (default in force). */
   public static synchronized String get(String key) {
      return live.getProperty(key);
   }

   /** Stores a value (null or empty removes the key) and rewrites the file at once; a live key (Config.isLive) applies now. */
   public static synchronized void set(String key, String value) {
      if (key == null || key.isEmpty()) {
         return;
      }
      String old = live.getProperty(key);
      if (value == null || value.trim().isEmpty()) {
         if (old == null) {
            return;
         }
         live.remove(key);
      } else {
         value = value.trim();
         if (value.equals(old)) {
            return;
         }
         live.setProperty(key, value);
      }
      // a tab master switch: every key it gates whose value in force moved is applied as if set on its own
      java.util.List<String> gated = Config.gatedBy(key);
      String[] before = null;
      if (gated != null) {
         before = new String[gated.size()];
         for (int i = 0; i < before.length; i++) {
            before[i] = Config.value(gated.get(i));
         }
      }
      boolean now = Config.reloadLive(key);
      if (now && gated != null) {
         boolean overlay = false;
         for (int i = 0; i < before.length; i++) {
            String k = gated.get(i);
            if (Config.isLive(k) && !java.util.Objects.equals(before[i], Config.value(k))) {
               if (Enhancements.owns(k)) {
                  Enhancements.apply(k);
               } else {
                  overlay = true;
               }
            }
         }
         if (overlay) {
            Overlay.reconfigure();
         }
      } else if (now && Enhancements.owns(key)) {
         Enhancements.apply(key);
      } else if (now) {
         Overlay.reconfigure();
      }
      File f = file();
      try {
         write(f, live);
         Log.info("options: " + key + "=" + (value == null || value.isEmpty() ? "(default)" : value) + " saved to " + f.getPath()
               + (now ? " (applied now)" : " (applies on the next launch)"));
      } catch (IOException e) {
         Log.warn("options: could not write " + f.getAbsolutePath() + ": " + e);
      }
   }

   /**
    * The tabs' "Export settings" copy, beside options.ini. The Lua builds the {@code key=value} lines (only the settings
    * that differ from the build's defaults, grouped per tab); the same text goes to the clipboard.
    */
   static final String EXPORT_NAME = "settings-export.ini";

   static File exportFile() {
      return new File(file().getAbsoluteFile().getParentFile(), EXPORT_NAME);
   }

   /** The export's full text: a comment header (date, build) above {@code body}; every header line is a comment to the import. */
   public static String exportText(String body) {
      String when = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ');
      String commit = BuildInfo.get("commit");
      return "# PZ Optimization settings (Options > Optimizations, Enhancements, Profiler), exported " + when
            + (commit != null && !commit.isEmpty() ? ", build " + commit : "") + "\n"
            + "# Import: Options > any of those tabs > Import settings... Settings not listed go back to the build's defaults.\n"
            + (body == null ? "" : body);
   }

   /** Writes the export file; its path, or "" when it could not be written (logged). */
   public static String exportWrite(String text) {
      File f = exportFile();
      try {
         File dir = f.getParentFile();
         if (dir != null) {
            dir.mkdirs();
         }
         java.nio.file.Files.writeString(f.toPath(), text == null ? "" : text);
         Log.info("options: settings exported to " + f.getPath());
         return f.getPath();
      } catch (IOException e) {
         Log.warn("options: could not write " + f.getAbsolutePath() + ": " + e);
         return "";
      }
   }

   /** The last export's text, or "" when there is none. */
   public static String exportRead() {
      File f = exportFile();
      if (!f.isFile()) {
         return "";
      }
      try {
         return java.nio.file.Files.readString(f.toPath());
      } catch (IOException e) {
         Log.warn("options: could not read " + f.getAbsolutePath() + ": " + e);
         return "";
      }
   }

   /** One {@code key=value} line per key, sorted, so the file diffs cleanly. */
   static void write(File f, Properties p) throws IOException {
      File dir = f.getAbsoluteFile().getParentFile();
      if (dir != null) {
         dir.mkdirs();
      }
      TreeMap<String, String> sorted = new TreeMap<>();
      for (String k : p.stringPropertyNames()) {
         sorted.put(k, p.getProperty(k));
      }
      try (Writer w = new FileWriter(f)) {
         w.write("# pzopt options chosen in Options > Optimizations; keys as in pzopt.properties (see pzopt.Config).\n");
         w.write("# Absent keys use the build's defaults. -Dpzopt.<key> and the install dir's pzopt.properties win over this file.\n");
         for (var e : sorted.entrySet()) {
            w.write(e.getKey() + "=" + e.getValue() + "\n");
         }
      }
   }
}
