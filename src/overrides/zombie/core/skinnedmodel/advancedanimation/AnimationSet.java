package zombie.core.skinnedmodel.advancedanimation;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugType;

public final class AnimationSet {
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.skinnedmodel.advancedanimation.AnimationSet");
   }

   protected static final HashMap<String, AnimationSet> setMap = new HashMap<>();
   public final HashMap<String, AnimState> states = new HashMap<>();
   public String name = "";

   public static AnimationSet GetAnimationSet(String name, boolean reload) {
      synchronized (setMap) { // pzopt: the boot preload thread (docs/plan-instant-load.md L1) and the game must not race on the map
         return pzoptGetAnimationSet(name, reload);
      }
   }

   private static AnimationSet pzoptGetAnimationSet(String name, boolean reload) {
      AnimationSet s = setMap.get(name);
      if (s != null && !reload) {
         return s;
      }

      s = new AnimationSet();
      s.Load(name);
      setMap.put(name, s);
      return s;
   }

   public static void Reset() {
      synchronized (setMap) { // pzopt: see GetAnimationSet
         pzoptReset();
      }
   }

   private static void pzoptReset() {
      for (AnimationSet animSet : setMap.values()) {
         animSet.clear();
      }

      setMap.clear();
   }

   public AnimState GetState(String name) {
      AnimState n = this.states.get(name.toLowerCase(Locale.ENGLISH));
      if (n != null) {
         return n;
      }

      DebugType.Animation.trace("AnimState not found: %s", new Object[]{name});
      return new AnimState();
   }

   public boolean containsState(String name) {
      return this.states.containsKey(name.toLowerCase(Locale.ENGLISH));
   }

   public boolean Load(String name) {
      DebugType.Animation.debugln("Loading AnimSet: %s", new Object[]{name});
      this.name = name;
      String[] listOfDirs = ZomboidFileSystem.instance.resolveAllDirectories("media/AnimSets/" + name, dir -> true, false);

      for (String stateDir : listOfDirs) {
         String stateName = new File(stateDir).getName();
         AnimState newState = AnimState.Parse(stateName, stateDir);
         newState.set = this;
         this.states.put(stateName.toLowerCase(Locale.ENGLISH), newState);
      }

      return true;
   }

   private void clear() {
      for (AnimState state : this.states.values()) {
         state.clear();
      }

      this.states.clear();
   }
}
