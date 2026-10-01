package pzopt;

import java.util.concurrent.atomic.AtomicInteger;

/** pzopt.LuaGate: a worker's call runs on the thread that drives the batch (the game thread's role) and returns its result. */
public class LuaGateTest {
   public static void main(String[] args) throws Exception {
      Check.check(LuaGate.ENABLED, "the gate is on by default");
      Check.check(!LuaGate.onWorker(), "the calling thread is not a worker");
      Thread game = Thread.currentThread();
      AtomicInteger onGame = new AtomicInteger(), elsewhere = new AtomicInteger(), workerCalls = new AtomicInteger(), voids = new AtomicInteger();
      long before = LuaGate.handled();
      Throwable t = FrameBatch.run(400, i -> {
         if (LuaGate.onWorker()) {
            workerCalls.incrementAndGet();
            Object r = LuaGate.call(null, () -> Thread.currentThread());
            (r == game ? onGame : elsewhere).incrementAndGet();
            LuaGate.callVoid(null, () -> {
               if (Thread.currentThread() == game) {
                  voids.incrementAndGet();
               }
            });
         }
         Thread.sleep(0, 200_000);
      });
      Check.check(t == null, "no failure: " + t);
      Check.check(workerCalls.get() > 0, "workers took part");
      Check.check(elsewhere.get() == 0 && onGame.get() == workerCalls.get(), "every worker call ran on the batch's thread: " + onGame + "/" + workerCalls);
      Check.check(voids.get() == workerCalls.get(), "void calls ran on the batch's thread outside an update batch: " + voids);
      Check.check(LuaGate.handled() - before == 2L * workerCalls.get(), "counted: " + LuaGate.describe());
      Check.check(LuaGate.timeouts() == 0, "no timeout");
      // an exception thrown by the call reaches the worker
      Throwable f = FrameBatch.run(64, i -> {
         if (LuaGate.onWorker()) {
            LuaGate.call(null, () -> {
               throw new IllegalStateException("lua error");
            });
         }
      });
      Check.check(f == null || f instanceof IllegalStateException, "the call's exception surfaces on the worker: " + f);
      System.out.println("LuaGateTest ok " + LuaGate.describe());
   }
}
