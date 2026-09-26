package actionrecorder;

import actionrecorder.runtime.ActionRecorderRuntime;
import basemod.BaseMod;
import basemod.interfaces.PostUpdateSubscriber;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;

@SpireInitializer
public class ActionRecorder implements PostUpdateSubscriber {
    private final ActionRecorderRuntime runtime;

    public ActionRecorder() {
        runtime = ActionRecorderRuntime.getInstance();
        BaseMod.subscribe(this);
    }

    @Override
    public void receivePostUpdate() {
        runtime.update();
    }

    public static void initialize() {
        new ActionRecorder();
    }
}
