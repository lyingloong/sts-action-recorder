package actionrecorder.runtime;

import actionrecorder.ui.ActionToastOverlay;
import actionrecorder.ui.AvailableActionsOverlay;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.ui.buttons.CardSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Game-side semantic decision recorder with optional raw-input diagnostics.
 */
public final class ActionRecorderRuntime {
    private static final ActionRecorderRuntime INSTANCE = new ActionRecorderRuntime();
    private static final String MOD_VERSION = "0.1.1";
    private static final String SCHEMA_VERSION = "0.5";

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final long reconnectIntervalMs;
    private final String eventsDirectory;
    private final CaptureMode captureMode;
    private final String recorderSession = UUID.randomUUID().toString();
    private final BlockingQueue<QueuedEvent> eventQueue = new LinkedBlockingQueue<QueuedEvent>();
    private final BlockingQueue<String> tcpQueue = new LinkedBlockingQueue<String>(1024);
    private final CommunicationStateBridge stateBridge = new CommunicationStateBridge();
    /**
     * Transaction ids are emitted before the game mutates its state.  The
     * external CommunicationMod bridge uses these ids to bind an accepted
     * action to a state snapshot; it must never infer the before-state from a
     * wall-clock timestamp alone.
     */
    private final Deque<String> transactionStack = new ArrayDeque<String>();
    private final Object endTurnToken = new Object();
    private final QueuedDecisionTracker<Object> queuedDecisions = new QueuedDecisionTracker<Object>();
    private volatile boolean accepting = true;
    private final Thread writerThread;
    private final Thread senderThread;

    private Socket socket;
    private BufferedWriter writer;
    private final Map<String, BufferedWriter> fileWriters = new HashMap<String, BufferedWriter>();
    private long nextConnectAt;
    private long eventSeq;
    private boolean inRun;
    private boolean terminalRun;
    private String terminalReason;
    private int lastTurn = -1;
    private String lastRoom = "";
    private String lastGridSelectionSignature = "";
    private boolean gridPreviewActive;
    private String lastHandSelectionSignature = "";
    private String savedRunId;
    private String savedFingerprint;
    private String activeRunId;
    private String activeFile;
    private volatile ActionToastOverlay actionToast;
    private volatile AvailableActionsOverlay availableActionsOverlay;
    /** Last CommunicationMod state, used only by the optional in-game debug overlay. */
    private JsonObject latestFrameSnapshot;
    private String publishedStateId;
    private long publishedStateSeq;

