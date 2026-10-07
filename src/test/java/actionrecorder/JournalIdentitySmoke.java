package actionrecorder;

import actionrecorder.runtime.RunIdentity;
import actionrecorder.runtime.RewardIdentity;
import com.google.gson.JsonObject;

/** Headless tests; no game resources, sockets or journal writes. */
public final class JournalIdentitySmoke {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        RunIdentity first = RunIdentity.fresh("WATCHER", 2, "-6614468472316669105");
        JsonObject save = first.checkpoint("segment-a", "session-a:state-9");
        RunIdentity resume = RunIdentity.resume(save, "WATCHER", 2, first.seed);
        check(first.runId.equals(resume.runId), "save/load changed run ID");
        check(first.fileName.equals(resume.fileName), "save/load changed journal file");
        check(first.seed.equals(resume.seed) && first.checkpointId.equals(resume.checkpointId), "checkpoint not restored");
        check("segment-a".equals(resume.checkpointSegment), "resume source segment missing");
        check(!first.runId.equals(RunIdentity.fresh("WATCHER", 2, first.seed).runId), "same seed is not the same run");
        check(!RunIdentity.ready(true, true, true, 3, true), "loading must block initialization");
        check(!RunIdentity.ready(false, true, false, 3, true), "BaseMod fields must be restored first");
        check(!RunIdentity.ready(false, false, false, 0, false), "act=0/seed=null must block initialization");
        check(RunIdentity.ready(false, true, true, 3, true), "ready restored run blocked");
        JsonObject legacy = new JsonObject();
        legacy.addProperty("run_id", first.runId);
        legacy.addProperty("fingerprint", "WATCHER-A2-seed-" + first.seed);
        RunIdentity oldResume = RunIdentity.resume(legacy, "WATCHER", 2, first.seed);
        check(first.fileName.equals(oldResume.fileName), "legacy save should append old file");
        check(!first.runId.equals(RunIdentity.resume(null, "WATCHER", 2, first.seed).runId), "unknown save guessed old run");
        JsonObject unsafe = new JsonObject(); unsafe.addProperty("run_id", first.runId); unsafe.addProperty("file_name", "../../other.jsonl");
        check(!RunIdentity.resume(unsafe, "WATCHER", 2, first.seed).fileName.contains("/"), "unsafe restored filename");
        RewardIdentity ids = new RewardIdentity(); Object a = new String("identical"), b = new String("identical");
        String id = ids.id(a);
        check(id.equals(ids.id(a)), "reopening reward changed identity");
        check(!id.equals(ids.id(b)), "identical rewards collided");
        check(ids.id(null) == null, "unavailable reward must stay null");
        ids.clear(); check(!id.equals(ids.id(a)), "reload must establish new reward object identities");
        System.out.println("PASS journal identity, load barrier, checkpoints and reward object IDs");
    }
}
