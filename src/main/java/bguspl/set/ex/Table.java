package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 * all functions in class are synchronized
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {


    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected volatile Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected volatile Integer[] cardToSlot; // slot per card (if any)

    private volatile boolean[][] tokens;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.tokens = new boolean[slotToCard.length][env.config.players];
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {
        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     * uses
     */
    public synchronized void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public synchronized int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     *
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     * @post - the card placed is on the table, in the assigned slot.
     */
    public synchronized void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }
        env.ui.placeCard(card, slot);
        cardToSlot[card] = slot;
        slotToCard[slot] = card;
    }

    /**
     * Removes a card from a grid slot on the table.
     *
     * @param slot - the slot from which to remove the card.
     */

    public synchronized void removeCard(int slot) {
        if (slotToCard[slot] == null) return;
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }
        env.ui.removeCard(slot);
        cardToSlot[slotToCard[slot]] = null;
        slotToCard[slot] = null;
        // also changed the condition of the for loop a little bit to make it more straight-forward
        for (int i = 0; i < env.config.players; i++) {
            ///was:  like this: wrong way to remove token
            /*if (tokens[slot][i])
                tokens[slot][i] = false;*/
            // correction:
            removeToken(i,slot);
        }
    }

    /**
     * Places a player token on a grid slot.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public synchronized boolean isTokenPlaced(int player, int slot) {
        return (slotToCard[slot] != null && tokens[slot][player]);
    }

    public synchronized void placeToken(int player, int slot) {
        if (!isSlotEmpty(slot) && !isTokenPlaced(player, slot)) {
            env.ui.placeToken(player, slot);
            tokens[slot][player] = true;
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return - true iff a token was successfully removed.
     */
    public synchronized boolean removeToken(int player, int slot) {
        if (isTokenPlaced(player, slot)) {
            env.ui.removeToken(player, slot);
            tokens[slot][player] = false;
            return true;
        }
        return false;
    }

    public synchronized int[] getSetById(int id) {
        int[] set = new int[env.config.featureSize]; //omer change - from: featureCount -> to featureSize
        int indx = 0;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i][id])
                set[indx++] = slotToCard[i];
        }
        return set;
    }

    public synchronized List<Integer> getCards() {
        List<Integer> cardList = new LinkedList<>();
        for (Integer card : slotToCard) {
            if(card != null) cardList.add(card); // was without the checking and coused exeption to the util check
        }
        return cardList;
    }

    public synchronized void resetTokensById(int playerId) {
        for (int i = 0; i < tokens.length; i++) {
            removeToken(playerId, i); // omer - table didn't remove the tokens from the cards
        }
    }

    public synchronized boolean isSlotEmpty(int slot) {
        return slotToCard[slot] == null;
    }
}
