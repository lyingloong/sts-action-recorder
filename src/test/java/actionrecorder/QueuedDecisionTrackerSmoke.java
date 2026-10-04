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
        check(!tracker.readyToSettleBefore(first, false), "original queue entry still pending");
        Object automaticCopy = new Object();
        check(!tracker.readyToSettleBefore(automaticCopy, false), "automatic replay is part of the active decision");
        check(!tracker.readyToSettleBefore(new Object(), false), "recursive automatic replay also delays settlement");
        check(!tracker.readyToSettleBefore(second, true), "end turn waits for next turn");
        check(tracker.readyToSettleBefore(second, false), "next human execution follows the completed auto chain");
        check(tracker.readyToSettleBefore(null, false), "idle queue is a settlement boundary");
        check(tracker.finishActive().dispatched, "dispatch survives queue removal");
        check("second".equals(tracker.begin(second).transactionId), "equal label is not same item");
        check(!tracker.readyToSettleBefore(null, false), "unexecuted action is not settled");
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
