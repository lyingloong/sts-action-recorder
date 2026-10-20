package actionrecorder.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.function.Supplier;

/** Optional, correlated script commands. Plain CommunicationMod commands remain valid. */
public final class CommandLedger {
    public static final CommandLedger INSTANCE = new CommandLedger();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private String stateId;
    private JsonElement state;
    private JsonObject receipt;
    private boolean active;
    private final Supplier<String> liveState;

    public CommandLedger() { this(CommandLedger::readLiveState); }

    public CommandLedger(Supplier<String> liveState) { this.liveState = liveState; }

    private static String readLiveState() {
        try {
            return (String) Class.forName("communicationmod.GameStateConverter")
                    .getMethod("getCommunicationState").invoke(null);
        } catch (ReflectiveOperationException exc) {
            throw new IllegalArgumentException("Cannot verify current game state", exc);
        }
    }

    public synchronized void observe(String id, String json) {
        stateId = id;
        state = DecisionState.project(new JsonParser().parse(json));
    }

    public synchronized String begin(String wire) {
        active = false;
        receipt = null;
        // CM's DataReader consumes LF/NUL but retains the CR in Windows CRLF.
        // Normalize only the outer envelope; decoded command text stays strict.
        String packet = wire.trim();
        if (!packet.startsWith("AR1 ")) return wire;
        String[] parts = packet.split(" ", 4);
        receipt = new JsonObject();
        receipt.addProperty("id", parts.length > 1 ? parts[1] : "");
        receipt.addProperty("status", "rejected");
        if (parts.length != 4 || !parts[1].matches("[a-zA-Z0-9_-]{1,80}"))
            throw reject("invalid_envelope", "Malformed AR1 command");
        if (!seen.add(parts[1])) throw reject("duplicate_command", "Duplicate command ID; not executed again");
        if (seen.size() > 4096) seen.remove(seen.iterator().next());
        String command;
        try { command = new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException exc) { throw reject("invalid_envelope", exc.getMessage()); }
        if (command.isEmpty() || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)
            throw reject("invalid_envelope", "Invalid AR1 command text");
        receipt.addProperty("command", command);
        boolean query = command.trim().equalsIgnoreCase("STATE");
        if (!query && (!parts[2].equals(stateId) || state == null))
            throw reject("stale_observation", "Stale observation; refresh STATE before deciding");
        if (!query) {
            String live;
            try { live = liveState.get(); }
            catch (RuntimeException exc) { throw reject("validation_unavailable", "Cannot verify current game state: " + exc); }
            validateState(live);
        }
        active = true;
        receipt.addProperty("status", query ? "query" : "executing");
        return command;
    }

    public synchronized void validateState(String live) {
        JsonElement projected;
        try { projected = DecisionState.project(new JsonParser().parse(live)); }
        catch (RuntimeException exc) { throw reject("validation_unavailable", "Cannot verify current game state: " + exc); }
        if (state == null || !state.equals(projected))
            throw reject("state_changed", "Game state changed during decision; refresh STATE");
    }

    private IllegalArgumentException reject(String code, String error) {
        if (receipt != null) {
            receipt.addProperty("error_code", code);
            receipt.addProperty("execution_status", "not_executed");
            receipt.addProperty("error", error);
        }
        return new IllegalArgumentException(error);
    }

    public synchronized void accepted() {
        if (active && !"query".equals(receipt.get("status").getAsString()))
            receipt.addProperty("status", "accepted");
    }

    public synchronized void failed(String error) {
        if (receipt != null) {
            receipt.addProperty("status", "rejected");
            receipt.addProperty("error", error);
            // Only a pre-executor guard can prove not_executed. Exceptions from
            // the executor may occur after effects/queue submission: never retry them.
            if (!receipt.has("error_code")) receipt.addProperty("error_code", "command_failed");
            if (!receipt.has("execution_status")) receipt.addProperty("execution_status", active ? "unknown" : "not_executed");
        }
    }

    public synchronized void finish() { active = false; }

    public synchronized String activeId() {
        return active && receipt != null ? receipt.get("id").getAsString() : null;
    }

    public synchronized void link(String transaction, boolean queued) {
        if (active && receipt != null) {
            receipt.addProperty("transaction_id", transaction);
            receipt.addProperty("execution_status", queued ? "pending" : "observed");
        }
    }

    public synchronized void execution(String transaction, String status) {
        if (receipt != null && receipt.has("transaction_id")
                && transaction.equals(receipt.get("transaction_id").getAsString()))
            receipt.addProperty("execution_status", status);
    }

    public synchronized void stamp(JsonObject root) {
        root.addProperty("recorder_command_protocol", 1);
        if (receipt != null) root.add("recorder_command", new JsonParser().parse(receipt.toString()));
    }

    public synchronized String decorate(String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            stamp(root);
            return root.toString();
        } catch (RuntimeException exc) { return json; }
    }
}
