package zombie.iso.worldgen;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import se.krka.kahlua.vm.KahluaUtil;
import zombie.UsedFromLua;
import zombie.ZomboidFileSystem;
import zombie.Lua.LuaManager;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.debug.StackTraceContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

@UsedFromLua
public class WorldGenUtils {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.iso.worldgen.WorldGenUtils"); // pzopt
   }

   @UsedFromLua
   public static final WorldGenUtils INSTANCE = new WorldGenUtils();
   private static final int OFFSET = 2;
   private ArrayList<String> files = new ArrayList<>();

   private WorldGenUtils() {
   }

   public String generateSeed() {
      Random rnd = new Random();
      String res = "";

      for (int i = 0; i < 16; i++) {
         int rndInt = rnd.nextInt(52);
         if (rndInt < 26) {
            res = res + (char)(rndInt + 65);
         } else {
            res = res + (char)(rndInt + 97 - 26);
         }
      }

      return res;
   }

   public void getFiles(String basePath) {
      this.files = new ArrayList<>();
      File mainFile = ZomboidFileSystem.instance.getMediaFile(basePath);
      File base = ZomboidFileSystem.instance.base.canonicalFile;

      try (Stream<Path> stream = Files.walk(Paths.get(mainFile.getPath()))) {
         stream.filter(x$0 -> Files.isRegularFile(x$0))
            .filter(f -> f.toString().endsWith("lua"))
            .forEach(f -> this.files.add(base.toPath().relativize(f).toString().replaceAll("\\\\", "/")));
      } catch (IOException e) {
         DebugType.General.printException(e, "", LogSeverity.Error);
      }
   }

   public int getFilesNum() {
      return this.files.size();
   }

   public String getFile(int i) {
      return this.files.get(i);
   }

   public String displayTable(String tableName) {
      StringBuilder buffer = new StringBuilder();
      Object table = LuaManager.env.rawget(tableName);
      this.displayElement(tableName, table, buffer, 0);
      return buffer.toString();
   }

   public String displayTable(KahluaTable table) {
      StringBuilder buffer = new StringBuilder();
      this.displayElement("", table, buffer, 0);
      return buffer.toString();
   }

   private void displayElement(String name, Object value, StringBuilder buffer, int offset) {
      String type = KahluaUtil.type(value);
      String space = " ".repeat(Math.max(0, offset));
      switch (type) {
         case "nil":
         case "string":
         case "number":
         case "boolean":
            buffer.append(String.format("%s%s = %s\n", space, name, value));
            break;
         case "function":
         case "coroutine":
         case "userdata":
            buffer.append(String.format("%s%s = %s\n", space, name, value));
            break;
         case "table":
            buffer.append(String.format("%s%s = {\n", space, name));
            KahluaTable table = (KahluaTable)value;
            KahluaTableIterator tableIter = table.iterator();

            while (tableIter.advance()) {
               String k = tableIter.getKey().toString();
               Object v = tableIter.getValue();
               this.displayElement(k, v, buffer, offset + 2);
            }

            buffer.append(String.format("%s}\n", space));
            break;
         default:
            buffer.append(String.format("!!!! %s%s = %s\n", space, name, value));
      }
   }

   // pzopt: worldgenPatternCache (2026-10-05, Louisville 120 fps pass). canPlace turned each placement glob into a regex and
   // matched it with String.matches, which compiles a new Pattern per call: ~5 % of the game's allocation, on the world
   // streamer, feeding the young collections that stall the game thread. The compiled Pattern is kept per glob.
   private static final java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern> PZOPT_PATTERNS = new java.util.concurrent.ConcurrentHashMap<>(); // pzopt

   private static java.util.regex.Pattern pzoptCompile(String glob) { // pzopt: the stock rewrite of the glob, compiled
      String floorCheck = glob.startsWith("!") ? glob.substring(1) : glob; // pzopt
      floorCheck = "^" + floorCheck; // pzopt
      floorCheck = floorCheck.replace(".", "\\."); // pzopt
      floorCheck = floorCheck.replace("*", ".*"); // pzopt
      floorCheck = floorCheck.replace("?", ".?"); // pzopt
      return java.util.regex.Pattern.compile(floorCheck); // pzopt
   } // pzopt

   public boolean canPlace(List<String> placement, String floorName) {
      boolean toBePlaced = false;

      for (String floorCheck : placement) {
         if (pzopt.Config.WORLDGEN_PATTERN_CACHE && pzopt.Overrides.enabled()) { // pzopt: worldgenPatternCache, the same regex compiled once
            java.util.regex.Pattern pzoptPattern = PZOPT_PATTERNS.computeIfAbsent(floorCheck, WorldGenUtils::pzoptCompile); // pzopt
            if (pzoptPattern.matcher(floorName).matches()) { // pzopt: what String.matches does with a fresh Pattern
               toBePlaced = !floorCheck.startsWith("!"); // pzopt
            } // pzopt
            continue; // pzopt
         } // pzopt
         boolean check = true;
         if (floorCheck.startsWith("!")) {
            check = false;
            floorCheck = floorCheck.substring(1);
         }

         floorCheck = "^" + floorCheck;
         floorCheck = floorCheck.replace(".", "\\.");
         floorCheck = floorCheck.replace("*", ".*");
         floorCheck = floorCheck.replace("?", ".?");
         if (floorName.matches(floorCheck)) {
            toBePlaced = check;
         }
      }

      return toBePlaced;
   }

   public @Nullable IsoObject doesFloorExit(IsoChunk chunk, int tileX, int tileY, int z) {
      IsoGridSquare square = chunk.getGridSquare(tileX, tileY, z);
      return square == null ? null : square.getFloor();
   }

   public @Nullable IsoObject doesFloorExit(IsoCell cell, int tileX, int tileY, int z) {
      IsoGridSquare square = cell.getGridSquare(tileX, tileY, z);
      return square == null ? null : square.getFloor();
   }

   public String methodName(StackTraceElement trace) {
      return String.format("%s.%s:%s", trace.getClassName(), trace.getMethodName(), trace.getLineNumber());
   }

   public String methodsCall(String header, int depth, String... args) {
      StackTraceElement[] trace = StackTraceContainer.getStackTrace();
      StringBuilder buffer = new StringBuilder();
      buffer.append(header);
      if (args.length != 0) {
         buffer.append(" [");

         for (int i = 0; i < args.length; i++) {
            buffer.append(args[i]);
            buffer.append(", ");
         }

         buffer.append("]");
      }

      for (int i = 0; i <= depth; i++) {
         buffer.append(" <- " + this.methodName(trace[i + 2]));
      }

      return buffer.toString();
   }

   public void showTimers(String clazzStr) {
      try {
         Class<?> clazz = Class.forName(clazzStr);
         Field[] fields = clazz.getDeclaredFields();

         for (Field field : fields) {
            field.setAccessible(true);
            if (field.getType().equals(WorldGenTiming.class)) {
               WorldGenTiming timing = (WorldGenTiming)field.get(null);
               System.out
                  .println(
                     field.getName()
                        + " = "
                        + (float)timing.meanDuration() / 1000.0F / 1000.0F
                        + " ms ["
                        + timing.times()
                        + "] ["
                        + timing.minTime()
                        + " "
                        + timing.maxTime()
                        + "]"
                  );
            }
         }
      } catch (ClassNotFoundException e) {
         System.out.println("Class not found: " + clazzStr);
      } catch (IllegalAccessException e) {
         throw new RuntimeException(e);
      }
   }

   public void showTimersTotal(String clazzStr) {
      try {
         Class<?> clazz = Class.forName(clazzStr);
         Field[] fields = clazz.getDeclaredFields();

         for (Field field : fields) {
            field.setAccessible(true);
            if (field.getType().equals(WorldGenTiming.class)) {
               WorldGenTiming timing = (WorldGenTiming)field.get(null);
               System.out
                  .println(
                     field.getName()
                        + " = "
                        + (float)timing.totalDuration() / 1000.0F / 1000.0F
                        + " ms ["
                        + timing.times()
                        + "] ["
                        + timing.minTime()
                        + " "
                        + timing.maxTime()
                        + "]"
                  );
            }
         }
      } catch (ClassNotFoundException e) {
         System.out.println("Class not found: " + clazzStr);
      } catch (IllegalAccessException e) {
         throw new RuntimeException(e);
      }
   }

   public void resetTimers(String clazzStr) {
      try {
         Class<?> clazz = Class.forName(clazzStr);
         Field[] fields = clazz.getDeclaredFields();

         for (Field field : fields) {
            field.setAccessible(true);
            if (field.getType().equals(WorldGenTiming.class)) {
               ((WorldGenTiming)field.get(null)).reset();
            }
         }
      } catch (ClassNotFoundException e) {
         System.out.println("Class not found: " + clazzStr);
      } catch (IllegalAccessException e) {
         throw new RuntimeException(e);
      }
   }

   public void getTimerKept(String clazzStr, String fieldName) {
      try {
         Class<?> clazz = Class.forName(clazzStr);
         Field[] fields = clazz.getDeclaredFields();

         for (Field field : fields) {
            field.setAccessible(true);
            if (field.getType().equals(WorldGenTiming.class) && field.getName().equals(fieldName)) {
               List<Long> kept = ((WorldGenTiming)field.get(null)).getKept();
               StringBuilder s = new StringBuilder();

               for (Long time : kept) {
                  s.append(String.format("%d%n", time));
               }

               System.out.println(s);
            }
         }
      } catch (ClassNotFoundException e) {
         System.out.println("Class not found: " + clazzStr);
      } catch (IllegalAccessException e) {
         throw new RuntimeException(e);
      }
   }

   public int getCornerOfGeneration(int b) {
      return Math.floorDiv(b, 2) * 2;
   }
}
