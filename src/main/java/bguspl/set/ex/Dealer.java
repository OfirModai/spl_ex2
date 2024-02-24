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
    private volatile Queue<Integer> calls;
    private long starting_time;
    private long last_updated_time;
    private volatile MySemaphore callsLock;
    private volatile Thread dealerThread;

    private List<Thread> playersThreads;

    // declaring consts for not using magic numbers
    private static final int second = 1000;
    private static final int hundredth = 10;



    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        calls = new LinkedList<>();
        callsLock = new MySemaphore(env);
        last_updated_time = 0; // we haven't updated yet, therefore it's 0
        dealerThread = null;
        playersThreads = new LinkedList<>(); //new - save the threads to make sure they are terminated
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
            playersThreads.add(t);
        }
        while (!shouldFinish()) {
            timerLoop();
            removeAllCardsFromTable();
            synchronized (table) {
                deckShuffle();
                placeCardsOnTable();
            }
        }
        terminate();
        env.ui.removeTokens(); //ofir - make it look nicer in the end
        env.logger.info("decksize: " + deck.size());
        // new - make sure all threads finishes before the dealer
        /*for(Thread t : playersThreads) {
            try {
                t.interrupt(); // waking the player to terminate himself
                t.join();
            } catch (InterruptedException ignored) {
            }
        }*/
        announceWinners();

        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        boolean timeout = false;
        boolean keepPlaying = true;
        updateTimerDisplay(true); // instead of writing starting time
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
        dealerThread.interrupt();
        callsLock.acquire(false);
        if (!calls.contains(id)) {
            //env.logger.info(Thread.currentThread().getName()+" request call");
            calls.add(id);
            //env.logger.info(Thread.currentThread().getName()+" call added");
        }
        callsLock.release();

    }

    /**
     * Checks what cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        callsLock.acquire(true);
        synchronized (table) {
            if (calls.isEmpty()) {
                callsLock.release();
                return;
            }
            int playerId = calls.remove();
            //env.logger.info("player "+playerId+" getting checked");
            //was here: callsLock.release();
            int[] set = table.getSetById(playerId);
            table.resetTokensById(playerId);
            players[playerId].tokenCounter.compareAndSet(env.config.featureSize,0);
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
        callsLock.release(); //release only here for removing calls which had tokens that were won before them
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

    /**
     * assuming you already took calls lock
     * @param slot
     */
    private void removeCardAndNotify(int slot) {
        //changed the order here - firstly we remove token and then the card making it more clear
        for (int i = 0; i < players.length; i++) {
            if (table.isTokenPlaced(i, slot)) {
                if (calls.contains(i)) calls.remove(i); // ofir
                players[i].oneTokenIsRemoved();
                table.removeToken(i,slot);
                // here we need to update the player that if he called the dealer, the call is canceled
            }
        }
        table.removeCard(slot);
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        callsLock.acquire(true);
        if (calls.isEmpty()) {
            callsLock.release();
            //long difference = env.config.turnTimeoutMillis - (System.currentTimeMillis() - starting_time);
            long difference;
            boolean warn = env.config.turnTimeoutMillis - env.config.turnTimeoutWarningMillis < last_updated_time - starting_time;
            if (warn) difference = hundredth-1 - (System.currentTimeMillis() - last_updated_time);
            else difference = second-1 - (System.currentTimeMillis() - last_updated_time);
            env.logger.info("dealer sleeps: " + difference);
            if (difference < 0) difference = 0;
            try {
                Thread.sleep(difference);
            } catch (InterruptedException ignored) {
            }
        }
        else {
            callsLock.release();
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
        }
        else {
            // changed here the code to be more straight forward and print the maximum time + making it more
            // appiling to humans
            long current_time_left = env.config.turnTimeoutMillis - (last_updated_time - starting_time);
            boolean warn = false;
            if(current_time_left < env.config.turnTimeoutWarningMillis) warn = true;
            else current_time_left = roundToSecondsIntuitively(current_time_left);
            env.ui.setCountdown(current_time_left, warn);
        }
    }

    private long roundToSecondsIntuitively(long millis){
        if (millis % second > (second/2)) return millis + second - (millis % second);
        return millis - (millis % second);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        callsLock.acquire(true);
        synchronized (table) { //ofir
            for (int i = 0; i < env.config.tableSize; i++) {
                //was : table.removeCard(i);
                removeCardAndNotify(i);
            }
        }
        callsLock.release();

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
