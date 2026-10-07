package actionrecorder.runtime;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Verify publication scope without starting the game or opening a script pipe. */
public final class CommunicationSnapshotSmoke {
    private static CommunicationStateBridge bridge;
    private static String expected;
    private static boolean nested;
    private static boolean fail;
    private static int publications;

    public static void publisher() {
        JsonObject root = new JsonObject();
        root.addProperty("ready_for_command", true);
        bridge.stampSnapshotMetadata(root);
        check(root.get("ready_for_command").getAsBoolean(), "original CM readiness unchanged");
        check(root.get("recorder_snapshot_only").getAsBoolean(), "boundary snapshot is read-only");
        check(root.get("recorder_snapshot_kind").getAsString().equals(expected), "publication kind");
        publications++;
        if (nested) {
            nested = false;
            String outer = expected;
            expected = "effects_settled";
            check(bridge.publish(expected), "nested publication succeeds");
            expected = outer;
            check(outer.equals(bridge.snapshotKind()), "nested scope restores outer kind");
        }
        if (fail) throw new IllegalStateException("intentional publisher failure");
    }

    public static void main(String[] args) throws Exception {
        bridge = new CommunicationStateBridge();
        Method method = CommunicationSnapshotSmoke.class.getMethod("publisher");
        Field field = CommunicationStateBridge.class.getDeclaredField("publisher");
        field.setAccessible(true);
        field.set(bridge, method);
        for (String kind : new String[] {"action_before", "execution_before", "effects_settled"}) {
            expected = kind;
            check(bridge.publish(kind), "publication succeeds");
            check(bridge.snapshotKind() == null, "normal scope restored");
        }
        expected = "action_before";
        nested = true;
        check(bridge.publish(expected), "nested publication");
        check(bridge.snapshotKind() == null, "nested scope fully restored");
        fail = true;
        check(!bridge.publish(expected), "failure is reported");
        check(bridge.snapshotKind() == null, "failed publisher cannot poison next CM reply");
        JsonObject normal = new JsonObject();
        bridge.stampSnapshotMetadata(normal);
        check(!normal.get("recorder_snapshot_only").getAsBoolean(), "natural CM reply is actionable");
        check(!normal.has("recorder_snapshot_kind"), "normal reply has no boundary kind");
        check(publications == 6, "all boundary publications tested");
        System.out.println("PASS snapshot metadata / all kinds / nested scope / failure reset / natural reply");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
