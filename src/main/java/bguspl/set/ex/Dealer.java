package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private Queue<Integer> calls;
    private long starting_time;


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        calls = new LinkedList<>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (int i = 0; i < players.length; i++) {
            Thread t = new Thread(players[i], "player " + i);
            t.start();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            killPlayerThreads(); // to check if this is what they want
            removeAllCardsFromTable();
            for (int i = 0; i < players.length; i++) {
                Thread t = new Thread(players[i], "player " + i);
                t.start();
            }
            updateTimerDisplay(true);
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        terminate(); // to check for double terminate
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() { // maybe we should consider small delay between checks! -omer
        starting_time = System.currentTimeMillis();
        boolean timeout;
        boolean keepPlaying = true;
        while (!terminate && keepPlaying) {  // !timeout & env.util.findSets(table.getCards(), 1).size() > 0};
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
            timeout = Math.abs(System.currentTimeMillis() - starting_time) < env.config.turnTimeoutMillis;
            if (timeout & env.util.findSets(table.getCards(), 1).size() == 0)
                keepPlaying = false;
            if (timeout)
                updateTimerDisplay(true);
        }

    }

    /**
     * Called when the game should be terminated.
     */
    public void killPlayerThreads() {
        for (Player player : players) {
            player.terminate();
        }
    }

    public void terminate() {
        killPlayerThreads();
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate ||
                (env.util.findSets(deck, 1).size() == 0);
    }


    public synchronized void callDealer(int id) {
        if (!calls.contains(id)) calls.add(id);
    }

    /**
     * Checks what cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (calls.size() == 0) return;
        int playerId;
        synchronized (this) {
            playerId = calls.remove();
        }
        int[] set = table.getSetById(playerId);
        table.resetTokensById(playerId);
        if (env.util.testSet(set)) {
            for (int i = 0; i < set.length; i++) {
                removeCardAndNotify(set[i]);
            }
            placeCardsOnTable();
            players[playerId].point();
        } else
            players[playerId].penalty();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        for (int i = 0; deck.size() > 0 && i < env.config.tableSize; i++) {
            if (table.isSlotEmpty(i))
                table.placeCard(deck.remove(0), i);
        }
    }

    private void removeCardAndNotify(int slot) {
        for (int i = 0; i < players.length; i++) {
            if (table.isTokenPlaced(i, slot)) {
                players[i].decreaseTokenCounter();
            }
        }
        table.removeCard(slot);

    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if (calls.isEmpty()) {
            long difference = env.config.turnTimeoutMillis - (System.currentTimeMillis() - starting_time);
            try {
                Thread.sleep((long) (difference - Math.floor(difference)));
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) { // to see if the time updates good
        if (reset) {
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
            starting_time = System.currentTimeMillis();
        } else {
            env.ui.setCountdown(env.config.turnTimeoutMillis - (System.currentTimeMillis() - starting_time),
                    env.config.turnTimeoutWarningMillis < System.currentTimeMillis() - starting_time);
        }

    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for (int i = 0; i < env.config.tableSize; i++) {
            table.removeCard(i);
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int[] playersId = new int[players.length];
        int i = 0;
        for (Player player : players) {
            playersId[i] = player.id;
            i++;
        }
        env.ui.announceWinner(playersId);
    }
}
