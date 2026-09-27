package actionrecorder;

import actionrecorder.runtime.ActionRecorderRuntime;
import basemod.BaseMod;
import basemod.abstracts.CustomSavableRaw;
import basemod.interfaces.PostUpdateSubscriber;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

@SpireInitializer
public class ActionRecorder implements PostUpdateSubscriber {
    private final ActionRecorderRuntime runtime;

    public ActionRecorder() {
        runtime = ActionRecorderRuntime.getInstance();
        BaseMod.subscribe(this);
        BaseMod.addSaveField("ActionRecorderRun", new CustomSavableRaw() {
            @Override
            public JsonElement onSaveRaw() {
                return runtime.saveRunIdentity();
            }

            @Override
            public void onLoadRaw(JsonElement value) {
                runtime.restoreRunIdentity(value);
            }
        });
    }

    @Override
    public void receivePostUpdate() {
        runtime.update();
    }

    public static void initialize() {
        new ActionRecorder();
    }
}
