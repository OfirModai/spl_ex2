package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Random;
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
    private volatile int score;

    /**
     * counts the num of tokens that has been placed by the player
     */
    private volatile AtomicInteger tokenCounter = new AtomicInteger();

    /**
     * player needs to communicate with the dealer
     */
    private final Dealer dealer;

    private ArrayList<Integer> keysPressed;

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
        this.tokenCounter.set(0);
        this.dealer = dealer;
        this.score = 0;
        this.keysPressed = new ArrayList<>();
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            if (!keysPressed.isEmpty()) //operates the first keyPressed in line
                keyPressedFromPlayerThread(keysPressed.remove(0));
            else {
                try {
                    Thread.currentThread().wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
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
            while (!terminate) {
                int randomSlot = (int) (Math.random() * env.config.tableSize);

                // TODO implement player key press simulator
                try {
                    synchronized (this) {
                        wait();
                    }
                } catch (InterruptedException ignored) {
                }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = false;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    private void keyPressedFromPlayerThread(int slot) {
        if (tokenCounter.get() == 3)
            throw new RuntimeException("It's a bug - too many tokens has been placed!");
        if (!table.isSlotEmpty(slot) && !table.isTokenPlaced(id, slot)) {
            table.placeToken(id, slot);
            tokenCounter.incrementAndGet();
        } else {
            table.removeToken(id, slot);
            tokenCounter.decrementAndGet();
        }
        if (tokenCounter.get() == 3) {
            dealer.callDealer(id);
            keysPressed.clear();
            tokenCounter.compareAndSet(3, 0);
        }
    }

    public void keyPressed(int slot) {
        keysPressed.add(slot);
        playerThread.notify();
    }


    public void decreaseTokenCounter() {
        tokenCounter.decrementAndGet();
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        try {
            playerThread.wait(env.config.pointFreezeMillis);
        } catch (InterruptedException ignored2) {
        }
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        try {
            playerThread.wait(env.config.penaltyFreezeMillis);
        } catch (InterruptedException ignored) {
        }
    }

    public int score() {
        return score;
    }
}
