package actionrecorder;

import actionrecorder.runtime.ActionRecorderRuntime;
import actionrecorder.ui.ActionToastOverlay;
import actionrecorder.ui.AvailableActionsOverlay;
import basemod.BaseMod;
import basemod.abstracts.CustomSavableRaw;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostRenderSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

@SpireInitializer
public class ActionRecorder implements PostUpdateSubscriber, PostInitializeSubscriber, PostRenderSubscriber {
    private final ActionRecorderRuntime runtime;
    private final ActionRecorderConfig config;
    private final ActionToastOverlay actionToast;
    private final AvailableActionsOverlay availableActionsOverlay;

    public ActionRecorder() {
        runtime = ActionRecorderRuntime.getInstance();
        config = new ActionRecorderConfig();
        actionToast = new ActionToastOverlay(config);
        availableActionsOverlay = new AvailableActionsOverlay(config);
        runtime.setActionToast(actionToast);
        runtime.setAvailableActionsOverlay(availableActionsOverlay);
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

    @Override
    public void receivePostInitialize() {
        config.load();
        config.registerSettingsPanel();
    }

    @Override
    public void receivePostRender(SpriteBatch spriteBatch) {
        actionToast.render(spriteBatch);
        availableActionsOverlay.render(spriteBatch);
    }

    public static void initialize() {
        new ActionRecorder();
    }
}
