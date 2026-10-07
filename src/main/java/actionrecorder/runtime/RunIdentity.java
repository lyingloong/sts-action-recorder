package actionrecorder.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.util.UUID;

/** Persistent journal identity. A seed is metadata, never a run's identity. */
public final class RunIdentity {
    public String runId, seed, character, fileName, checkpointId, checkpointSegment, checkpointState;
    public int ascension;

    public static boolean ready(boolean loading, boolean pendingLoad, boolean fieldsRestored,
                                int act, boolean hasSeed) {
        return !loading && (!pendingLoad || fieldsRestored) && act > 0 && hasSeed;
    }

    public static RunIdentity fresh(String character, int ascension, String seed) {
        RunIdentity value = new RunIdentity();
        value.runId = UUID.randomUUID().toString();
        value.character = character;
        value.ascension = ascension;
        value.seed = seed;
        value.fileName = "run-" + value.runId + "-" + fingerprint(character, ascension, seed) + ".jsonl";
        return value;
    }

    public static RunIdentity resume(JsonElement saved, String character, int ascension, String seed) {
        JsonObject object = saved != null && saved.isJsonObject() ? saved.getAsJsonObject() : new JsonObject();
        String id = text(object, "run_id");
        RunIdentity value = fresh(character, ascension, seed);
        // Explicit saved identity is authoritative, including legacy 0.1.4 saves.
        if (id != null && id.matches("[A-Za-z0-9_-]+")) {
            value.runId = id;
            String name = text(object, "file_name");
            String oldFingerprint = text(object, "fingerprint");
            value.fileName = name != null && name.startsWith("run-" + id + "-")
                    && name.matches("run-[A-Za-z0-9_-]+\\.jsonl") ? name
                    : "run-" + id + "-" + (oldFingerprint != null && oldFingerprint.matches("[A-Za-z0-9_-]+")
                        ? oldFingerprint : fingerprint(character, ascension, seed)) + ".jsonl";
        }
        value.checkpointId = text(object, "checkpoint_id");
        value.checkpointSegment = text(object, "checkpoint_segment_id");
        value.checkpointState = text(object, "checkpoint_state_id");
        return value;
    }

    public JsonObject checkpoint(String segment, String state) {
        checkpointId = "checkpoint-" + UUID.randomUUID().toString();
        checkpointSegment = segment;
        checkpointState = state;
        JsonObject object = new JsonObject();
        object.addProperty("run_id", runId);
        object.addProperty("seed", seed);
        object.addProperty("character", character);
        object.addProperty("ascension", ascension);
        object.addProperty("file_name", fileName);
        object.addProperty("fingerprint", fingerprint(character, ascension, seed));
        object.addProperty("checkpoint_id", checkpointId);
        object.addProperty("checkpoint_segment_id", segment);
        object.addProperty("checkpoint_state_id", state);
        return object;
    }

    private static String fingerprint(String character, int ascension, String seed) {
        return (character + "-A" + ascension + "-seed-" + seed).replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }
}
