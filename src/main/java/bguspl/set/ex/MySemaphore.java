package bguspl.set.ex;

import java.util.LinkedList;

public class MySemaphore {
    LinkedList<Thread> threadQueue;
    private boolean free;

    MySemaphore() {
        free = true;
        threadQueue = new LinkedList<>();
    }

/*    public synchronized boolean tryAcquire(boolean isDealer) {
        if (free && threadQueue.isEmpty()) {
            acquire(isDealer);
            return true;
        }
        return false;
    }*/

    public synchronized void acquire(boolean isDealer) {
        if (isDealer) threadQueue.add(0, Thread.currentThread());
        else threadQueue.addLast(Thread.currentThread());
        while (!free || Thread.currentThread() != threadQueue.getFirst()) {
            try {
                wait();
            } catch (InterruptedException ignored) {
            }
        }
        free = false;
        threadQueue.remove(0);
    }

    public synchronized void release() {
        free = true;
        notifyAll();
    }
}
