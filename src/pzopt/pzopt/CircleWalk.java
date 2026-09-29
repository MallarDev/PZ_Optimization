package pzopt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import zombie.characters.IsoPlayer;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;

/**
 * Harness scene {@code explore=circle director=jev} (2026-09-29, the maintainer's report: the shelves of the Riverside
 * gas station flicker while the player walks in circles): Jev walks the player in circles on foot, through the movement
 * keys (Showcase's key set), never teleported, first round the square the save loaded on, then round the free squares
 * beside the shelves of the nearest building (the aisles: free room squares next to a square holding a container), one
 * spot after another. Per spot Jev decides the laps (clockwise, then counter-clockwise, {@code circle_laps} each) and when
 * to walk on to the next spot (a grid path, doors opened on the way); done after the last spot.
 *
 * <p>The state goes to {@code Zomboid/pzopt-explore-state.json} every 0.3 s ({@code "scene": "circle"}),
 * harness/explore-director.py answers with {@code <seq> <action>} in {@code pzopt-explore-cmd.txt}: circle_cw,
 * circle_ccw, next_spot, hold, done. Flags: {@code circle_radius} (1.5 tiles), {@code circle_laps} (1),
 * {@code circle_spots} (spots beside the shelves, 4), {@code circle_building_radius} (tiles to the building, 40).
 * Every spot change is logged with its epoch ms ({@code harness: circle: spot}) so a devCapture can be cut per spot.
 */
final class CircleWalk {
   private CircleWalk() {
   }

   static final String[] ACTIONS = {"circle_cw", "circle_ccw", "next_spot", "hold", "done"};

   private static boolean finished;
   private static float radius, radiusIn, laps;
   private static boolean[] blocked2 = new boolean[0];
   private static float stuckX, stuckY;
   private static long stuckSinceNs;
   private static int spot, commands, commandSeq = -1, replans, stuckMarks;
   private static String command = "hold";
   private static long startNs, lastStateNs, lastCmdCheckNs, lastPlanNs, lastProgressNs, lastLogNs, arrivedNs;
   private static java.io.File stateFile, cmdFile;
   private static float progressX, progressY, lastAngle;
   private static boolean angleValid;
   private static String building = "none";

   /** Spot centres (square x, y), the level, and per spot the radians turned each way and its containers. */
   private static final ArrayList<int[]> spots = new ArrayList<>();
   private static int level;
   private static float[] turnedCw = new float[0], turnedCcw = new float[0];
   private static int[] containers = new int[0];
   private static final Set<Long> blocked = new HashSet<>();
   private static int[] pathX = new int[0], pathY = new int[0];
   private static int pathIdx, pathLen;

   static boolean finished() {
      return finished;
   }

