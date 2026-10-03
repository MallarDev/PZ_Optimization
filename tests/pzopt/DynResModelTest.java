package pzopt;

import java.util.Random;

/**
 * The dynamic-resolution cost model (DynRes.Model) in a simulated closed loop: GPU ms = a + b * x + noise + lone spikes,
 * the scale solved from the model each frame and seen three frames late (the GPU timestamps' latency).
 */
public class DynResModelTest {
   static final float BUDGET = 1000.0F / 240.0F;
   static final float TARGET = BUDGET * 0.9F;

   public static void main(String[] args) {
      Random rnd = new Random(1);
      DynRes.Model m = new DynRes.Model();
      float[] pending = {1.0F, 1.0F, 1.0F};
      double a = 1.0, b = 4.0; // ms fixed, ms per unit pixel fraction: 5 ms at native, over the 4.17 ms interval
      float wish = 1.0F;
      float settled = 0.0F;
      boolean flippedToMax = false;
      for (int f = 0; f < 3000; f++) {
         if (f == 1500) {
            b = 8.0; // a heavier per-pixel load arrives at one scale (fog rolls in)
         }
         float scale = pending[f % 3];
         float x = scale * scale;
         double t = a + b * x + rnd.nextGaussian() * 0.25 + (rnd.nextInt(40) == 0 ? 6.0 : 0.0);
         m.update(x, (float)Math.max(0.05, t), 0);
         wish = m.solve(BUDGET, TARGET, 0.5F, 1.0F, 0);
         pending[f % 3] = wish;
         if (f == 1499) {
            settled = wish;
         }
         if (f > 1500 && f < 1600 && wish >= 0.999F) {
            flippedToMax = true;
         }
      }
      double expected1 = Math.sqrt((TARGET - m.headroom() * 0 - 1.0) / 4.0);
      Check.check(settled > 0.7F && settled < 0.95F, "settles at a scale whose predicted cost meets the target before the step (" + settled + ", ~" + expected1 + ")");
      Check.check(!flippedToMax, "a load step seen at one scale never makes the model think the pixels do not matter");
      double cost = 1.0 + 8.0 * wish * wish;
      Check.check(cost < BUDGET, "after the step the solved scale fits the interval again (" + wish + ", " + cost + " ms)");
      Check.check(wish < settled, "the heavier per-pixel load lowers the scale");

      // the frame is all fixed cost: a smaller image buys nothing, the scale stays at its maximum
      DynRes.Model fixed = new DynRes.Model();
      float s = 1.0F;
      int atMin = 0, probe = 0;
      for (int f = 0; f < 3000; f++) {
         // the probe DynRes.beginFrame runs: a second stuck at the minimum and over target -> 8 frames at min + 0.3
         float shown = probe > 0 ? 0.8F : s;
         float x = shown * shown;
         double t = 5.0 + 0.2 * x + rnd.nextGaussian() * 0.2;
         fixed.update(x, (float)t, 0);
         s = fixed.solve(BUDGET, TARGET, 0.5F, 1.0F, 0);
         if (probe > 0) {
            probe--;
         } else if (s <= 0.501F && ++atMin >= 240) {
            atMin = 0;
            probe = 8;
         }
      }
      Check.check(fixed.pixelsMinor(1.0F), "learns that the pixels are a minor share of a fixed-cost frame (b " + fixed.b() + ")");
      Check.check(fixed.solve(BUDGET, TARGET, 0.5F, 1.0F, 0) >= 0.999F, "and keeps the native scale there");
      // a square-wave per-pixel load (the devDynResLoad rig) with bake bursts: in each heavy half the solved scale must fit
      DynRes.Model sq = new DynRes.Model();
      float[] lag = {1.0F, 1.0F, 1.0F};
      double worst = 0.0;
      for (int f = 0; f < 6000; f++) {
         boolean heavy = f / 720 % 2 == 1;
         double bb = heavy ? 9.0 : 3.0;
         float sc = lag[f % 3];
         float x = sc * sc;
         int bakes = rnd.nextInt(30) == 0 ? 8 : 0;
         double t = 1.5 + bb * x + 0.3 * bakes + rnd.nextGaussian() * 0.25;
         sq.update(x, (float)t, bakes);
         float w = sq.solve(BUDGET, TARGET, 0.5F, 1.0F, 0);
         lag[f % 3] = w;
         if (heavy && f % 720 > 360) {
            worst = Math.max(worst, 1.5 + bb * w * w);
         }
      }
      Check.check(worst < BUDGET, "square-wave load: the settled heavy halves fit the interval (worst predicted " + worst + " ms)");
      System.out.println("DynResModelTest ok (settled " + settled + ", after the step " + wish + ")");
   }
}
