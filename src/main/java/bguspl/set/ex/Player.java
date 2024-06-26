package bguspl.set.ex;

import bguspl.set.Env;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private volatile AtomicInteger score;

    /**
     * counts the num of tokens that has been placed by the player
     */
    public volatile AtomicInteger tokenCounter;

    /**
     * player needs to communicate with the dealer
     */
    private final Dealer dealer;

    private LinkedBlockingDeque<Integer> keysPressed;

    private volatile AtomicBoolean dealerChecks;

    private volatile long playerToSleep;
    private volatile long aiToSleep;

    public volatile boolean playerStarted;
    private volatile boolean aiStarted;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.tokenCounter = new AtomicInteger(0);
        this.dealer = dealer;
        this.score = new AtomicInteger(0);
        this.keysPressed = new LinkedBlockingDeque<>(env.config.featureSize);
        this.dealerChecks = new AtomicBoolean(false);
        this.playerStarted = false;
        this.aiStarted = false;

    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        playerStarted = true;
        // wait for everyone to start
        synchronized (table) {}
        while (!terminate) {
            try {
                Thread.sleep(playerToSleep);
                playerToSleep = 0;
                Integer key = keysPressed.take();
                if (!terminate & playerToSleep == 0) { // added this condition for the situation of the end
                    //env.logger.info("player " + id + " took press");
                    keyPressedFromPlayerThread(key);
                    // the thread wakes here and don't need to put the token
                }
            } catch (InterruptedException ignored) {
            }

        }
        if (!human) {
            boolean killed = false;
            // mechanism to kill aiThreads before the belonging playerThreads
            do {
                try {
                    killed = true;
                    aiThread.interrupt();
                    aiThread.join();
                } catch (InterruptedException exception) {
                    killed = false;
                }
            } while (!killed);
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("generator_thread " + Thread.currentThread().getName() + " starting.");
            aiStarted = true;
            while (!terminate) {
                try {
                    Thread.sleep(aiToSleep);
                    aiToSleep = 0;
                } catch (InterruptedException ignored) {
                }
                int randomSlot = (int) (Math.random() * env.config.tableSize);
                //env.logger.info("player "+ id + " generated press");
                keyPressed(randomSlot);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
        while (!aiStarted);
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        boolean killed = false;
        do {
            killed = true;
            try {
                playerThread.interrupt();
                playerThread.join();
            } catch (InterruptedException exception) {
                killed = false;
            }
        } while (!killed);
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    private void keyPressedFromPlayerThread(int slot) {
        if (tokenCounter.get() == env.config.featureSize | dealerChecks.get()) //just for ourselves
            throw new RuntimeException("It's a bug - too many tokens has been placed! or the dealer checks");

        synchronized (table) {
            if (table.isSlotEmpty(slot)) return;
            if (!table.isTokenPlaced(id, slot)) {
                table.placeToken(id, slot);
                tokenCounter.incrementAndGet();
            } else {
                table.removeToken(id, slot);
                tokenCounter.decrementAndGet();
            }
        }
        //calls dealer for set check
        if (tokenCounter.get() == env.config.featureSize) {
            dealerChecks.compareAndSet(false, true);
            dealer.callDealer(id);
            keysPressed.clear();
            // was: tokenCounter.compareAndSet(3, 0);
            // deleted caused we need the count if one is taken down
            synchronized (this) {
                try {
                    this.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    public void keyPressed(int slot) {
        if (dealerChecks.get()) {
            synchronized (this) {
                try {
                    this.wait();
                } catch (InterruptedException ignored) {
                }
            }
        } else {
            try {
                keysPressed.put(slot);
            } catch (InterruptedException ignore) {
            }
        }

    }


    public void oneTokenIsRemoved() {
        tokenCounter.decrementAndGet();
        if (dealerChecks.get()) { // ofir - make the player know his call was canceled
            dealerChecks.compareAndSet(true, false);
            playerThread.interrupt();
            if (aiThread != null) aiThread.interrupt();
            synchronized (this) {
                this.notifyAll();
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, score.incrementAndGet());
        playerToSleep = env.config.pointFreezeMillis;
        aiToSleep = env.config.pointFreezeMillis;
        dealerChecks.compareAndSet(true, false);
        playerThread.interrupt();
        if (aiThread != null) aiThread.interrupt();
        synchronized (this) {
            this.notifyAll();
        }
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        playerToSleep = env.config.penaltyFreezeMillis;
        aiToSleep = env.config.penaltyFreezeMillis;
        //env.logger.info("player " + id + " got penalty");
        dealerChecks.compareAndSet(true, false);
        playerThread.interrupt();
        if (aiThread != null) aiThread.interrupt();
        synchronized (this) {
            this.notifyAll();
        }
    }

    public int score() { // not synchronized by purpose: will return the right score for the very second it was called.
        return score.get();
    }
}
