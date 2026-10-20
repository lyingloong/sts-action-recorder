package actionrecorder;

import actionrecorder.runtime.CommandLedger;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Correlation/parser/stale-state tests without launching the game. */
public final class CommandLedgerSmoke {
    public static void main(String[] args) throws Exception {
        String[] live = {"{\"in_game\":true,\"hp\":80}"};
        CommandLedger ledger = new CommandLedger(() -> live[0]);
        ledger.observe("state1", "{\"in_game\":true,\"hp\":80}");
        ledger.validateState("{\"hp\":80,\"in_game\":true}");
        expectFailure(() -> ledger.validateState("{\"hp\":79,\"in_game\":true}"), "changed game state");
        check("STATE".equals(ledger.begin(wire("query1", "-", "STATE"))), "decode query");
        JsonObject root = new JsonObject(); ledger.stamp(root);
        check(root.get("recorder_command_protocol").getAsInt() == 1, "capability");
        check(root.getAsJsonObject("recorder_command").get("status").getAsString().equals("query"), "query receipt");
        ledger.finish();
        expectFailure(() -> ledger.begin(wire("query1", "-", "STATE")), "duplicate not replayed");
        expectFailure(() -> ledger.begin(wire("action1", "old", "PLAY 2 0")), "stale state rejected before executor");
        ledger.failed("stale"); ledger.finish();
        root = new JsonObject(); ledger.stamp(root);
        check(root.getAsJsonObject("recorder_command").get("id").getAsString().equals("action1"), "error correlation");
        check(root.getAsJsonObject("recorder_command").get("status").getAsString().equals("rejected"), "error receipt");
        check(root.getAsJsonObject("recorder_command").get("error_code").getAsString().equals("stale_observation"), "typed stale rejection");
        check(root.getAsJsonObject("recorder_command").get("execution_status").getAsString().equals("not_executed"), "stale guard precedes executor");
        check("PLAY 2 0".equals(ledger.begin(wire("action2", "state1", "PLAY 2 0"))), "fresh action decoded");
        ledger.link("tx2", true); ledger.accepted(); ledger.finish();
        root = new JsonObject(); ledger.stamp(root);
        check(root.getAsJsonObject("recorder_command").get("execution_status").getAsString().equals("pending"), "accepted is not executed");
        ledger.execution("other", "executed"); ledger.execution("tx2", "executed");
        root = new JsonObject(); ledger.stamp(root);
        check(root.getAsJsonObject("recorder_command").get("execution_status").getAsString().equals("executed"), "exact transaction execution");
        live[0] = "{\"in_game\":true,\"hp\":79}";
        expectFailure(() -> ledger.begin(wire("action3", "state1", "PLAY 2 0")), "live state changed despite matching ID");
        ledger.failed("state changed"); ledger.finish();
        root = new JsonObject(); ledger.stamp(root);
        check(root.getAsJsonObject("recorder_command").get("error_code").getAsString().equals("state_changed"), "typed state drift");
        check(root.getAsJsonObject("recorder_command").get("execution_status").getAsString().equals("not_executed"), "state drift not executed");
        live[0] = "{\"in_game\":true,\"hp\":80}";
        ledger.begin(wire("executor-failure", "state1", "PLAY 2 0"));
        ledger.failed("executor exception after possible side effects"); ledger.finish();
        root = new JsonObject(); ledger.stamp(root);
        check(root.getAsJsonObject("recorder_command").get("error_code").getAsString().equals("command_failed"), "executor failure distinct");
        check(root.getAsJsonObject("recorder_command").get("execution_status").getAsString().equals("unknown"), "executor failure never proves not executed");
        ledger.begin(wire("queued-failure", "state1", "PLAY 2 0"));
        ledger.link("queued-side-effect", true); ledger.failed("exception after queue submission"); ledger.finish();
        root = new JsonObject(); ledger.stamp(root);
        check(root.getAsJsonObject("recorder_command").get("execution_status").getAsString().equals("pending"), "queued effects are not mislabeled not executed");
        CommandLedger unavailable = new CommandLedger(() -> { throw new IllegalStateException("converter unavailable"); });
        unavailable.observe("state1", live[0]);
        expectFailure(() -> unavailable.begin(wire("unavailable", "state1", "END")), "unavailable state cannot execute");
        unavailable.failed("converter unavailable"); unavailable.finish();
        root = new JsonObject(); unavailable.stamp(root);
        check(root.getAsJsonObject("recorder_command").get("error_code").getAsString().equals("validation_unavailable"), "unknown validation is not recoverable drift");
        check("PLAY 1".equals(ledger.begin("PLAY 1")), "plain commands unchanged");
        check(ledger.activeId() == null, "human/plain input not falsely attributed");
        expectFailure(() -> ledger.begin("AR1 invalid! - ???"), "malformed packet rejected");
        expectFailure(() -> ledger.begin(wire("newline", "-", "STATE\nEND")), "multiple commands rejected");
        expectFailure(() -> ledger.begin(wire("carriage", "-", "STATE\rEND")), "embedded CR rejected");
        expectFailure(() -> ledger.begin(wire("badbase64", "-", "STATE") + "!\r"), "malformed Base64 still rejected");
        expectFailure(() -> ledger.begin(wire("joined", "-", "STATE") + "\n" + wire("second", "-", "STATE")),
                "multiple envelopes in one frame rejected");
        check("PLAY 1\r".equals(ledger.begin("PLAY 1\r")), "legacy framing unchanged");
        testFraming();
        testInstalledDataReader();
        System.out.println("PASS command correlation / duplicate rejection / stale state / legacy commands / CRLF framing");
    }

