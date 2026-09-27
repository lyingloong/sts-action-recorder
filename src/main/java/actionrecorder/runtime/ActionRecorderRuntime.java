package actionrecorder.runtime;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Game-side raw event recorder. Raw input is the primary data source; semantic
 * screen patches are optional annotations for downstream consumers.
 */
public final class ActionRecorderRuntime {
    private static final ActionRecorderRuntime INSTANCE = new ActionRecorderRuntime();
    private static final String MOD_VERSION = "0.1.0";
    private static final String SCHEMA_VERSION = "0.1";

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final long reconnectIntervalMs;
    private final String eventsDirectory;
    private final CaptureMode captureMode;
    private final String recorderSession = UUID.randomUUID().toString();
    private final BlockingQueue<QueuedEvent> eventQueue = new LinkedBlockingQueue<QueuedEvent>();
    private volatile boolean accepting = true;
    private final Thread writerThread;

    private Socket socket;
    private BufferedWriter writer;
    private final Map<String, BufferedWriter> fileWriters = new HashMap<String, BufferedWriter>();
    private long nextConnectAt;
    private long eventSeq;
    private boolean inRun;
    private int lastTurn = -1;
    private String lastRoom = "";
    private int lastPlayedCardCount;
    private String savedRunId;
    private String savedFingerprint;
    private String activeRunId;
    private String activeFile;

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
        writerThread.start();
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
        String payload = "\"action\":{"
                + "\"id\":" + quote(id)
                + ",\"kind\":" + quote(kind)
                + (details == null || details.length() == 0 ? "" : "," + details)
                + "}";
        emit("action_observed", payload);
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
        boolean dungeon = false;
        try {
            dungeon = AbstractDungeon.isPlayerInDungeon();
        } catch (Throwable ignored) {
            // PostUpdate also runs while dungeon globals are being initialized.
        }

        if (!dungeon || AbstractDungeon.player == null) {
            if (inRun) {
                boolean terminal = AbstractDungeon.is_victory
                        || AbstractDungeon.isDungeonBeaten
                        || AbstractDungeon.player.currentHealth <= 0;
                emit("run_ended", "\"reason\":\"dungeon_left\",\"terminal\":" + terminal);
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
            lastPlayedCardCount = 0;
            emit("run_started", "\"run_id\":" + quote(activeRunId)
                    + ",\"character\":" + quote(AbstractDungeon.player.chosenClass.name())
                    + ",\"act\":" + AbstractDungeon.actNum
                    + ",\"ascension\":" + AbstractDungeon.ascensionLevel
                    + ",\"seed\":" + quote(String.valueOf(com.megacrit.cardcrawl.core.Settings.seed)));
        }

        observeRoom();
        observeCombat();
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
        String roomName = room.getClass().getName();
        if (!roomName.equals(lastRoom)) {
            lastRoom = roomName;
            emit("room_changed", "\"room_class\":" + quote(roomName)
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

        List<AbstractCard> played = AbstractDungeon.actionManager.cardsPlayedThisTurn;
        if (played == null) {
            return;
        }
        if (played.size() < lastPlayedCardCount) {
            lastPlayedCardCount = played.size();
        }
        for (int index = lastPlayedCardCount; index < played.size(); index++) {
            AbstractCard card = played.get(index);
            if (card == null) {
                continue;
            }
            String payload = "\"action\":{"
                    + "\"id\":" + quote("PLAY:card_uuid=" + card.uuid)
                    + ",\"kind\":\"play_card\""
                    + ",\"card_id\":" + quote(card.cardID)
                    + ",\"card_name\":" + quote(card.name)
                    + ",\"card_uuid\":" + quote(String.valueOf(card.uuid))
                    + ",\"turn\":" + turn
                    + "}";
            emit("action_observed", payload);
        }
        lastPlayedCardCount = played.size();
    }

    private void resetRunState() {
        inRun = false;
        lastTurn = -1;
        lastRoom = "";
        lastPlayedCardCount = 0;
        activeRunId = null;
        activeFile = null;
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
                sendTcp(event.message);
            } catch (InterruptedException exc) {
                // Shutdown interrupts the worker so it can drain the queue.
            }
        }
        closeConnection();
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