   static void worldReady(IsoPlayer p) {
      radius = Float.parseFloat(HarnessFlags.get("circle_radius", "1.5").trim());
      radiusIn = Float.parseFloat(HarnessFlags.get("circle_radius_in", "1.0").trim());
      laps = Float.parseFloat(HarnessFlags.get("circle_laps", "1").trim());
      int max = Integer.parseInt(HarnessFlags.get("circle_spots", "4").trim());
      float reach = Float.parseFloat(HarnessFlags.get("circle_building_radius", "40").trim());
      java.io.File z = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(z, "pzopt-explore-state.json");
      cmdFile = new java.io.File(z, "pzopt-explore-cmd.txt");
      cmdFile.delete();
      stateFile.delete(); // a previous run's state would be answered before this run writes its own
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      level = (int)p.getZ();
      spots.add(new int[] {(int)p.getX(), (int)p.getY(), 0});
      // the nearest building with shelf aisles (the one nearest may be a gas pump canopy: rooms named "empty", no shelves)
      ArrayList<BuildingDef> near = new ArrayList<>();
      for (BuildingDef b : IsoWorld.instance.getMetaGrid().getBuildings()) {
         if (distance(p, b) < reach) near.add(b);
      }
      near.sort((a, c) -> Float.compare(distance(p, a), distance(p, c)));
      for (BuildingDef b : near) {
         findShelfSpots(p, b, max);
         if (spots.size() > 1) {
            building = b.getRooms().isEmpty() || b.getRooms().get(0).name == null ? "building" : b.getRooms().get(0).name;
            break;
         }
      }
      turnedCw = new float[spots.size()];
      turnedCcw = new float[spots.size()];
      containers = new int[spots.size()];
      blocked2 = new boolean[spots.size()];
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < spots.size(); i++) {
         int[] s = spots.get(i);
         containers[i] = s[2];
         sb.append(' ').append(s[0]).append(',').append(s[1]).append('(').append(s[2]).append(')');
      }
      Log.info(String.format(Locale.ROOT, "harness: explore=circle director jev: radius %.1f, %.0f lap(s) each way, building %s, %d spots:%s",
            radius, laps, building, spots.size(), sb));
   }

   static void routeStart(IsoPlayer p) {
      startNs = System.nanoTime();
      lastProgressNs = startNs;
      spotEvent(p);
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (finished) return;
      if (nowNs - lastCmdCheckNs >= 100_000_000L) {
         lastCmdCheckNs = nowNs;
         readCommand(nowNs);
      }
      Showcase.releaseKeys();
      int[] s = spots.get(spot);
      float cx = s[0] + 0.5F, cy = s[1] + 0.5F;
      float r = spot == 0 ? radius : radiusIn; // the aisles between the shelves are narrow
      switch (command) {
         case "circle_cw", "circle_ccw" -> {
            float sign = command.equals("circle_cw") ? 1F : -1F;
            float a = (float)Math.atan2(p.getY() - cy, p.getX() - cx);
            if (angleValid) {
               float da = a - lastAngle;
               da = (float)(((da + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI);
               // only the turn made on the circle counts (walking out to it from the centre swings the angle)
               if (Math.hypot(p.getX() - cx, p.getY() - cy) > r * 0.6F && da * sign > 0) {
                  if (sign > 0) turnedCw[spot] += da; else turnedCcw[spot] -= da;
               }
            }
            lastAngle = a;
            angleValid = true;
            float t = a + sign * (float)Math.PI / 4F; // + angle = clockwise on screen (+y is south)
            Showcase.moveKeys(cx + r * (float)Math.cos(t) - p.getX(), cy + r * (float)Math.sin(t) - p.getY());
            // no progress for 3 s (furniture in the way): the spot is blocked, the director moves on
            if (Math.hypot(p.getX() - stuckX, p.getY() - stuckY) > 0.3) {
               stuckX = p.getX();
               stuckY = p.getY();
               stuckSinceNs = nowNs;
            } else if (stuckSinceNs != 0L && nowNs - stuckSinceNs > 3_000_000_000L && !blocked2[spot]) {
               blocked2[spot] = true;
               Log.info("harness: circle: spot " + spot + " blocked at " + p.getX() + "," + p.getY());
            }
         }
         case "next_spot" -> walk(p, s[0], s[1], nowNs);
         case "done" -> {
            finished = true;
            float total = 0;
            for (int i = 0; i < spots.size(); i++) total += (turnedCw[i] + turnedCcw[i]) / (float)(2 * Math.PI);
            Log.info(String.format(Locale.ROOT, "harness: circle: done at +%.1f s, spot %d of %d, %.1f laps in all, %d commands, %d re-plans, %d stuck marks",
                  (nowNs - startNs) / 1e9, spot + 1, spots.size(), total, commands, replans, stuckMarks));
         }
         default -> { // hold
         }
      }
      if (nowNs - lastLogNs >= 2_000_000_000L) {
         lastLogNs = nowNs;
         Log.info(String.format(Locale.ROOT, "harness: circle: t=%.0fs %s spot %d pos=%.2f,%.2f dist=%.2f laps cw %.2f ccw %.2f",
               (nowNs - startNs) / 1e9, command, spot, p.getX(), p.getY(), Math.hypot(p.getX() - cx, p.getY() - cy),
               turnedCw[spot] / (2 * Math.PI), turnedCcw[spot] / (2 * Math.PI)));
      }
      if (nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, nowNs);
      }
   }

   private static void spotEvent(IsoPlayer p) {
      int[] s = spots.get(spot);
      Log.info(String.format(Locale.ROOT, "harness: circle: spot %d at %d,%d epoch_ms=%d (+%.1f s)", spot, s[0], s[1],
            System.currentTimeMillis(), (System.nanoTime() - startNs) / 1e9));
   }

   // ---- the spots: free aisle squares beside the shelves ----

   private static float distance(IsoPlayer p, BuildingDef b) {
      float dx = Math.max(Math.max(b.getX() - p.getX(), p.getX() - b.getX2()), 0F);
      float dy = Math.max(Math.max(b.getY() - p.getY(), p.getY() - b.getY2()), 0F);
      return (float)Math.hypot(dx, dy);
   }

   /** Free room squares of the building next to a square with a container; greedy, spots at least 3 tiles apart. */
   private static void findShelfSpots(IsoPlayer p, BuildingDef b, int max) {
      IsoCell cell = IsoWorld.instance.getCell();
      ArrayList<int[]> cand = new ArrayList<>();
      for (int y = b.getY(); y <= b.getY2(); y++) {
         for (int x = b.getX(); x <= b.getX2(); x++) {
            IsoGridSquare sq = cell.getGridSquare(x, y, level);
            if (sq == null || sq.getRoom() == null || !sq.isFree(false)) continue;
            // a walkable ring: at least 7 of the 8 squares around free and not behind a wall (a corner by a door pinned
            // the character for the rest of the run)
            int free = 0;
            for (int dy = -1; dy <= 1; dy++) {
               for (int dx = -1; dx <= 1; dx++) {
                  IsoGridSquare o = dx == 0 && dy == 0 ? null : cell.getGridSquare(x + dx, y + dy, level);
                  if (o != null && o.getRoom() != null && o.isFree(false) && !sq.isWallTo(o) && !sq.isWindowBlockedTo(o)) free++;
               }
            }
            if (free < 7) continue;
            // the shelves (containers) within two squares, not behind a wall of the spot itself
            int n = 0;
            for (int dy = -2; dy <= 2; dy++) {
               for (int dx = -2; dx <= 2; dx++) {
                  IsoGridSquare o = cell.getGridSquare(x + dx, y + dy, level);
                  if (o == null || o.getRoom() != sq.getRoom()) continue;
                  for (int i = 0; i < o.getObjects().size(); i++) {
                     if (o.getObjects().get(i).getContainer() != null) n++;
                  }
               }
            }
            if (n > 0) cand.add(new int[] {x, y, n});
         }
      }
      cand.sort((a, c) -> c[2] - a[2]); // most shelves around first
      ArrayList<int[]> picked = new ArrayList<>();
      for (int[] c : cand) {
         if (picked.size() >= max) break;
         boolean far = true;
         for (int[] q : picked) far &= Math.hypot(q[0] - c[0], q[1] - c[1]) >= 3;
         far &= Math.hypot(spots.get(0)[0] - c[0], spots.get(0)[1] - c[1]) >= 3;
         if (far) picked.add(c);
      }
      // walking order: nearest next, from the start square
      int[] at = spots.get(0);
      while (!picked.isEmpty()) {
         int bi = 0;
         for (int i = 1; i < picked.size(); i++) {
            if (Math.hypot(picked.get(i)[0] - at[0], picked.get(i)[1] - at[1]) < Math.hypot(picked.get(bi)[0] - at[0], picked.get(bi)[1] - at[1])) bi = i;
         }
         at = picked.remove(bi);
         spots.add(at);
      }
   }

   // ---- walking to a spot: a grid path over the loaded squares of the level ----

   private static void walk(IsoPlayer p, int gx, int gy, long nowNs) {
      IsoGridSquare cur = p.getCurrentSquare();
      if (cur == null) return;
      if (Math.hypot(gx + 0.5F - p.getX(), gy + 0.5F - p.getY()) < 0.4F) {
         if (arrivedNs == 0L) arrivedNs = nowNs;
         pathLen = 0;
         return;
      }
      boolean stuck = pathLen > 0 && nowNs - lastProgressNs > 1_500_000_000L;
      if (stuck) {
         int bx = pathX[Math.min(pathIdx, pathLen - 1)], by = pathY[Math.min(pathIdx, pathLen - 1)];
         if (bx != gx || by != gy) blocked.add(Explore.pack(bx, by));
         stuckMarks++;
         Log.info("harness: circle: stuck at " + cur.x + "," + cur.y + ", square " + bx + "," + by + " marked blocked");
      }
      if (pathLen == 0 || stuck || pathIdx >= pathLen || nowNs - lastPlanNs > 2_000_000_000L) {
         lastPlanNs = nowNs;
         lastProgressNs = nowNs;
         progressX = p.getX();
         progressY = p.getY();
         plan(cur, gx, gy);
         replans++;
         if (pathLen == 0) {
            Showcase.moveKeys(gx + 0.5F - p.getX(), gy + 0.5F - p.getY()); // no path over the loaded squares: head straight for it
            return;
         }
      }
      if (Math.hypot(p.getX() - progressX, p.getY() - progressY) > 0.4) {
         progressX = p.getX();
         progressY = p.getY();
         lastProgressNs = nowNs;
      }
      while (pathIdx < pathLen && Math.hypot(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY()) < 0.35F) {
         pathIdx++;
      }
      if (pathIdx >= pathLen) return;
      Explore.openDoorBetween(p, cur, cur.getCell().getGridSquare(pathX[pathIdx], pathY[pathIdx], cur.z));
      Showcase.moveKeys(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY());
   }

   private static void plan(IsoGridSquare start, int gx, int gy) {
      IsoCell cell = start.getCell();
      int r = 60, w = 2 * r + 1, ox = start.x - r, oy = start.y - r, z = start.z;
      int[] prev = new int[w * w];
      java.util.Arrays.fill(prev, -2);
      ArrayDeque<Integer> q = new ArrayDeque<>();
      int s = (start.y - oy) * w + (start.x - ox);
      prev[s] = -1;
      q.add(s);
      int found = -1;
      int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      while (!q.isEmpty()) {
         int i = q.poll();
         int x = ox + i % w, y = oy + i / w;
         if (x == gx && y == gy) {
            found = i;
            break;
         }
         IsoGridSquare a = cell.getGridSquare(x, y, z);
         if (a == null) continue;
         for (int[] n : nb) {
            int nx = x + n[0], ny = y + n[1];
            if (nx < ox || ny < oy || nx >= ox + w || ny >= oy + w) continue;
            int j = (ny - oy) * w + (nx - ox);
            if (prev[j] != -2 || blocked.contains(Explore.pack(nx, ny))) continue;
            if (!Explore.passable(a, cell.getGridSquare(nx, ny, z))) continue;
            prev[j] = i;
            q.add(j);
         }
      }
      pathLen = 0;
      if (found < 0) return;
      int n = 0;
      for (int i = found; i >= 0; i = prev[i]) n++;
      if (pathX.length < n) {
         pathX = new int[n];
         pathY = new int[n];
      }
      int k = n;
      for (int i = found; i >= 0; i = prev[i]) {
         k--;
         pathX[k] = ox + i % w;
         pathY[k] = oy + i / w;
      }
      pathLen = n;
      pathIdx = Math.min(1, n);
   }

   // ---- the director (Jev) ----

   private static void readCommand(long nowNs) {
      try {
         if (cmdFile == null || !cmdFile.isFile()) return;
         String[] parts = java.nio.file.Files.readString(cmdFile.toPath()).trim().split("\\s+");
         if (parts.length < 2) return;
         int seq = Integer.parseInt(parts[0]);
         if (seq == commandSeq) return;
         commandSeq = seq;
         String c = parts[1];
         if (!java.util.Arrays.asList(ACTIONS).contains(c) || c.equals(command)) return;
         Log.info("harness: circle director: " + command + " -> " + c + " (#" + seq + ")");
         commands++;
         if (c.equals("next_spot")) {
            if (spot + 1 >= spots.size()) return; // no spot left: stay on the last one
            spot++;
            arrivedNs = 0L;
            pathLen = 0;
            spotEvent(null);
         }
         angleValid = false;
         stuckSinceNs = 0L;
         command = c;
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   private static void writeState(IsoPlayer p, long nowNs) {
      int[] s = spots.get(spot);
      float dist = (float)Math.hypot(s[0] + 0.5F - p.getX(), s[1] + 0.5F - p.getY());
      boolean circling = command.startsWith("circle_");
      boolean atSpot = circling ? dist < radius + 1F : arrivedNs != 0L || dist < 0.6F;
      String json = String.format(Locale.ROOT,
            "{\"t\":%d,\"scene\":\"circle\",\"seconds_since_start\":%.1f,\"current_action\":\"%s\",\"laps_wanted_each_way\":%.0f,"
                  + "\"player\":{\"at_spot\":%b,\"distance_to_spot_tiles\":%.1f,\"moving\":%b},"
                  + "\"spot\":{\"number\":%d,\"spots_total\":%d,\"spots_left_after_this\":%d,\"shelves_next_to_it\":%d,"
                  + "\"laps_clockwise_here\":%.2f,\"laps_counterclockwise_here\":%.2f,\"clockwise_laps_done\":%b,"
                  + "\"counterclockwise_laps_done\":%b,\"is_last_spot\":%b,\"blocked_here\":%b}}",
            System.currentTimeMillis(), (nowNs - startNs) / 1e9, command, laps, atSpot, dist, p.isPlayerMoving(),
            spot + 1, spots.size(), spots.size() - spot - 1, containers[spot],
            turnedCw[spot] / (2 * Math.PI), turnedCcw[spot] / (2 * Math.PI), turnedCw[spot] >= laps * 2 * Math.PI,
            turnedCcw[spot] >= laps * 2 * Math.PI, spot + 1 >= spots.size(), blocked2[spot]);
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: circle: state write failed: " + e);
      }
   }
}
