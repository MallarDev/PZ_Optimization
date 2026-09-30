package pzopt;

import java.util.ArrayList;
import java.util.Random;

/**
 * pzopt.MapStreets.sortByIndex (mapStreetMemo): the visible-street sort gives exactly the order of stock's
 * {@code list.sort((a, b) -> all.indexOf(a) - all.indexOf(b))}: first index in the street list, elements missing from
 * it (indexOf -1) first, duplicates of the list kept in their stable order.
 */
public class MapStreetsSortTest {
   public static void main(String[] args) {
      Random rng = new Random(7);
      for (int round = 0; round < 300; round++) {
         int n = rng.nextInt(400);
         ArrayList<Object> all = new ArrayList<>();
         for (int i = 0; i < n; i++) {
            all.add(new Object());
         }
         // a street can appear twice in the combined list: indexOf finds the first
         for (int i = 0; i < n / 20; i++) {
            all.add(rng.nextInt(all.size() + 1), all.get(rng.nextInt(Math.max(1, all.size()))));
         }
         ArrayList<Object> visible = new ArrayList<>();
         int m = rng.nextInt(120);
         for (int i = 0; i < m; i++) {
            if (rng.nextInt(10) == 0 || all.isEmpty()) {
               visible.add(new Object()); // not in the list (intersection streets of another data set)
            } else {
               Object o = all.get(rng.nextInt(all.size()));
               if (!visible.contains(o)) {
                  visible.add(o);
               }
            }
         }
         ArrayList<Object> expected = new ArrayList<>(visible);
         expected.sort((a, b) -> all.indexOf(a) - all.indexOf(b));
         ArrayList<Object> got = new ArrayList<>(visible);
         MapStreets.sortByIndex(got, all);
         Check.check(expected.equals(got), "round " + round + ": same order as the indexOf comparator (" + visible.size() + " of " + all.size() + ")");
      }
      System.out.println("MapStreetsSortTest ok");
   }
}