    private static void testFraming() {
        CommandLedger ledger = new CommandLedger(() -> "{\"in_game\":false}");
        ledger.observe("menu", "{\"in_game\":false}");
        String[] endings = {"", "\r", "\n", "\r\n"};
        for (int i = 0; i < endings.length; i++) {
            check("START IRONCLAD 0".equals(ledger.begin(wire("start" + i, "menu", "START IRONCLAD 0") + endings[i])),
                    "START transport ending " + i);
            ledger.accepted(); ledger.finish();
            check("STATE".equals(ledger.begin(wire("state" + i, "-", "STATE") + endings[i])),
                    "STATE transport ending " + i);
            ledger.finish();
        }
        check("STATE".equals(ledger.begin("  " + wire("outer", "-", "STATE") + " \r\n")), "outer framing whitespace");
    }

    /** Exercise the installed CM reader rather than assuming it removes CRLF. */
    private static void testInstalledDataReader() throws Exception {
        Class<?> readerType;
        try { readerType = Class.forName("communicationmod.DataReader"); }
        catch (ClassNotFoundException optionalDependencyAbsent) {
            System.out.println("SKIP installed CommunicationMod DataReader round-trip (jar absent)");
            return;
        }
        byte[] bytes = (wire("reader-start", "menu", "START IRONCLAD 0") + "\r\n"
                + wire("reader-state", "-", "STATE") + "\r\n").getBytes(StandardCharsets.UTF_8);
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        InputStream stream = new InputStream() {
            private int index;
            @Override public int read() {
                if (index < bytes.length) return bytes[index++] & 0xff;
                // CM loops on EOF. An empty NUL frame plus interrupt ends the test cleanly.
                Thread.currentThread().interrupt();
                return 0;
            }
        };
        Runnable reader = (Runnable) readerType.getConstructor(BlockingQueue.class, InputStream.class, boolean.class)
                .newInstance(queue, stream, false);
        Thread thread = new Thread(reader, "command-ledger-test-reader");
        thread.setDaemon(true);
        thread.start();
        thread.join(2000);
        check(!thread.isAlive(), "DataReader round-trip completed");
        check(queue.size() == 2, "DataReader produced exactly two frames");
        CommandLedger ledger = new CommandLedger(() -> "{\"in_game\":false}");
        ledger.observe("menu", "{\"in_game\":false}");
        check("START IRONCLAD 0".equals(ledger.begin(queue.remove())), "Windows START through actual DataReader");
        ledger.accepted(); ledger.finish();
        check("STATE".equals(ledger.begin(queue.remove())), "Windows STATE through actual DataReader");
        ledger.finish();
        System.out.println("PASS installed CommunicationMod DataReader CRLF round-trip");
    }
    private static String wire(String id, String state, String command) {
        return "AR1 " + id + " " + state + " " + Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));
    }
    private static void expectFailure(Runnable test, String why) {
        try { test.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(why);
    }
    private static void check(boolean result, String why) { if (!result) throw new AssertionError(why); }
}
