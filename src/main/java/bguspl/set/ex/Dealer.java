package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
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
    private long last_updated_time;
    static MySemaphore callsLock;
    Thread dealerThread;


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        calls = new LinkedList<>();
        callsLock = new MySemaphore();
        last_updated_time = 0; // we haven't updated yet, therefore it's 0
        dealerThread = null;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        deckShuffle();
        placeCardsOnTable();
        dealerThread = Thread.currentThread();
        for (int i = 0; i < players.length; i++) {
            Thread t = new Thread(players[i], "player " + i);
            t.start();
        }
        while (!shouldFinish()) {
            timerLoop();
            synchronized (table) {
                removeAllCardsFromTable();
                deckShuffle();
                updateTimerDisplay(true);
                placeCardsOnTable();
            }
        }
        terminate();
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() { // maybe we should consider small delay between checks! -omer
        starting_time = System.currentTimeMillis();
        boolean timeout;
        boolean keepPlaying = true;
        table.hints();
        while (!terminate && keepPlaying) {  // !timeout & env.util.findSets(table.getCards(), 1).size() > 0};
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
            timeout = System.currentTimeMillis() - starting_time > env.config.turnTimeoutMillis;
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
        return terminate || (env.util.findSets(deck, 1).size() == 0);
    }


    public void callDealer(int id) {
        if (!calls.contains(id)) {
            callsLock.acquire(false);
            calls.add(id);
            callsLock.release();
            dealerThread.interrupt();
        }
    }

    /**
     * Checks what cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (calls.isEmpty()) return;
        synchronized (table) {
            callsLock.acquire(true);
            int playerId = calls.remove();
            callsLock.release();
            int[] set = table.getSetById(playerId);
            table.resetTokensById(playerId);
            if (env.util.testSet(set)) {
                for (int i = 0; i < set.length; i++) {
                    removeCardAndNotify(table.cardToSlot[set[i]]); // was set[i]
                }
                placeCardsOnTable();
                players[playerId].point();
                table.hints(); /// to delete
            } else
                players[playerId].penalty();
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        for (int i = 0; deck.size() > 0 && i < env.config.tableSize; i++) {
            if (table.isSlotEmpty(i)) {
                table.placeCard(deck.remove(0), i);
            }
        }
    }

    private void removeCardAndNotify(int slot) {
        //changed the order here - I don't know why but it fixed the 4 tokens problem
        table.removeCard(slot);
        for (int i = 0; i < players.length; i++) {
            if (table.isTokenPlaced(i, slot)) {
                players[i].decreaseTokenCounter();
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if (calls.isEmpty()) {
            //long difference = env.config.turnTimeoutMillis - (System.currentTimeMillis() - starting_time);
            long difference;
            boolean warn = env.config.turnTimeoutMillis - env.config.turnTimeoutWarningMillis < last_updated_time - starting_time;
            if (warn) difference = 9 - (System.currentTimeMillis() - last_updated_time);
            else difference = 999 - (System.currentTimeMillis() - last_updated_time);
            if (difference < 0) difference = 0;
            try {
                Thread.sleep(difference);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) { // to see if the time updates good
        last_updated_time = System.currentTimeMillis();
        if (reset) {
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
            starting_time = last_updated_time;
        } else {
            env.ui.setCountdown(env.config.turnTimeoutMillis - (last_updated_time - starting_time),
                    env.config.turnTimeoutMillis - env.config.turnTimeoutWarningMillis < last_updated_time - starting_time);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        synchronized (table) { //ofir

            for (int i = 0; i < env.config.tableSize; i++) {
                //was : table.removeCard(i);
                removeCardAndNotify(i);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxPoints = 0;
        Vector<Integer> winners = new Vector<>();
        for (int i = 0; i < players.length; i++) {
            if (players[i].score() == maxPoints) {
                winners.add(players[i].id);
            } else if (players[i].score() > maxPoints) {
                maxPoints = players[i].score();
                winners.clear();
                winners.add(players[i].id);
            }
        }
        int[] winnersID = new int[winners.size()];

        for (int i = 0; i < winnersID.length; i++) {
            winnersID[i] = winners.get(i);
        }
        env.ui.announceWinner(winnersID);
    }

    // shuffles the deck
    private void deckShuffle() {
        Collections.shuffle(deck);
    }

}
