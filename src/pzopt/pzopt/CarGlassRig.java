package pzopt;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/**
 * Harness rig {@code car_rig=N} (2026-10-01, car glass): {@code car_rig_at} s (default 2) after the first tick, N cars of
 * different models are parked around the player on a ring of radius {@code car_rig_r} squares (default 7), headings
 * stepping 45 degrees, so one frame shows windows facing every way. {@code car_rig_seat=true} seats the player in the
 * first car (an occupied driver's seat); {@code car_rig_broken=true} removes the second car's windshield and front-left
 * window (open frames). {@code car_rig_models} overrides the model list (comma-separated script names).
 */
final class CarGlassRig {
   private static int cars = -1;
   private static long atNs;
   private static boolean done;
   static final String MODELS = "Base.CarNormal,Base.SportsCar,Base.PickUpTruck,Base.Van,Base.CarLuxury,Base.ModernCar,Base.CarStationWagon,Base.SmallCar,"
         + "Base.OffRoad,Base.PickUpVan,Base.CarTaxi,Base.StepVan";

   private CarGlassRig() {
   }

   static boolean active() {
      if (cars < 0) {
         cars = Integer.parseInt(HarnessFlags.get("car_rig", "0").trim());
      }
      return cars > 0;
   }

   static void tick(IsoPlayer p, long nowNs) {
      if (!active() || done) {
         return;
      }
      if (atNs == 0L) {
         atNs = nowNs + (long)(Float.parseFloat(HarnessFlags.get("car_rig_at", "2")) * 1e9);
         return;
      }
      if (nowNs < atNs) {
         return;
      }
      done = true;
      try {
         spawn(p);
      } catch (Throwable t) {
         Log.warn("harness: car rig failed: " + t);
      }
   }

   /** A 3x5 block of loaded, outdoor, walkable squares with no vehicle (a car body fits however it turns). */
   private static boolean clear(IsoCell cell, int x, int y, int z) {
      for (int dy = -2; dy <= 2; dy++) {
         for (int dx = -2; dx <= 2; dx++) {
            IsoGridSquare q = cell.getGridSquare(x + dx, y + dy, z);
            if (q == null || q.chunk == null || !q.isOutside() || q.getFloor() == null || q.isSolid() || q.isSolidTrans() || q.getVehicleContainer() != null
                  || q.getBuilding() != null) {
               return false;
            }
         }
      }
      return true;
   }

   private static void spawn(IsoPlayer p) {
      String[] models = HarnessFlags.get("car_rig_models", MODELS).split(",");
      float r = Float.parseFloat(HarnessFlags.get("car_rig_r", "7"));
      IsoCell cell = IsoWorld.instance.currentCell;
      int px = p.getXi(), py = p.getYi(), pz = p.getZi();
      int placed = 0;
      BaseVehicle first = null;
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < cars; i++) {
         double a = 2.0 * Math.PI * i / cars;
         IsoGridSquare sq = null;
         int x = 0, y = 0;
         for (float rr = r; rr <= r + 4F && sq == null; rr += 1F) {
            x = px + (int)Math.round(rr * Math.cos(a));
            y = py + (int)Math.round(rr * Math.sin(a));
            if (clear(cell, x, y, pz)) {
               sq = cell.getGridSquare(x, y, pz);
            }
         }
         if (sq == null) {
            continue;
         }
         String script = models[i % models.length].trim();
         BaseVehicle v = zombie.Lua.LuaManager.GlobalObject.addVehicleDebug(script, IsoDirections.N, 0, sq);
         if (v == null || v.getSquare() == null) {
            Log.warn("harness: car rig: could not place " + script + " at " + x + "," + y);
            continue;
         }
         float angle = (float)(i * Math.PI / 4.0);
         v.savedRot.setAngleAxis(angle, 0F, 1F, 0F);
         v.jniTransform.setRotation(v.savedRot);
         v.repair();
         if (first == null) {
            first = v;
         } else if (placed == 1 && Boolean.parseBoolean(HarnessFlags.get("car_rig_broken", "false"))) {
            for (String id : new String[] {"Windshield", "WindowFrontLeft"}) {
               VehiclePart part = v.getPartById(id);
               if (part != null) {
                  part.setInventoryItem(null);
                  v.transmitPartItem(part);
               }
            }
         }
         sb.append(' ').append(script).append('@').append(x).append(',').append(y).append('/').append(i * 45);
         placed++;
      }
      if (first != null && Boolean.parseBoolean(HarnessFlags.get("car_rig_seat", "false"))) {
         p.getInventory().AddItem(first.createVehicleKey());
         boolean in = first.enter(0, p);
         Log.info("harness: car rig: player seated in " + first.getScriptName() + ": " + in);
      }
      Log.info("harness: car rig: " + placed + " cars around " + px + "," + py + ":" + sb);
   }
}
