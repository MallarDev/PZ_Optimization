package pzopt;

import java.io.File;

/**
 * The options tabs' "Export settings" file (pzopt.UserOptions.exportText / exportWrite / exportRead): the header lines
 * are comments above the body, the file lands beside options.ini (scripts/test.sh points that at build/tests), and the
 * import reads back what was written.
 */
public class UserOptionsExportTest {
   static int failures;

   static void check(boolean ok, String what) {
      if (!ok) {
         failures++;
         System.err.println("FAIL: " + what);
      }
   }

   public static void main(String[] args) {
      String body = "# Optimizations\nenabled=false\ntreesInChunkTexture=false\n# Enhancements: all at the defaults\n# Profiler\n";
      String text = UserOptions.exportText(body);
      check(text.endsWith(body), "the body follows the header unchanged");
      String header = text.substring(0, text.length() - body.length());
      for (String line : header.split("\n")) {
         check(line.startsWith("# "), "header line is a comment: " + line);
      }
      File f = UserOptions.exportFile();
      check(f.getParentFile().equals(UserOptions.file().getAbsoluteFile().getParentFile()), "export beside options.ini: " + f);
      f.delete();
      check(UserOptions.exportRead().isEmpty(), "no file reads as empty");
      String path = UserOptions.exportWrite(text);
      check(path.equals(f.getPath()), "write returns the path: " + path);
      check(UserOptions.exportRead().equals(text), "read returns what was written");
      f.delete();
      if (failures > 0) {
         throw new AssertionError(failures + " check(s) failed");
      }
      System.out.println("UserOptionsExportTest ok");
   }
}
