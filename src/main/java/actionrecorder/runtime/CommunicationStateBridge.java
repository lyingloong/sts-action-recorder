package actionrecorder.runtime;

import java.lang.reflect.Method;
import com.google.gson.JsonObject;

/** Optional access to the same publisher used by CommunicationMod's script interface. */
final class CommunicationStateBridge {
    private Method publisher;
    private boolean warned;
    private String snapshotKind;

    String snapshotKind() { return snapshotKind; }

    void stampSnapshotMetadata(JsonObject root) {
        root.addProperty("recorder_snapshot_only", snapshotKind != null);
        if (snapshotKind != null) root.addProperty("recorder_snapshot_kind", snapshotKind);
    }

    boolean publish(String kind) {
        String previous = snapshotKind;
        snapshotKind = kind;
        try {
            if (publisher == null) {
                publisher = Class.forName("communicationmod.CommunicationMod")
                        .getDeclaredMethod("sendGameState");
                publisher.setAccessible(true);
            }
            publisher.invoke(null);
            return true;
        } catch (Throwable exc) {
            if (!warned) {
                warned = true;
                System.err.println("[ActionRecorder] state publisher unavailable: " + exc);
            }
            return false;
        } finally {
            snapshotKind = previous;
        }
    }
}
