package actionrecorder;

import actionrecorder.runtime.QueuedDecisionTracker;
import java.util.Arrays;
import java.util.Collections;

/** No game initialization: exercise exact queue identity, duplicate labels, cancellation. */
public final class QueuedDecisionTrackerSmoke {
    public static void main(String[] args) {
        QueuedDecisionTracker<Object> tracker = new QueuedDecisionTracker<Object>();
        Object first = new String("same-card"), second = new String("same-card");
        tracker.register(first, "first", 1);
        tracker.register(second, "second", 1);
        check(tracker.removeMissing(Arrays.asList(first, second)).isEmpty(), "identity retention");
        check("first".equals(tracker.begin(first).transactionId), "first exact queue item");
        tracker.active().dispatched = true;
        check(tracker.finishActive().dispatched, "dispatch survives queue removal");
        check("second".equals(tracker.begin(second).transactionId), "equal label is not same item");
        check(!tracker.finishActive().dispatched, "validation failure is not executed");
        Object endTurn = new Object();
        tracker.register(endTurn, "end", 1);
        check("end".equals(tracker.removeMissing(Collections.emptyList()).get(0).transactionId), "cancelled queue entry retained");
        tracker.register(first, "resume", 2);
        tracker.clear();
        check(!tracker.hasPending(first) && tracker.active() == null, "run reset");
        System.out.println("PASS queue identity / dispatch / rejection / cancellation / reset");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
