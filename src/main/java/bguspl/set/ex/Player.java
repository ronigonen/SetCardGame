package bguspl.set.ex;

import bguspl.set.Env;
import java.util.Queue;
import java.util.LinkedList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Arrays;

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
    private int score;

    private Dealer dealer;

    private BlockingQueue<Integer> actions;

    public AtomicInteger answer = new AtomicInteger(0); //0=not checked yet, 1=score, 2=penalty

    public AtomicBoolean canPlaceCards = new AtomicBoolean(false);
    public AtomicBoolean setChecked = new AtomicBoolean(false);

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
        this.dealer=dealer;
        this.actions=new ArrayBlockingQueue<>(env.config.featureSize);
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
            // TODO implement main player loop
            if (!actions.isEmpty() && (answer.get()==2 || answer.get()==1)){
                actions.clear();
            }
            answer.set(0);
            if (!human) {
                synchronized (aiThread) {
                    aiThread.notifyAll();
                }
            }
            if(human){
                synchronized (actions){
                    while(actions.isEmpty() && !terminate) {
                        try {
                            actions.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            try{
                synchronized (this) {
                    if (!actions.isEmpty() && table.tableLock.get() && table.timeShown.get() && canPlaceCards.get()) {
                        int slot = actions.take();
                        if (!human) {
                            synchronized (aiThread) {
                                aiThread.notifyAll();
                            }
                        }
                        if (table.slotToCard[slot] != null) {
                            if (table.playersTokens[id].size()==env.config.featureSize && setChecked.get()) {
                                if (table.playersTokens[id].contains(slot)) {
                                    if (table.removeToken(id, slot)) {
                                        setChecked.set(false);
                                    }
                                }
                            }
                            else {
                                if (table.slotsTokens[slot] != null && table.slotsTokens[slot].contains(id)) {
                                    if (table.removeToken(id, slot)) {
                                        setChecked.set(false);
                                    }
                                } else {
                                    table.placeToken(id, slot);
                                }
                            }
                        }
                    }
                }
                if (canPlaceCards.get() && table.tableLock.get() && table.playersTokens[id].size() == env.config.featureSize && !setChecked.get()) {
                    Thread.sleep(100);
                    canPlaceCards.set(false);
                    setChecked.set(true);
                    int[] arrOfCards = new int[4];
                    arrOfCards[0] = this.id;
                    int index = 1;
                    for (Integer i : table.playersTokens[id]) {
                        arrOfCards[index] = i;
                        index = index + 1;
                    }
                    dealer.addToQueue(arrOfCards);
                    synchronized (this) {
                        while (!canPlaceCards.get())
                            wait();
                    }
                    if (answer.get() == 1) {
                        point();
                    } else if (answer.get() == 2) {
                        penalty();
                    }
                }

            } catch (InterruptedException ignored) {
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator
                int r = (int) (Math.random() * (env.config.tableSize));
                keyPressed(r);
                synchronized (aiThread) {
                    while (actions.size() == env.config.featureSize && !terminate) {
                        try {
                            aiThread.wait();
                        } catch (InterruptedException e) {
                        }
                    }
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
        // TODO implement
        terminate=true;
        if(!human) {
            aiThread.interrupt();
        }
        playerThread.interrupt();
        Thread.currentThread().interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // TODO implement
        if(human){
            synchronized (actions){
                actions.notifyAll();
            }
        }
        if(table.tableLock.get()){
            try{
            actions.put(slot);
            } catch(InterruptedException ignored){}
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement
        synchronized (this) {
            this.score = score + 1;
            int ignored = table.countCards(); // this part is just for demonstration in the unit tests
            env.ui.setScore(id, score);
            long start=System.currentTimeMillis();
            long timeLeft=env.config.pointFreezeMillis-(System.currentTimeMillis()-start);
            while (timeLeft>=0) {
                timeLeft=env.config.pointFreezeMillis-(System.currentTimeMillis()-start);
                env.ui.setFreeze(id, timeLeft + 1000);
            }
            env.ui.setFreeze(id, 0);
            setChecked.set(false);
        }
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO implement
        synchronized (this) {
            long start=System.currentTimeMillis();
            long timeLeft=env.config.penaltyFreezeMillis-(System.currentTimeMillis()-start);
            while (timeLeft>=1) {
                timeLeft=env.config.penaltyFreezeMillis-(System.currentTimeMillis()-start);
                env.ui.setFreeze(id, timeLeft+1000);
            }
            env.ui.setFreeze(id, 0);
            setChecked.set(true);
        }
    }

    public int score() {
        return score;
    }
    public void removeTokens(){
        actions.clear();
        setChecked.set(false);
        if (!human)
            synchronized (aiThread) {
                aiThread.notifyAll();
            }
        synchronized (this) {
            this.notifyAll();
        }
        canPlaceCards.set(true);
    }
}
