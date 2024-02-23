package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;

public class MySemaphore {
    private LinkedList<Thread> threadQueue;
    private boolean free;
    private final Env env;;

    MySemaphore(Env env) {
        free = true;
        threadQueue = new LinkedList<>();
        this.env = env;
    }

/*    public synchronized boolean tryAcquire(boolean isDealer) {
        if (free && threadQueue.isEmpty()) {
            acquire(isDealer);
            return true;
        }
        return false;
    }*/

    public synchronized void acquire(boolean isDealer) {
        if (isDealer)
            threadQueue.add(0, Thread.currentThread());
        else threadQueue.addLast(Thread.currentThread());
        while (!free || Thread.currentThread() != threadQueue.getFirst()) {
            try {
                wait();
            } catch (InterruptedException ignored) {
            }
        }
        env.logger.info(Thread.currentThread().getName() + " took lock");
        free = false;
        threadQueue.remove(0);
    }

    public synchronized void release() {
        free = true;
        env.logger.info(Thread.currentThread().getName() + " released lock");
        notifyAll();
    }
}
