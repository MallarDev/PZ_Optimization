package pzopt;

/** pzopt.GpuPstate.Governor: the gpuPstate=auto ladder on a Steam Deck-like and a flip-like GPU. */
public class GpuPstateTest {
   static final int NONE = 0, STANDARD = 1, MIN_SCLK = 2;
   static final long WINDOW = 500_000_000L;

   /** Drives the governor for {@code seconds} with a GPU whose frame time is {@code autoMs} x the level's slowdown. */
   static int run(GpuPstate.Governor g, float autoMs, float standardX, float minSclkX, float budgetMs, int seconds, int[] switches) {
      int level = NONE;
      long t = 1_000_000_000L;
      for (long end = t + seconds * 1_000_000_000L; t < end; t += WINDOW) {
         float ms = autoMs * (level == STANDARD ? standardX : level == MIN_SCLK ? standardX * minSclkX : 1.0F);
         int next = g.next(level, t, ms, budgetMs, 95);
         if (next != level) {
            switches[0]++;
            level = next;
         }
      }
      return level;
   }

   public static void main(String[] args) {
      float budget = 1000.0F / 45; // the Deck player's 45 fps cap

      // Steam Deck (2026-09-28 log): ~2.4 ms at automatic, 4.6 at standard, min_sclk 4.9x standard = 22.5 ms
      GpuPstate.Governor deck = new GpuPstate.Governor();
      int[] sw = {0};
      int level = run(deck, 2.4F, 1.9F, 4.9F, budget, 400, sw);
      Check.check(level == STANDARD, "the Deck settles at standard, got " + level);
      Check.check(sw[0] <= 3, "the Deck tries min_sclk once, not every few seconds: " + sw[0] + " switches in 400 s");
      Check.check(Math.abs(deck.slowdown[MIN_SCLK] - 4.9F) < 0.01F, "min_sclk's slowdown is measured: " + deck.slowdown[MIN_SCLK]);

      // flip-like: min_sclk only 1.25x standard, so it holds and the ladder goes all the way down and stays
      GpuPstate.Governor flip = new GpuPstate.Governor();
      sw[0] = 0;
      level = run(flip, 3.4F, 1.25F, 1.25F, 1000.0F / 60, 400, sw);
      Check.check(level == MIN_SCLK, "the flip holds min_sclk, got " + level);
      Check.check(sw[0] == 2, "two steps down and no more: " + sw[0]);

      // a heavier scene later: at min_sclk over the fit -> one step up, back-off doubles
      GpuPstate.Governor g = new GpuPstate.Governor();
      long t = 1_000_000_000L;
      Check.check(g.next(MIN_SCLK, t, 30.0F, budget, 95) == STANDARD, "over the fit steps up");
      Check.check(g.backoffNs == 2 * GpuPstate.Governor.BACKOFF_MIN_NS, "the back-off doubles");
      Check.check(g.next(STANDARD, t + WINDOW, 2.0F, budget, 95) == STANDARD, "no step down inside the back-off");
      Check.check(g.next(NONE, t, 50.0F, budget, 95) == NONE, "automatic is the top rung");

      // the guess still lets a light scene try the lower rung once
      GpuPstate.Governor fresh = new GpuPstate.Governor();
      Check.check(fresh.next(STANDARD, 10_000_000_000L, 4.0F, budget, 95) == MIN_SCLK, "an unmeasured rung is tried from the clock guess");
      // a near-empty first window (loading frames) teaches nothing: the flip's run learned 38.7 / 0.26 = 148x
      GpuPstate.Governor load = new GpuPstate.Governor();
      Check.check(load.next(NONE, 10_000_000_000L, 0.26F, budget, 95) == STANDARD, "a light window steps down");
      Check.check(load.next(STANDARD, 10_500_000_000L, 38.7F, budget, 95) == NONE, "the heavy scene steps back up");
      Check.check(load.slowdown[STANDARD] == 0.0F, "no slowdown learned from a 0.26 ms window: " + load.slowdown[STANDARD]);
      // a real window before a scene change is clamped to the clock range
      GpuPstate.Governor jump = new GpuPstate.Governor();
      jump.next(NONE, 10_000_000_000L, 2.0F, budget, 95);
      jump.next(STANDARD, 10_500_000_000L, 40.0F, budget, 95);
      Check.check(jump.slowdown[STANDARD] == GpuPstate.Governor.MAX_SLOWDOWN, "a 20x jump is clamped to 8x: " + jump.slowdown[STANDARD]);
      System.out.println("GpuPstateTest ok");
   }
}
