package actionrecorder.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Identity-based correlation. A UUID or timestamp is not a queue entry. */
public final class QueuedDecisionTracker<T> {
    public static final class Entry<T> {
        public final T item;
        public final String transactionId;
        public final int turn;
        public boolean dispatched;
        Entry(T item, String transactionId, int turn) {
            this.item = item;
            this.transactionId = transactionId;
            this.turn = turn;
        }
    }

    private final Map<T, Entry<T>> pending = new IdentityHashMap<T, Entry<T>>();
    private Entry<T> active;

    public void register(T item, String transactionId, int turn) {
        if (pending.containsKey(item)) throw new IllegalStateException("Queue entry already registered");
        pending.put(item, new Entry<T>(item, transactionId, turn));
    }
    public boolean hasPending(T item) { return pending.containsKey(item); }
    public boolean hasPending() { return !pending.isEmpty(); }
    public Entry<T> active() { return active; }
    /** Drain engine queue continuations before the next tracked human entry or idle. */
    public boolean readyToSettleBefore(T nextItem, boolean waitingForNextTurn) {
        return active != null && active.dispatched && !waitingForNextTurn
                && nextItem != active.item && (nextItem == null || hasPending(nextItem));
    }
    public Entry<T> begin(T item) {
        if (active != null) throw new IllegalStateException("Previous execution not closed");
        active = pending.remove(item);
        return active;
    }
    public Entry<T> finishActive() {
        Entry<T> previous = active;
        active = null;
        return previous;
    }
    public List<Entry<T>> removeMissing(Iterable<T> liveItems) {
        Map<T, Boolean> live = new IdentityHashMap<T, Boolean>();
        for (T item : liveItems) live.put(item, Boolean.TRUE);
        List<Entry<T>> missing = new ArrayList<Entry<T>>();
        for (T item : new ArrayList<T>(pending.keySet())) {
            if (!live.containsKey(item)) missing.add(pending.remove(item));
        }
        return missing;
    }
    public void clear() { pending.clear(); active = null; }
}
