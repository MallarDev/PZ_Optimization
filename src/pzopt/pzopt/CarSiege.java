package pzopt;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.vehicles.AttackVehicleState;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/**
 * Harness rig {@code siege=N} (2026-10-01): the "zombies crowd my car instead of attacking it" report. {@code siege_at} s
 * (default 3) after the first tick a {@code siege_car} (Base.CarNormal) is placed on the player's square and the player
 * seated as its driver, visible, in god mode; N hostile zombies spawn on free outdoor squares {@code siege_min}..
 * {@code siege_max} (3..8) tiles away and are told once that they saw the player. The route does not move the player
 * ({@link #active()}). A line every second: how many zombies target the player, stand adjacent to the car, are in
 * AttackVehicleState, their action states, how many read {@code battackvehicle} true, and the windows' / doors' summed
 * condition; {@code siege=} in pzopt-bench.out. A/B: stock is {@code --prop enabled=false}.
 */
final class CarSiege {
   private static int zombies = -1; // -1 = flags not read yet, 0 = off
   private static long atNs, lastLogNs, spawnNs;
   private static BaseVehicle car;
   private static final ArrayList<IsoZombie> spawned = new ArrayList<>();
   private static int condStart = -1, condLast, attackSeconds, maxAttacking;

   private CarSiege() {
   }

   /** The route must not teleport the player out of the car. */
   static boolean active() {
      if (zombies < 0) {
         return Integer.parseInt(HarnessFlags.get("siege", "0").trim()) > 0;
      }
      return zombies > 0;
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (zombies == 0) {
         return;
      }
      if (zombies < 0) {
         zombies = Integer.parseInt(HarnessFlags.get("siege", "0").trim());
         atNs = nowNs + (long)(Float.parseFloat(HarnessFlags.get("siege_at", "3")) * 1e9);
         return;
      }
      if (car == null) {
         if (nowNs >= atNs) {
            start(p, nowNs);
         }
         return;
      }
      if (nowNs - lastLogNs >= 1_000_000_000L) {
         lastLogNs = nowNs;
         log(p, nowNs);
      }
   }

   private static void start(IsoPlayer p, long nowNs) {
      try {
         p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
         p.setInvisible(false, true);
         zombie.SystemDisabler.zombiesDontAttack = false;
         IsoGridSquare sq = p.getCurrentSquare();
         String script = HarnessFlags.get("siege_car", "Base.CarNormal");
         BaseVehicle v = LuaManager.GlobalObject.addVehicleDebug(script, IsoDirections.E, 0, sq);
         if (v == null || v.getSquare() == null) {
            Log.warn("harness: siege: could not place " + script + " at " + sq.x + "," + sq.y);
            zombies = 0;
            return;
         }
         v.repair();
         p.getInventory().AddItem(v.createVehicleKey());
         if (!v.enter(0, p)) {
            Log.warn("harness: siege: could not seat the player in " + script);
            zombies = 0;
            return;
         }
         car = v;
         IsoCell cell = IsoWorld.instance.currentCell;
         int px = sq.x, py = sq.y;
         int rMin = Integer.parseInt(HarnessFlags.get("siege_min", "3").trim()), rMax = Integer.parseInt(HarnessFlags.get("siege_max", "8").trim());
         ArrayList<IsoGridSquare> ground = new ArrayList<>();
         for (int y = py - rMax; y <= py + rMax; y++) {
            for (int x = px - rMax; x <= px + rMax; x++) {
               int d2 = (x - px) * (x - px) + (y - py) * (y - py);
               IsoGridSquare g = cell.getGridSquare(x, y, 0);
               if (d2 >= rMin * rMin && d2 <= rMax * rMax && g != null && g.isOutside() && g.isFree(false) && !g.isWaterSquare()) {
                  ground.add(g);
               }
            }
         }
         java.util.Collections.shuffle(ground, new java.util.Random(42));
         for (int i = 0; i < ground.size() && spawned.size() < zombies; i++) {
            IsoGridSquare g = ground.get(i);
            ArrayList<IsoZombie> list = LuaManager.GlobalObject.addZombiesInOutfit(g.x, g.y, 0, 1, null, 50);
            if (list != null) {
               spawned.addAll(list);
            }
         }
         for (IsoZombie z : spawned) {
            z.spotted(p, true);
         }
         condStart = condLast = windowCondition(v);
         spawnNs = lastLogNs = nowNs;
         Log.info("harness: siege: " + script + " at " + px + "," + py + ", player seated, " + spawned.size() + " zombies spawned " + rMin + ".." + rMax
               + " tiles away (" + ground.size() + " squares), windows+doors condition " + condStart);
      } catch (Throwable t) {
         Log.warn("harness: siege: setup failed: " + t);
         zombies = 0;
      }
   }

   private static void log(IsoPlayer p, long nowNs) {
      int alive = 0, targeting = 0, adjacent = 0, attacking = 0, wantAttack = 0;
      TreeMap<String, Integer> states = new TreeMap<>();
      for (IsoZombie z : spawned) {
         if (z.isDead() || z.getCurrentSquare() == null) {
            continue;
         }
         alive++;
         if (z.getTarget() == p) targeting++;
         if (car.isCharacterAdjacentTo(z)) adjacent++;
         if (z.getCurrentState() == AttackVehicleState.instance()) attacking++;
         try {
            if (z.getVariableBoolean("battackvehicle")) wantAttack++;
         } catch (Throwable t) {
            // a guard on the wrong thread: not expected on the game thread
         }
         states.merge(z.getActionStateName(), 1, Integer::sum);
      }
      int cond = windowCondition(car);
      if (attacking > 0) attackSeconds++;
      maxAttacking = Math.max(maxAttacking, attacking);
      StringBuilder st = new StringBuilder();
      for (Map.Entry<String, Integer> e : states.entrySet()) {
         st.append(st.length() == 0 ? "" : ",").append(e.getKey()).append(':').append(e.getValue());
      }
      Log.info(String.format(java.util.Locale.ROOT, "harness: siege t=%.0f alive=%d target=%d adjacent=%d battackvehicle=%d attackvehicle=%d cond=%d (-%d/s) states=%s",
            (nowNs - spawnNs) / 1e9, alive, targeting, adjacent, wantAttack, attacking, cond, condLast - cond, st));
      condLast = cond;
   }

   /** Summed condition of every window and door part: a zombie hitting the car takes it down. */
   private static int windowCondition(BaseVehicle v) {
      int sum = 0;
      for (int i = 0; i < v.getPartCount(); i++) {
         VehiclePart part = v.getPartByIndex(i);
         if (part != null && (part.getWindow() != null || part.getDoor() != null)) {
            sum += part.getCondition();
         }
      }
      return sum;
   }

   /** Line for pzopt-bench.out ("" when the rig is off). */
   static String summary() {
      if (car == null) {
         return "";
      }
      return "\nsiege=zombies:" + spawned.size() + " attack_seconds:" + attackSeconds + " max_attacking:" + maxAttacking + " condition:" + condStart + "->"
            + windowCondition(car);
   }
}