    private ActionRecorderRuntime() {
        host = property("host", "127.0.0.1");
        port = integerProperty("port", 8766);
        connectTimeoutMs = integerProperty("connect_timeout_ms", 250);
        reconnectIntervalMs = integerProperty("reconnect_interval_ms", 1000);
        eventsDirectory = property("events_dir", "data/actionrecorder");
        captureMode = CaptureMode.from(property("capture_mode", "game_actions"));
        writerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                writerLoop();
            }
        }, "action-recorder-writer");
        writerThread.setDaemon(true);
        senderThread = new Thread(new Runnable() {
            @Override public void run() {
                while (accepting || writerThread.isAlive() || !tcpQueue.isEmpty()) {
                    try {
                        String message = tcpQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (message != null) sendTcp(message);
                    } catch (InterruptedException ignored) { }
                }
                closeConnection();
            }
        }, "action-recorder-tcp");
        senderThread.setDaemon(true);
        writerThread.start();
        senderThread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }, "action-recorder-shutdown"));
    }

    public static ActionRecorderRuntime getInstance() {
        return INSTANCE;
    }

    public void setActionToast(ActionToastOverlay actionToast) {
        this.actionToast = actionToast;
    }

    public void setAvailableActionsOverlay(AvailableActionsOverlay overlay) {
        this.availableActionsOverlay = overlay;
        if (overlay != null) overlay.setSnapshot(latestFrameSnapshot);
    }

    /** Remember terminal screens before CardCrawlGame clears its dungeon reference. */
    public synchronized void markRunTerminal(String reason) {
        if (!inRun) return;
        if (!terminalRun) emit("run_finished", "\"reason\":" + quote(reason)
                + ",\"terminal\":true,\"context\":" + contextJson());
        terminalRun = true;
        terminalReason = reason;
    }

    public synchronized com.google.gson.JsonElement saveRunIdentity() {
        com.google.gson.JsonObject value = new com.google.gson.JsonObject();
        if (savedRunId != null) {
            value.addProperty("run_id", savedRunId);
        }
        if (savedFingerprint != null) {
            value.addProperty("fingerprint", savedFingerprint);
        }
        return value;
    }

    public synchronized void restoreRunIdentity(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            savedRunId = null;
            savedFingerprint = null;
            return;
        }
        com.google.gson.JsonObject object = value.getAsJsonObject();
        savedRunId = object.has("run_id") ? object.get("run_id").getAsString() : null;
        savedFingerprint = object.has("fingerprint") ? object.get("fingerprint").getAsString() : null;
    }

    /**
     * Entry point for screen patches. The patch must call this only after a
     * user-facing choice has been accepted, not for a visual hover or a
     * low-level effect.
     */
    public synchronized void recordAction(String id, String kind, String details) {
        if (!captureMode.enabled()) {
            return;
        }
        if (transactionStack.isEmpty()) beginDecision();
        recordActionFromSnapshot(id, kind, details);
    }

    /** Called at the accepted skip-button branch, before the reward screen closes. */
    public synchronized void recordCardRewardSkip() {
        if (!captureMode.enabled()) return;
        recordActionFromSnapshot("SKIP", "card_reward_skipped", "");
    }

    /** Discovery potions/cards and Choose One cards select options without acquireCard(). */
    public synchronized void recordCardRewardOption(CardRewardScreen screen, AbstractCard card, String mode) {
        if (!captureMode.enabled() || screen == null || card == null
                || screen.rewardGroup == null
                || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.CARD_REWARD) return;
        int index = screen.rewardGroup.indexOf(card);
        recordActionFromSnapshot(index < 0 ? "CHOOSE:card_id=" + card.cardID : "CHOOSE:index=" + index,
                "card_option_selected",
                "\"selection_mode\":" + quote(mode)
                        + ",\"card_id\":" + quote(card.cardID)
                        + ",\"card_name\":" + quote(card.name)
                        + ",\"card_uuid\":" + quote(String.valueOf(card.uuid)));
    }

    private void recordActionFromSnapshot(String id, String kind, String details) {
        acceptActionFromSnapshot(id, kind, details, false);
    }

    private String acceptActionFromSnapshot(String id, String kind, String details, boolean tracked) {
        if (transactionStack.isEmpty()) {
            beginDecision();
        }
        String transactionId = transactionStack.pop();
        String selected = "{\"id\":" + quote(id)
                + ",\"kind\":" + quote(kind)
                + (details == null || details.length() == 0 ? "" : "," + details)
                + "}";
        emit("action_accepted", "\"transaction_id\":" + quote(transactionId)
                + ",\"execution_tracking\":" + tracked
                + ",\"action\":" + selected
                + ",\"chosen_action\":" + selected);
        ActionToastOverlay toast = actionToast;
        if (toast != null) {
            toast.show(id, kind);
        }
        return transactionId;
    }

    public synchronized void recordPurchasedPotion(com.megacrit.cardcrawl.potions.AbstractPotion potion, int price) {
        recordAction("SHOP:BUY_POTION:" + potion.ID, "shop_potion_purchased",
                "\"potion_id\":" + quote(potion.ID)
                        + ",\"potion_name\":" + quote(potion.name) + ",\"price\":" + price);
    }

    /** Call at the entry of a player action, before the game mutates its state. */
    public synchronized void beginDecision() {
        if (captureMode.enabled()) {
            // Snapshot at the action boundary even if the human acts twice in
            // one frame. Publication uses CommunicationMod's script channel;
            // no elapsed-time matching or full serialization every frame.
            publishedStateId = null;
            stateBridge.publish();
            String transactionId = recorderSession + ":tx-" + UUID.randomUUID().toString();
            transactionStack.push(transactionId);
            emit("action_begin", "\"transaction_id\":" + quote(transactionId)
                    + ",\"before_state_id\":" + quote(publishedStateId)
                    + ",\"expected_screen\":" + quote(String.valueOf(AbstractDungeon.screen))
                    + ",\"context\":" + contextJson());
        }
    }

    public synchronized void discardDecision() {
        if (!transactionStack.isEmpty()) {
            String transactionId = transactionStack.pop();
            emit("action_rejected", "\"transaction_id\":" + quote(transactionId)
                    + ",\"reason\":\"game_rejected_or_cancelled\"");
        }
    }

    public synchronized int decisionDepth() { return transactionStack.size(); }

    public synchronized void discardDecisionsAfter(int depth) {
        while (transactionStack.size() > depth) discardDecision();
    }

    public synchronized void recordRawInput(String inputType, String details) {
        if (!captureMode.includesRawInput()) {
            return;
        }
        emit("raw_input", "\"input_type\":" + quote(inputType)
                + ",\"context\":" + contextJson()
                + (details == null || details.length() == 0 ? "" : "," + details));
    }

    private String contextJson() {
        if (AbstractDungeon.player == null) {
            return "{\"in_game\":false}";
        }
        String room = "";
        try {
            AbstractRoom current = AbstractDungeon.getCurrRoom();
            room = current == null ? "" : current.getClass().getName();
        } catch (Throwable ignored) {
        }
        int turn = AbstractDungeon.actionManager == null ? -1 : AbstractDungeon.actionManager.turn;
        return "{"
                + "\"in_game\":true"
                + ",\"character\":" + quote(AbstractDungeon.player.chosenClass.name())
                + ",\"ascension\":" + AbstractDungeon.ascensionLevel
                + ",\"act\":" + AbstractDungeon.actNum
                + ",\"floor\":" + AbstractDungeon.floorNum
                + ",\"screen\":" + quote(String.valueOf(AbstractDungeon.screen))
                + ",\"room\":" + quote(room)
                + ",\"turn\":" + turn
                + "}";
    }

    public synchronized void update() {
        if (!captureMode.enabled()) {
            return;
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.DEATH) {
            markRunTerminal("death");
        } else if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.VICTORY) {
            markRunTerminal("victory");
        }
        boolean dungeon = false;
        try {
            dungeon = AbstractDungeon.isPlayerInDungeon();
        } catch (Throwable ignored) {
            // PostUpdate also runs while dungeon globals are being initialized.
        }

        if (!dungeon || AbstractDungeon.player == null) {
            // Leaving an Act is not leaving the run. Dungeon globals are
            // temporarily unavailable during the inter-Act transition.
            if (inRun && !terminalRun
                    && (com.megacrit.cardcrawl.core.CardCrawlGame.mode
                        == com.megacrit.cardcrawl.core.CardCrawlGame.GameMode.DUNGEON_TRANSITION
                    || com.megacrit.cardcrawl.core.CardCrawlGame.mode
                        == com.megacrit.cardcrawl.core.CardCrawlGame.GameMode.GAMEPLAY)) return;
            if (inRun) {
                boolean dead = AbstractDungeon.player != null
                        && AbstractDungeon.player.currentHealth <= 0;
                boolean terminal = terminalRun || dead;
                if (dead && !terminalRun) {
                    terminalReason = "death";
                }
                String reason = terminalReason == null ? "dungeon_left" : terminalReason;
                cancelPendingQueued("dungeon_left_before_execution");
                emit("run_ended", "\"reason\":" + quote(reason) + ",\"terminal\":" + terminal);
                if (terminal) {
                    savedRunId = null;
                    savedFingerprint = null;
                }
            }
            resetRunState();
            return;
        }

        if (!inRun) {
            startOrResumeRun();
            inRun = true;
            lastTurn = -1;
            lastRoom = "";
            lastGridSelectionSignature = "";
            lastHandSelectionSignature = "";
            emit("run_started", "\"character\":" + quote(AbstractDungeon.player.chosenClass.name())
                    + ",\"act\":" + AbstractDungeon.actNum
                    + ",\"ascension\":" + AbstractDungeon.ascensionLevel
                    + ",\"seed\":" + quote(String.valueOf(com.megacrit.cardcrawl.core.Settings.seed)));
        }

        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) {
            lastGridSelectionSignature = "";
            gridPreviewActive = false;
        }
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.HAND_SELECT) {
            lastHandSelectionSignature = "";
        }

        observeRoom();
        observeCombat();
        observeQueuedDecisions();
    }

    /** Called only on messages actually passed to CommunicationMod.sendMessage. */
    public synchronized String capturePublishedState(String message) {
        try {
            JsonElement parsed = new JsonParser().parse(message);
            if (!parsed.isJsonObject()) return message;
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("in_game") || root.has("error")) return message;
            // Initialize metadata before the first boundary snapshot is saved.
            if (root.get("in_game").getAsBoolean() && !inRun
                    && AbstractDungeon.player != null && AbstractDungeon.isPlayerInDungeon()) update();
            publishedStateId = recorderSession + ":state-" + (++publishedStateSeq);
            root.addProperty("recorder_state_id", publishedStateId);
            root.addProperty("recorder_state_seq", publishedStateSeq);
            root.addProperty("recorder_session", recorderSession);
            JsonObject saved = new JsonParser().parse(root.toString()).getAsJsonObject();
            if (saved.has("game_state") && saved.get("game_state").isJsonObject()) {
                JsonObject state = saved.getAsJsonObject("game_state");
                state.remove("seed");
                if (!state.has("screen_type") || state.get("screen_type").isJsonNull()
                        || !"MAP".equals(state.get("screen_type").getAsString())) {
                    state.add("map", com.google.gson.JsonNull.INSTANCE);
                }
            }
            latestFrameSnapshot = saved;
            AvailableActionsOverlay overlay = availableActionsOverlay;
            if (overlay != null) overlay.setSnapshot(saved);
            if (captureMode.enabled()) emit("state_published", "\"state_id\":" + quote(publishedStateId)
                    + ",\"state_seq\":" + publishedStateSeq + ",\"message\":" + saved.toString());
            return root.toString();
        } catch (Throwable exc) {
            System.err.println("[ActionRecorder] cannot capture published state: " + exc);
            return message;
        }
    }

    private void observeRoom() {
        AbstractRoom room;
        try {
            room = AbstractDungeon.getCurrRoom();
        } catch (Throwable ignored) {
            return;
        }
        if (room == null) {
            return;
        }
        String roomName = room.getClass().getName() + ":" + AbstractDungeon.actNum
                + ":" + AbstractDungeon.floorNum;
        if (!roomName.equals(lastRoom)) {
            lastRoom = roomName;
            emit("room_changed", "\"room_class\":" + quote(room.getClass().getName())
                    + ",\"floor\":" + AbstractDungeon.floorNum
                    + ",\"act\":" + AbstractDungeon.actNum);
        }
    }

    private void observeCombat() {
        if (AbstractDungeon.actionManager == null) {
            return;
        }
        int turn = AbstractDungeon.actionManager.turn;
        if (turn != lastTurn) {
            if (lastTurn >= 0 && turn > lastTurn) {
                emit("combat_turn_changed", "\"turn\":" + turn);
            }
            lastTurn = turn;
        }

    }

    /** Records a card at the semantic point where the game queues it. */
    public synchronized void recordCardQueued(CardQueueItem item) {
        if (!captureMode.enabled() || item == null || item.card == null) {
            return;
        }
        AbstractCard card = item.card;
        int handIndex = -1;
        if (AbstractDungeon.player != null && AbstractDungeon.player.hand != null) {
            handIndex = AbstractDungeon.player.hand.group.indexOf(card);
        }
        int targetIndex = -1;
        String targetId = null;
        if (item.monster != null && AbstractDungeon.getCurrRoom() != null
                && AbstractDungeon.getCurrRoom().monsters != null
                && AbstractDungeon.getCurrRoom().monsters.monsters != null) {
            targetIndex = AbstractDungeon.getCurrRoom().monsters.monsters.indexOf(item.monster);
            targetId = item.monster.id;
        }
        String id = "PLAY:card=" + (handIndex < 0 ? "?" : String.valueOf(handIndex + 1))
                + (targetIndex < 0 ? "" : ":target=" + targetIndex);
        String details = "\"card_id\":" + quote(card.cardID)
                + ",\"card_name\":" + quote(card.name)
                + ",\"card_uuid\":" + quote(String.valueOf(card.uuid))
                + ",\"hand_index\":" + handIndex
                + ",\"target_index\":" + targetIndex
                + ",\"target_id\":" + quote(targetId)
                + ",\"energy_on_use\":" + item.energyOnUse
                + ",\"autoplay\":" + item.autoplayCard;
        if (item.autoplayCard) { discardDecision(); return; }
        String transactionId = acceptActionFromSnapshot(id, "play_card", details, true);
        queuedDecisions.register(item, transactionId, com.megacrit.cardcrawl.actions.GameActionManager.turn);
    }

    public synchronized void recordEndTurnQueued() {
        if (!captureMode.enabled()) return;
        String transactionId = acceptActionFromSnapshot("END_TURN", "end_turn",
                "\"turn\":" + com.megacrit.cardcrawl.actions.GameActionManager.turn, true);
        queuedDecisions.register(endTurnToken, transactionId,
                com.megacrit.cardcrawl.actions.GameActionManager.turn);
    }

    /** At getNextAction entry, before queue validation, counters, powers or RNG change. */
    public synchronized void beforeQueueExecution(com.megacrit.cardcrawl.actions.GameActionManager manager) {
        if (!captureMode.enabled() || manager == null || manager.currentAction != null
                || !manager.actions.isEmpty() || !manager.preTurnActions.isEmpty()) return;
        QueuedDecisionTracker.Entry<Object> active = queuedDecisions.active();
        if (active != null && active.dispatched) {
            // End-turn includes the enemy turn and next turn's draw/energy.
            if (active.item == endTurnToken
                    && com.megacrit.cardcrawl.actions.GameActionManager.turn <= active.turn) return;
            settleActiveExecution();
        }
        if (queuedDecisions.active() != null || manager.cardQueue.isEmpty()) return;
        CardQueueItem head = manager.cardQueue.get(0);
        Object key = head.card == null ? endTurnToken : head;
        if (!queuedDecisions.hasPending(key)) return; // Engine autoplay is not a human decision.
        QueuedDecisionTracker.Entry<Object> entry = queuedDecisions.begin(key);
        publishedStateId = null;
        stateBridge.publish();
        emit("action_execution_begin", "\"transaction_id\":" + quote(entry.transactionId)
                + ",\"execution_before_state_id\":" + quote(publishedStateId)
                + ",\"action\":" + executionActionJson(head)
                + ",\"logical_boundary\":true,\"context\":" + contextJson());
    }

    private String executionActionJson(CardQueueItem item) {
        if (item.card == null) return "{\"id\":\"END_TURN\",\"kind\":\"end_turn\"}";
        int index = AbstractDungeon.player.hand.group.indexOf(item.card);
        int target = item.monster == null ? -1 : AbstractDungeon.getCurrRoom().monsters.monsters.indexOf(item.monster);
        String id = "PLAY:card=" + (index < 0 ? "?" : String.valueOf(index + 1))
                + (target < 0 ? "" : ":target=" + target);
        return "{\"id\":" + quote(id) + ",\"kind\":\"play_card\",\"card_uuid\":"
                + quote(String.valueOf(item.card.uuid)) + ",\"card_id\":" + quote(item.card.cardID)
                + ",\"target_index\":" + target + ",\"target_id\":"
                + quote(item.monster == null ? null : item.monster.id) + "}";
    }

    /** Only the actual useCard branch proves that a queued card was executed. */
    public synchronized void cardExecutionDispatched(AbstractCard card) {
        QueuedDecisionTracker.Entry<Object> active = queuedDecisions.active();
        if (active != null && active.item instanceof CardQueueItem
                && ((CardQueueItem) active.item).card == card) dispatchActiveExecution();
    }

    public synchronized void endTurnExecutionDispatched() {
        QueuedDecisionTracker.Entry<Object> active = queuedDecisions.active();
        if (active != null && active.item == endTurnToken) dispatchActiveExecution();
    }

    private void dispatchActiveExecution() {
        QueuedDecisionTracker.Entry<Object> active = queuedDecisions.active();
        if (active == null || active.dispatched) return;
        active.dispatched = true;
        emit("action_execution_result", "\"transaction_id\":" + quote(active.transactionId)
                + ",\"status\":\"executed\"");
    }

    public synchronized void afterQueueExecution(com.megacrit.cardcrawl.actions.GameActionManager manager) {
        QueuedDecisionTracker.Entry<Object> active = queuedDecisions.active();
        if (active == null || active.dispatched) return;
        if (active.item instanceof CardQueueItem && manager.cardQueue.contains(active.item)) return;
        queuedDecisions.finishActive();
        emitExecutionCancelled(active, "skipped", "queue_validation_failed");
    }

    private void settleActiveExecution() {
        QueuedDecisionTracker.Entry<Object> active = queuedDecisions.finishActive();
        if (active == null) return;
        publishedStateId = null;
        stateBridge.publish();
        emit("action_effects_settled", "\"transaction_id\":" + quote(active.transactionId)
                + ",\"after_state_id\":" + quote(publishedStateId) + ",\"logical_boundary\":true");
    }

    private void emitExecutionCancelled(QueuedDecisionTracker.Entry<Object> entry, String status, String reason) {
        emit("action_execution_result", "\"transaction_id\":" + quote(entry.transactionId)
                + ",\"status\":" + quote(status) + ",\"reason\":" + quote(reason));
    }

    private void cancelPendingQueued(String reason) {
        for (QueuedDecisionTracker.Entry<Object> entry : queuedDecisions.removeMissing(
                java.util.Collections.emptyList())) emitExecutionCancelled(entry, "cancelled", reason);
        QueuedDecisionTracker.Entry<Object> active = queuedDecisions.finishActive();
        if (active != null && !active.dispatched) emitExecutionCancelled(active, "cancelled", reason);
    }

    /** Lightweight identity checks only: no per-frame state serialization. */
    private void observeQueuedDecisions() {
        if (queuedDecisions.active() == null && !queuedDecisions.hasPending()) return;
        com.megacrit.cardcrawl.actions.GameActionManager manager = AbstractDungeon.actionManager;
        if (manager == null) return;
        QueuedDecisionTracker.Entry<Object> active = queuedDecisions.active();
        boolean combatEnded = terminalRun || AbstractDungeon.getCurrRoom() == null
                || AbstractDungeon.getCurrRoom().phase != AbstractRoom.RoomPhase.COMBAT;
        if (active != null && active.dispatched && combatEnded) settleActiveExecution();
        if (!queuedDecisions.hasPending()) return;
        java.util.ArrayList<Object> live = new java.util.ArrayList<Object>(manager.cardQueue);
        if (!combatEnded) live.add(endTurnToken);
        for (QueuedDecisionTracker.Entry<Object> missing : queuedDecisions.removeMissing(live)) {
            emitExecutionCancelled(missing, "cancelled", "queue_removed_without_execution");
        }
    }

    /** Emits selection changes once, preserving multi-card interactions. */
    public synchronized void recordGridCardSelection(Object list, Object candidate) {
        GridCardSelectScreen screen = AbstractDungeon.gridSelectScreen;
        if (!captureMode.enabled() || screen == null || list != screen.selectedCards
                || !(candidate instanceof AbstractCard)
                || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) return;
        AbstractCard card = (AbstractCard) candidate;
        java.util.ArrayList<AbstractCard> selected = new java.util.ArrayList<AbstractCard>(screen.selectedCards);
        selected.add(card);
        StringBuilder signature = new StringBuilder();
        for (AbstractCard item : selected) {
            if (item != null) signature.append(item.uuid).append(';');
        }
        String value = signature.toString();
        if (value.equals(lastGridSelectionSignature)) return;
        recordActionFromSnapshot("SELECT_CARDS:grid:" + value, "card_selection_changed",
                "\"screen\":\"grid\",\"selected_cards\":" + cardListJson(selected)
                        + ",\"for_upgrade\":" + screen.forUpgrade
                        + ",\"for_transform\":" + screen.forTransform
                        + ",\"for_purge\":" + screen.forPurge
                        + ",\"for_clarity\":" + screen.forClarity);
        lastGridSelectionSignature = value;
    }

    /** Emits selection changes once, preserving multi-card interactions. */
    public synchronized boolean recordGridSelection(GridCardSelectScreen screen) {
        if (!captureMode.enabled() || screen == null || screen.selectedCards == null || screen.confirmScreenUp) {
            return false;
        }
        StringBuilder signature = new StringBuilder();
        for (AbstractCard card : screen.selectedCards) {
            if (card != null) {
                signature.append(card.uuid).append(';');
            }
        }
        String value = signature.toString();
        if (value.equals(lastGridSelectionSignature)) {
            return false;
        }
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) return false;
        // An empty selection when a new grid opens is its initial state.
        // An empty set after a preview's Return is automatic UI cleanup, not
        // another player choice. The Return itself is recorded separately.
        if (value.isEmpty() && (lastGridSelectionSignature.isEmpty() || gridPreviewActive)) {
            lastGridSelectionSignature = "";
            gridPreviewActive = false;
            return false;
        }
        lastGridSelectionSignature = value;
        recordAction("SELECT_CARDS:grid:" + value, "card_selection_changed",
                "\"screen\":\"grid\",\"selected_cards\":" + cardListJson(screen.selectedCards)
                        + ",\"for_upgrade\":" + screen.forUpgrade
                        + ",\"for_transform\":" + screen.forTransform
                        + ",\"for_purge\":" + screen.forPurge
                        + ",\"for_clarity\":" + screen.forClarity);
        return true;
    }

    /** Upgrade/transform previews do not yet add the card to selectedCards. */
    public synchronized void recordGridPreview(GridCardSelectScreen screen) {
        if (screen == null || screen.confirmScreenUp) return;
        AbstractCard card;
        try {
            java.lang.reflect.Field hovered = GridCardSelectScreen.class.getDeclaredField("hoveredCard");
            hovered.setAccessible(true);
            card = (AbstractCard) hovered.get(screen);
        } catch (ReflectiveOperationException exc) { return; }
        if (card == null) return;
        gridPreviewActive = true;
        String signature = String.valueOf(card.uuid) + ";";
        if (signature.equals(lastGridSelectionSignature)) return;
        recordAction("SELECT_CARDS:grid:" + signature, "card_selection_changed",
                "\"screen\":\"grid\",\"card_uuid\":" + quote(String.valueOf(card.uuid))
                        + ",\"selected_cards\":" + cardListJson(java.util.Collections.singletonList(card))
                        + ",\"for_upgrade\":" + screen.forUpgrade
                        + ",\"for_transform\":" + screen.forTransform
                        + ",\"for_purge\":" + screen.forPurge);
        lastGridSelectionSignature = signature;
    }

    public synchronized boolean recordHandSelection(HandCardSelectScreen screen) {
        return recordHandSelection(screen, null);
    }

    public synchronized boolean recordHandSelection(HandCardSelectScreen screen, String cardUuid) {
        if (!captureMode.enabled() || screen == null || screen.selectedCards == null) {
            return false;
        }
        StringBuilder signature = new StringBuilder();
        for (AbstractCard card : screen.selectedCards.group) {
            if (card != null) {
                signature.append(card.uuid).append(';');
            }
        }
        String value = signature.toString();
        if (value.equals(lastHandSelectionSignature)) {
            return false;
        }
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.HAND_SELECT) return false;
        // Keep a real deselection, but not the empty baseline of a new screen.
        if (value.isEmpty() && lastHandSelectionSignature.isEmpty()) return false;
        lastHandSelectionSignature = value;
        recordAction("SELECT_CARDS:hand:" + value, "card_selection_changed",
                "\"screen\":\"hand\",\"card_uuid\":" + quote(cardUuid)
                        + ",\"selected_cards\":" + cardListJson(screen.selectedCards.group));
        return true;
    }

    public synchronized void recordHandDeselection(Object group, AbstractCard card) {
        HandCardSelectScreen screen = AbstractDungeon.handCardSelectScreen;
        if (screen == null || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.HAND_SELECT
                || AbstractDungeon.player == null || group != AbstractDungeon.player.hand
                || !screen.selectedCards.group.contains(card)) return;
        java.util.ArrayList<AbstractCard> selected = new java.util.ArrayList<AbstractCard>(screen.selectedCards.group);
        selected.remove(card);
        StringBuilder signature = new StringBuilder();
        for (AbstractCard item : selected) signature.append(item.uuid).append(';');
        recordAction("SELECT_CARDS:hand:" + signature, "card_selection_changed",
                "\"screen\":\"hand\",\"operation\":\"deselect\",\"card_uuid\":" + quote(String.valueOf(card.uuid))
                        + ",\"selected_cards\":" + cardListJson(selected));
        lastHandSelectionSignature = signature.toString();
    }

    /** Called immediately after the confirm button handles this frame, before the grid screen clears its click flag. */
    public synchronized void recordGridConfirmation(GridSelectConfirmButton button) {
        if (!captureMode.enabled() || button == null || button.hb == null
                || !button.hb.clicked || button.isDisabled
                || AbstractDungeon.gridSelectScreen == null
                || AbstractDungeon.gridSelectScreen.selectedCards == null) {
            return;
        }
        GridCardSelectScreen screen = AbstractDungeon.gridSelectScreen;
        recordAction("SELECT_CARDS:CONFIRM", "card_selection_confirmed",
                "\"screen\":\"grid\",\"selected_cards\":" + cardListJson(screen.selectedCards)
                        + ",\"for_upgrade\":" + screen.forUpgrade
                        + ",\"for_transform\":" + screen.forTransform
                        + ",\"for_purge\":" + screen.forPurge
                        + ",\"for_clarity\":" + screen.forClarity);
    }

    /** Hand effects such as Armaments use a separate screen and confirm button from grid selection. */
    public synchronized void recordHandConfirmation(CardSelectConfirmButton button) {
        HandCardSelectScreen screen = AbstractDungeon.handCardSelectScreen;
        if (!captureMode.enabled() || button == null || button.hb == null
                || !button.hb.clicked || button.isDisabled || screen == null
                || screen.selectedCards == null || screen.selectedCards.group == null) {
            return;
        }
        recordAction("SELECT_CARDS:HAND_CONFIRM", "card_selection_confirmed",
                "\"screen\":\"hand\",\"selected_cards\":"
                        + cardListJson(screen.selectedCards.group));
    }

    public synchronized void recordPotionAction(String id, String kind, int slot,
                                                 com.megacrit.cardcrawl.potions.AbstractPotion potion) {
        if (!captureMode.enabled()) {
            return;
        }
        String potionId = potion == null ? null : potion.ID;
        String potionName = potion == null ? null : potion.name;
        recordAction(id + ":slot=" + slot, kind,
                "\"slot\":" + slot + ",\"potion_id\":" + quote(potionId)
                        + ",\"potion_name\":" + quote(potionName));
    }

    public synchronized void recordPotionUseTarget(int slot,
                                                    com.megacrit.cardcrawl.potions.AbstractPotion potion,
                                                    AbstractMonster monster) {
        if (!captureMode.enabled()) {
            return;
        }
        int targetIndex = -1;
        if (monster != null && AbstractDungeon.getCurrRoom() != null
                && AbstractDungeon.getCurrRoom().monsters != null
                && AbstractDungeon.getCurrRoom().monsters.monsters != null) {
            targetIndex = AbstractDungeon.getCurrRoom().monsters.monsters.indexOf(monster);
        }
        String potionId = potion == null ? null : potion.ID;
        String potionName = potion == null ? null : potion.name;
        recordAction("POTION_USE:slot=" + slot + ":target=" + targetIndex,
                "potion_use_requested",
                "\"slot\":" + slot + ",\"potion_id\":" + quote(potionId)
                        + ",\"potion_name\":" + quote(potionName)
                        + ",\"target_index\":" + targetIndex
                        + ",\"target_id\":" + quote(monster == null ? null : monster.id));
    }

    private String cardListJson(List<AbstractCard> cards) {
        StringBuilder value = new StringBuilder("[");
        if (cards != null) {
            for (int index = 0; index < cards.size(); index++) {
                if (index > 0) {
                    value.append(',');
                }
                AbstractCard card = cards.get(index);
                value.append("{\"card_id\":").append(quote(card == null ? null : card.cardID))
                        .append(",\"card_name\":").append(quote(card == null ? null : card.name))
                        .append(",\"card_uuid\":").append(quote(card == null ? null : String.valueOf(card.uuid)))
                        .append('}');
            }
        }
        return value.append(']').toString();
    }

    private void resetRunState() {
        inRun = false;
        terminalRun = false;
        terminalReason = null;
        lastTurn = -1;
        lastRoom = "";
        lastGridSelectionSignature = "";
        gridPreviewActive = false;
        lastHandSelectionSignature = "";
        activeRunId = null;
        activeFile = null;
        latestFrameSnapshot = null;
        transactionStack.clear();
        queuedDecisions.clear();
    }

    private void startOrResumeRun() {
        String fingerprint = fingerprint();
        if (savedRunId == null || savedFingerprint == null || !savedFingerprint.equals(fingerprint)) {
            savedRunId = UUID.randomUUID().toString();
            savedFingerprint = fingerprint;
        }
        activeRunId = savedRunId;
        activeFile = new File(
                eventsDirectory,
                "run-" + sanitize(activeRunId) + "-" + sanitize(fingerprint) + ".jsonl"
        ).getPath();
    }

    private String fingerprint() {
        String character = AbstractDungeon.player == null || AbstractDungeon.player.chosenClass == null
                ? "UNKNOWN" : AbstractDungeon.player.chosenClass.name();
        return character + "-A" + AbstractDungeon.ascensionLevel
                + "-seed-" + String.valueOf(com.megacrit.cardcrawl.core.Settings.seed);
    }

    private void emit(String type, String payload) {
        long now = System.currentTimeMillis();
        String message = "{"
                + "\"schema_version\":" + quote(SCHEMA_VERSION)
                + ",\"mod_version\":" + quote(MOD_VERSION)
                + ",\"recorder_session\":" + quote(recorderSession)
                + ",\"capture_mode\":" + quote(captureMode.value)
                + ",\"event_seq\":" + (++eventSeq)
                + ",\"timestamp_ms\":" + now
                + ",\"type\":" + quote(type)
                + (activeRunId == null ? "" : ",\"run_id\":" + quote(activeRunId))
                + (payload == null || payload.length() == 0 ? "" : "," + payload)
                + "}";
        // PostUpdate and screen patches run on the game's update thread.
        // Never perform disk or socket I/O here.
        String path = activeFile == null
                ? new File(eventsDirectory, "session-" + recorderSession + ".jsonl").getPath()
                : activeFile;
        eventQueue.offer(new QueuedEvent(path, message));
    }

    private void openLocalFile() {
        File parent = new File(eventsDirectory);
        if (!parent.exists() && !parent.mkdirs()) {
            System.err.println("[ActionRecorder] cannot create event directory: " + parent);
        }
    }

    private void writerLoop() {
        openLocalFile();
        while (accepting || !eventQueue.isEmpty()) {
            try {
                QueuedEvent event = eventQueue.take();
                writeLocal(event);
                if (!tcpQueue.offer(event.message)) {
                    System.err.println("[ActionRecorder] TCP queue full; event retained in local journal");
                }
            } catch (InterruptedException exc) {
                // Shutdown interrupts the worker so it can drain the queue.
            }
        }
        closeLocalFiles();
    }

    private void writeLocal(QueuedEvent event) {
        try {
            BufferedWriter fileWriter = fileWriters.get(event.path);
            if (fileWriter == null) {
                File target = new File(event.path);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fileWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(target, true), StandardCharsets.UTF_8));
                fileWriters.put(event.path, fileWriter);
            }
            fileWriter.write(event.message);
            fileWriter.newLine();
            fileWriter.flush();
        } catch (IOException exc) {
            System.err.println("[ActionRecorder] local event file write failed: " + exc.getMessage());
        }
    }

    private void sendTcp(String message) {
        if (!ensureConnection()) {
            return;
        }
        try {
            writer.write(message);
            writer.newLine();
            writer.flush();
        } catch (IOException exc) {
            closeConnection();
            System.err.println("[ActionRecorder] action channel write failed: " + exc.getMessage());
        }
    }

    private boolean ensureConnection() {
        if (writer != null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now < nextConnectAt) {
            return false;
        }
        nextConnectAt = now + reconnectIntervalMs;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            String hello = "{"
                    + "\"schema_version\":" + quote(SCHEMA_VERSION)
                    + ",\"mod_version\":" + quote(MOD_VERSION)
                    + ",\"recorder_session\":" + quote(recorderSession)
                    + ",\"event_seq\":0"
                    + ",\"timestamp_ms\":" + System.currentTimeMillis()
                    + ",\"type\":\"hello\""
                    + ",\"host\":" + quote(host)
                    + ",\"port\":" + port
                    + "}";
            writer.write(hello);
            writer.newLine();
            writer.flush();
            System.err.println("[ActionRecorder] connected to " + host + ":" + port);
            return true;
        } catch (IOException exc) {
            closeConnection();
            return false;
        }
    }

    private void closeConnection() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        writer = null;
        socket = null;
    }

    private void closeLocalFiles() {
        for (BufferedWriter fileWriter : fileWriters.values()) {
            try {
                fileWriter.flush();
                fileWriter.close();
            } catch (IOException ignored) {
            }
        }
        fileWriters.clear();
    }

    private void shutdown() {
        accepting = false;
        writerThread.interrupt();
        try {
            writerThread.join(2000L);
            senderThread.join(1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String property(String suffix, String fallback) {
        String value = System.getProperty("actionrecorder." + suffix);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int integerProperty(String suffix, int fallback) {
        try {
            return Integer.parseInt(property(suffix, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private enum CaptureMode {
        OFF("off", false, false),
        GAME_ACTIONS("game_actions", true, false),
        RAW_INPUT("raw_input", true, true);

        private final String value;
        private final boolean enabled;
        private final boolean rawInput;

        CaptureMode(String value, boolean enabled, boolean rawInput) {
            this.value = value;
            this.enabled = enabled;
            this.rawInput = rawInput;
        }

        private boolean enabled() {
            return enabled;
        }

        private boolean includesRawInput() {
            return rawInput;
        }

        private static CaptureMode from(String value) {
            for (CaptureMode mode : values()) {
                if (mode.value.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            System.err.println("[ActionRecorder] unknown capture mode '" + value
                    + "', using game_actions");
            return GAME_ACTIONS;
        }
    }

    private static final class QueuedEvent {
        private final String path;
        private final String message;

        private QueuedEvent(String path, String message) {
            this.path = path;
            this.message = message;
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default: result.append(character);
            }
        }
        return result.append('"').toString();
    }
}
