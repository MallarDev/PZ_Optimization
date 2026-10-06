package pzopt;

import java.util.List;

/** -cachedir= as MainScreenState.main reads it, from a Windows command line split as the C runtime splits it. */
public class CacheDirArgTest {
   public static void main(String[] args) {
      Check.check(UserOptions.cacheDirArg(List.of("-debug")) == null, "no -cachedir");
      Check.check("E:/Zomboid".equals(UserOptions.cacheDirArg(List.of("-cachedir=E:/Zomboid "))), "trimmed");
      Check.check("D:\\b".equals(UserOptions.cacheDirArg(List.of("-cachedir=C:\\a", "-cachedir=D:\\b"))), "last one wins");

      List<String> a = UserOptions.splitWindowsCommandLine(
            "\"C:\\Program Files (x86)\\Steam\\steamapps\\common\\ProjectZomboid\\ProjectZomboid64.exe\" -cachedir=E:/Zomboid  -debug");
      Check.check(a.equals(List.of("C:\\Program Files (x86)\\Steam\\steamapps\\common\\ProjectZomboid\\ProjectZomboid64.exe",
            "-cachedir=E:/Zomboid", "-debug")), "plain: " + a);
      a = UserOptions.splitWindowsCommandLine("ProjectZomboid64.exe \"-cachedir=E:\\My Games\\Zomboid\" -x");
      Check.check(a.equals(List.of("ProjectZomboid64.exe", "-cachedir=E:\\My Games\\Zomboid", "-x")), "quoted with spaces: " + a);
      a = UserOptions.splitWindowsCommandLine("pz.exe -cachedir=\"E:\\My Games\\Zomboid\"");
      Check.check(a.equals(List.of("pz.exe", "-cachedir=E:\\My Games\\Zomboid")), "quote mid-argument: " + a);
      a = UserOptions.splitWindowsCommandLine("pz.exe a\\\\\\\"b c\\\\\"d e\" \\\\server\\share\\ \"\"\"x\"");
      Check.check(a.equals(List.of("pz.exe", "a\\\"b", "c\\d e", "\\\\server\\share\\", "\"x")), "backslash rules: " + a);
      a = UserOptions.splitWindowsCommandLine("\"pz.exe\"");
      Check.check(a.equals(List.of("pz.exe")), "program only: " + a);

      // this JVM (no -cachedir=) keeps the default folder
      Check.check(UserOptions.zomboidDir().getName().equals("Zomboid"), "default folder: " + UserOptions.zomboidDir());
      System.out.println("CacheDirArgTest OK");
   }
}
