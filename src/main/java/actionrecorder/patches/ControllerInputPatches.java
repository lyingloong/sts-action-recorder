package actionrecorder.patches;

import actionrecorder.runtime.ActionRecorderRuntime;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.helpers.controller.CInputHelper;

/** Captures controller button input alongside keyboard and mouse events. */
public final class ControllerInputPatches {
    private ControllerInputPatches() {
    }

    @SpirePatch(clz = CInputHelper.class, method = "listenerPress")
    public static class ButtonDown {
        @SpirePostfixPatch
        public static void postfix(int button) {
            ActionRecorderRuntime.getInstance().recordRawInput(
                    "controller_button_down", "\"button\":" + button);
        }
    }

    @SpirePatch(clz = CInputHelper.class, method = "listenerRelease")
    public static class ButtonUp {
        @SpirePostfixPatch
        public static void postfix(int button) {
            ActionRecorderRuntime.getInstance().recordRawInput(
                    "controller_button_up", "\"button\":" + button);
        }
    }
}
