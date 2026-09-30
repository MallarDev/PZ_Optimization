package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.joml.Matrix4f;
import zombie.worldMap.UIWorldMap;
import zombie.worldMap.WorldMapRenderer;

/**
 * mapStreetMemo / mapStreetCache (2026-09-30, the UI snappiness pass): the world map spent ~80 % of its 6 ms UI frame
 * laying out street-name labels, recomputing per street and per character what only changes with the view: the
 * street's length in UI pixels (every point projected, several times per street per frame), its translated name
 * (Translator.getText once per character drawn) and the draw order of the visible streets (indexOf in a sort
 * comparator). This keeps a per-map-UI view stamp that changes whenever anything the world-to-UI projection reads
 * changes (display zoom, centre, model-view-projection matrix, UI height, data / images loaded), so the per-street
 * memos in WorldMapStreet stay exact.
 */
public final class MapStreets {
   private static final class View {
      float zoom;
      float cx;
      float cy;
      double h;
      boolean data;
      boolean images;
      final float[] mvp = new float[16];
      long stamp;
   }

   private static final IdentityHashMap<UIWorldMap, View> views = new IdentityHashMap<>();
   private static final float[] tmp = new float[16];
   private static long nextStamp = 1L;

   private MapStreets() {
   }

   public static boolean memo() {
      return Config.MAP_STREET_MEMO && Overrides.enabled();
   }

   public static boolean cache() {
      return Config.MAP_STREET_CACHE && Overrides.enabled();
   }

   private static int hits;
   private static int misses;

   private static int checks;
   private static int checkMismatches;

   /** devMapStreetCheck: a reused layout compared with a fresh one. */
   public static void checked(boolean same, int kept, int fresh) {
      checks++;
      if (!same) {
         checkMismatches++;
         if (checkMismatches <= 20) {
            Log.warn("mapStreetCache check: the kept layout differs (" + kept + " vs " + fresh + " characters), " + checkMismatches + " of " + checks);
         }
      }
      if (checks % 300 == 0) {
         Log.info("mapStreetCache check: " + checks + " reuses checked, " + checkMismatches + " differed");
      }
   }

   public static void cacheHit() {
      hits++;
   }

   public static void cacheMiss() {
      misses++;
      if ((hits + misses) % 600 == 0) {
         Log.info("mapStreetCache: " + hits + " layouts reused, " + misses + " laid out");
      }
   }

   /**
    * mapStreetCache: what the street-label layout reads besides the view: every renderer option's value (street names,
    * highlight, isometric, symbols ...), the map style's layers and the translation language.
    */
   public static long labelKey(UIWorldMap ui, WorldMapRenderer r) {
      long h = 17L;
      for (int i = 0; i < r.getOptionCount(); i++) {
         zombie.config.ConfigOption o = r.getOptionByIndex(i);
         h = 31L * h + o.getName().hashCode();
         h = 31L * h + o.getValueAsString().hashCode();
      }
      zombie.worldMap.styles.WorldMapStyle style = ui.getAPI().getStyle();
      if (style != null) {
         h = 31L * h + System.identityHashCode(style);
         h = 31L * h + style.getLayerCount();
         for (int i = 0; i < style.getLayerCount(); i++) {
            h = 31L * h + System.identityHashCode(style.getLayerByIndex(i));
         }
      }
      h = 31L * h + System.identityHashCode(zombie.core.Translator.getLanguage());
      return h;
   }

   /** A number that changes whenever the projection of this map UI changes (game thread; the map UIs are few). */
   public static long viewStamp(UIWorldMap ui) {
      WorldMapRenderer r = ui.getAPI().getRenderer();
      View v = views.get(ui);
      if (v == null) {
         if (views.size() > 8) {
            views.clear();
         }
         v = new View();
         views.put(ui, v);
      }
      float zoom = r.getDisplayZoomF();
      float cx = r.getCenterWorldX();
      float cy = r.getCenterWorldY();
      double h = ui.getHeight();
      boolean data = ui.getWorldMap().hasData();
      boolean images = ui.getWorldMap().hasImages();
      Matrix4f m = r.getModelViewProjectionMatrix();
      m.get(tmp);
      boolean same = v.stamp != 0L && zoom == v.zoom && cx == v.cx && cy == v.cy && h == v.h && data == v.data && images == v.images;
      for (int i = 0; same && i < 16; i++) {
         same = Float.floatToRawIntBits(tmp[i]) == Float.floatToRawIntBits(v.mvp[i]);
      }
      if (!same) {
         v.zoom = zoom;
         v.cx = cx;
         v.cy = cy;
         v.h = h;
         v.data = data;
         v.images = images;
         System.arraycopy(tmp, 0, v.mvp, 0, 16);
         v.stamp = nextStamp++;
      }
      return v.stamp;
   }

   private static final IdentityHashMap<Object, Integer> index = new IdentityHashMap<>();

   /**
    * Sorts {@code list} exactly as {@code list.sort((a, b) -> all.indexOf(a) - all.indexOf(b))} would (same comparator
    * values, same stable sort), with each element's first index in {@code all} found in one pass.
    */
   public static <T> void sortByIndex(ArrayList<T> list, List<T> all) {
      index.clear();
      for (int i = 0; i < list.size(); i++) {
         index.put(list.get(i), -1);
      }
      int found = 0;
      for (int i = 0; i < all.size() && found < index.size(); i++) {
         Object o = all.get(i);
         Integer cur = index.get(o);
         if (cur != null && cur == -1) {
            index.put(o, i);
            found++;
         }
      }
      list.sort((a, b) -> index.get(a) - index.get(b));
      index.clear();
   }
}
