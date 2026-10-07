package actionrecorder.runtime;

import java.util.IdentityHashMap;
import java.util.UUID;

/** IDs describe object instances, not content or mutable list positions. */
public final class RewardIdentity {
    private final IdentityHashMap<Object, String> ids = new IdentityHashMap<Object, String>();

    public synchronized String id(Object reward) {
        if (reward == null) return null;
        String id = ids.get(reward);
        if (id == null) {
            id = "reward-" + UUID.randomUUID().toString();
            ids.put(reward, id);
        }
        return id;
    }

    public synchronized void clear() { ids.clear(); }
}
