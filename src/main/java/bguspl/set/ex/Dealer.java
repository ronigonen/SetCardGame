package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
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
    private long reshuffleTime = Long.MAX_VALUE;


    private BlockingQueue<int[]> setsQueue;
    private Semaphore sem;


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.setsQueue=new ArrayBlockingQueue<>(players.length, true);
        this.sem=new Semaphore(1,true);
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        Thread[] threads = new Thread[players.length];
        for (int i = 0; i < players.length; i++) {
            threads[i] = new Thread(players[i]);
        }
        for (int i = 0; i < players.length; i++) {
            threads[i].start();
        }
        for (Player p : players) {
            p.canPlaceCards.set(true);
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis()-999 < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        terminate=true;
        Thread.currentThread().interrupt();
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        if (deck==null){
            return true;
        }
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        // TODO implement
        if (table.tableLock.get()) {
            table.tableLock.set(false);
            if (!this.setsQueue.isEmpty()) {
                int[] a = new int[4];
                try {
                    a = this.setsQueue.take();
                } catch (InterruptedException ignored) {
                }
                if (table.slotToCard[a[1]] != null && table.slotToCard[a[2]] != null && table.slotToCard[a[3]] != null) {
                    int idPlayer = a[0];
                    int[] arrToCheck = new int[3];
                    arrToCheck[0] = table.slotToCard[a[1]];
                    arrToCheck[1] = table.slotToCard[a[2]];
                    arrToCheck[2] = table.slotToCard[a[3]];
                    Player p = players[idPlayer];
                    if (this.env.util.testSet(arrToCheck)) {
                        table.tableLock.set(false);
                        p.answer.set(1);
                        for (int i = 1; i <= 3; i++) {
                            for (Player player: players){
                                table.removeToken(player.id, a[i]);
                            }
                            table.removeCard(a[i]);
                        }
                        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
                    } else {
                        p.answer.set(2);
                    }
                } else {
                    players[a[0]].answer.set(0);
                }
                players[a[0]].canPlaceCards.set(true);
                synchronized (players[a[0]]) {
                    players[a[0]].notifyAll();
                }
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // TODO implement
            table.tableLock.set(false);
            boolean placed=false;
            List<Integer> cards=new ArrayList<>();
            for (int i = 0; i < env.config.tableSize; i++) {
                if (this.table.slotToCard[i] == null) {
                    if (deck.size() >= 1) {
                        Collections.shuffle(deck);
                        int card = deck.remove(0);
                        table.placeCard(card, i);
                        placed=true;
                        cards.add(card);
                    }
                }
            }
            if(placed) {
                updateTimerDisplay(true);
            }
            table.tableLock.set(true);
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        // TODO implement
        synchronized (this) {
            if (this.setsQueue.isEmpty()) {
                if (reshuffleTime-System.currentTimeMillis()+999<=env.config.turnTimeoutWarningMillis){
                    try {
                        this.wait(10);
                    } catch (InterruptedException ignored) {}
                }
                else {
                    try {
                        this.wait(1000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
            if (reset){
                long start=System.currentTimeMillis();
                reshuffleTime=start+env.config.turnTimeoutMillis;
                env.ui.setCountdown(env.config.turnTimeoutMillis+999, false);
            }
            long timeLeft=reshuffleTime-System.currentTimeMillis()+999;
            boolean warn=timeLeft<=env.config.turnTimeoutWarningMillis;
            if(timeLeft<0) {
                env.ui.setCountdown(0, warn);
                table.timeShown.set(false);
            }
            else {
                env.ui.setCountdown(timeLeft, warn);
                table.timeShown.set(true);
            }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement
        table.tableLock.set(false);
        for (int i=0; i<env.config.tableSize; i++) {
            if(table.slotToCard[i]!=null){
                deck.add(table.slotToCard[i]);
            }
            for (Player p:players){
                table.removeToken(p.id, i);
                p.removeTokens();
            }
            table.removeCard(i);
        }
        this.setsQueue.clear();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
            int maxScore = 0;
            LinkedList<Integer> winners = new LinkedList<>();
            for (Player p : players) {
                if (p.score() > maxScore) {
                    maxScore = p.score();
                }
            }
            for (Player p : players) {
                if (p.score() == maxScore) {
                    winners.add(p.id);
                }
            }
            int[] arrWinners = new int[winners.size()];
            int index = 0;
            for (int id : winners) {
                arrWinners[index]=id;
                index=index + 1;
            }
            try{
                Thread.sleep(env.config.endGamePauseMillies);
            }
            catch (InterruptedException ignored){}
            env.ui.announceWinner(arrWinners);
    }


    public void addToQueue(int[] arr) {
        try {
            sem.acquire();
        } catch (InterruptedException interrupt) {
        }
        this.setsQueue.add(arr);
        sem.release();
        synchronized (this) {
            this.notifyAll();
        }
    }

}
