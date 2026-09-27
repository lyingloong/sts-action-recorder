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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Game-side event recorder. It observes player-level outcomes and deliberately
 * does not treat every queued GameAction as a user decision.
 */
public final class ActionRecorderRuntime {
    private static final ActionRecorderRuntime INSTANCE = new ActionRecorderRuntime();
    private static final String MOD_VERSION = "0.1.0";
    private static final String SCHEMA_VERSION = "0.1";

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final long reconnectIntervalMs;
    private final String eventsFile;
    private final String recorderSession = UUID.randomUUID().toString();
    private final BlockingQueue<String> eventQueue = new LinkedBlockingQueue<String>();
    private volatile boolean accepting = true;
    private final Thread writerThread;

    private Socket socket;
    private BufferedWriter writer;
    private BufferedWriter fileWriter;
    private long nextConnectAt;
    private long eventSeq;
    private boolean inRun;
    private int lastTurn = -1;
    private String lastRoom = "";
    private int lastPlayedCardCount;

    private ActionRecorderRuntime() {
        host = property("host", "127.0.0.1");
        port = integerProperty("port", 8766);
        connectTimeoutMs = integerProperty("connect_timeout_ms", 250);
        reconnectIntervalMs = integerProperty("reconnect_interval_ms", 1000);
        eventsFile = property("events_file", "data/actionrecorder-events.jsonl");
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

    /**
     * Entry point for screen patches. The patch must call this only after a
     * user-facing choice has been accepted, not for a visual hover or a
     * low-level effect.
     */
    public synchronized void recordAction(String id, String kind, String details) {
        String payload = "\"action\":{"
                + "\"id\":" + quote(id)
                + ",\"kind\":" + quote(kind)
                + (details == null || details.length() == 0 ? "" : "," + details)
                + "}";
        emit("action_observed", payload);
    }

    public synchronized void update() {
        boolean dungeon = false;
        try {
            dungeon = AbstractDungeon.isPlayerInDungeon();
        } catch (Throwable ignored) {
            // PostUpdate also runs while dungeon globals are being initialized.
        }

        if (!dungeon || AbstractDungeon.player == null) {
            if (inRun) {
                emit("run_ended", "\"reason\":\"dungeon_left\"");
            }
            resetRunState();
            return;
        }

        if (!inRun) {
            inRun = true;
            lastTurn = -1;
            lastRoom = "";
            lastPlayedCardCount = 0;
            emit("run_started", "\"character\":" + quote(AbstractDungeon.player.chosenClass.name())
                    + ",\"act\":" + AbstractDungeon.actNum);
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
    }

    private void emit(String type, String payload) {
        long now = System.currentTimeMillis();
        String message = "{"
                + "\"schema_version\":" + quote(SCHEMA_VERSION)
                + ",\"mod_version\":" + quote(MOD_VERSION)
                + ",\"recorder_session\":" + quote(recorderSession)
                + ",\"event_seq\":" + (++eventSeq)
                + ",\"timestamp_ms\":" + now
                + ",\"type\":" + quote(type)
                + (payload == null || payload.length() == 0 ? "" : "," + payload)
                + "}";
        // PostUpdate and screen patches run on the game's update thread.
        // Never perform disk or socket I/O here.
        eventQueue.offer(message);
    }

    private void openLocalFile() {
        try {
            File target = new File(eventsFile);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                System.err.println("[ActionRecorder] cannot create event directory: " + parent);
            }
            fileWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(target, true), StandardCharsets.UTF_8));
        } catch (IOException exc) {
            System.err.println("[ActionRecorder] local event file unavailable: " + exc.getMessage());
        }
    }

    private void writerLoop() {
        openLocalFile();
        while (accepting || !eventQueue.isEmpty()) {
            try {
                String message = eventQueue.take();
                writeLocal(message);
                sendTcp(message);
            } catch (InterruptedException exc) {
                // Shutdown interrupts the worker so it can drain the queue.
            }
        }
        closeConnection();
        closeLocalFile();
    }

    private void writeLocal(String message) {
        if (fileWriter == null) {
            return;
        }
        try {
            fileWriter.write(message);
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

    private void closeLocalFile() {
        if (fileWriter == null) {
            return;
        }
        try {
            fileWriter.flush();
            fileWriter.close();
        } catch (IOException ignored) {
        } finally {
            fileWriter = null;
        }
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
