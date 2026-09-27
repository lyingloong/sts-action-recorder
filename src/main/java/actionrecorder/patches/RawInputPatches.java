package actionrecorder.patches;

import actionrecorder.runtime.ActionRecorderRuntime;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.helpers.input.ScrollInputProcessor;

/**
 * Lossless input layer. These events intentionally preserve low-level input
 * even when no screen-specific semantic patch exists.
 */
public final class RawInputPatches {
    private RawInputPatches() {
    }

    @SpirePatch(clz = ScrollInputProcessor.class, method = "keyDown")
    public static class KeyDown {
        @SpirePostfixPatch
        public static void postfix(int keycode) {
            record("key_down", "\"keycode\":" + keycode);
        }
    }

    @SpirePatch(clz = ScrollInputProcessor.class, method = "keyUp")
    public static class KeyUp {
        @SpirePostfixPatch
        public static void postfix(int keycode) {
            record("key_up", "\"keycode\":" + keycode);
        }
    }

    @SpirePatch(clz = ScrollInputProcessor.class, method = "keyTyped")
    public static class KeyTyped {
        @SpirePostfixPatch
        public static void postfix(char character) {
            record("key_typed", "\"character\":" + quote(String.valueOf(character)));
        }
    }

    @SpirePatch(clz = ScrollInputProcessor.class, method = "touchDown")
    public static class TouchDown {
        @SpirePostfixPatch
        public static void postfix(int screenX, int screenY, int pointer, int button) {
            record("mouse_down", "\"x\":" + screenX + ",\"y\":" + screenY
                    + ",\"pointer\":" + pointer + ",\"button\":" + button);
        }
    }

    @SpirePatch(clz = ScrollInputProcessor.class, method = "touchUp")
    public static class TouchUp {
        @SpirePostfixPatch
        public static void postfix(int screenX, int screenY, int pointer, int button) {
            record("mouse_up", "\"x\":" + screenX + ",\"y\":" + screenY
                    + ",\"pointer\":" + pointer + ",\"button\":" + button);
        }
    }

    @SpirePatch(clz = ScrollInputProcessor.class, method = "touchDragged")
    public static class TouchDragged {
        @SpirePostfixPatch
        public static void postfix(int screenX, int screenY, int pointer) {
            record("mouse_dragged", "\"x\":" + screenX + ",\"y\":" + screenY
                    + ",\"pointer\":" + pointer);
        }
    }

    @SpirePatch(clz = ScrollInputProcessor.class, method = "mouseMoved")
    public static class MouseMoved {
        @SpirePostfixPatch
        public static void postfix(int screenX, int screenY) {
            record("mouse_moved", "\"x\":" + screenX + ",\"y\":" + screenY);
        }
    }

    @SpirePatch(clz = ScrollInputProcessor.class, method = "scrolled")
    public static class Scrolled {
        @SpirePostfixPatch
        public static void postfix(int amount) {
            record("mouse_scrolled", "\"amount\":" + amount);
        }
    }

    private static void record(String inputType, String details) {
        ActionRecorderRuntime.getInstance().recordRawInput(inputType, details);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
