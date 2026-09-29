package actionrecorder.ui;

import actionrecorder.ActionRecorderConfig;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;

/** Lightweight, non-blocking in-game notification for recorded actions. */
public final class ActionToastOverlay {
    private static final long FADE_IN_MS = 180L;
    private static final long HOLD_MS = 1100L;
    private static final long FADE_OUT_MS = 500L;
    private static final long TOTAL_MS = FADE_IN_MS + HOLD_MS + FADE_OUT_MS;

    private final ActionRecorderConfig config;
    private volatile String message;
    private volatile long shownAt;

    public ActionToastOverlay(ActionRecorderConfig config) {
        this.config = config;
        message = "";
        shownAt = 0L;
    }

    public void show(String actionId, String actionKind) {
        if (!config.isActionToastEnabled()) {
            return;
        }
        String kind = actionKind == null || actionKind.length() == 0 ? "action" : actionKind;
        String id = actionId == null || actionId.length() == 0 ? "-" : actionId;
        String text = kind + "  |  " + id;
        message = text.length() > 180 ? text.substring(0, 177) + "..." : text;
        shownAt = System.currentTimeMillis();
    }

    public void render(SpriteBatch spriteBatch) {
        if (!config.isActionToastEnabled()) {
            return;
        }
        String currentMessage = message;
        long start = shownAt;
        if (currentMessage.length() == 0 || start == 0L) {
            return;
        }
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= TOTAL_MS) {
            return;
        }
        float alpha = alphaFor(elapsed);
        BitmapFont font = FontHelper.buttonLabelFont != null
                ? FontHelper.buttonLabelFont : FontHelper.tipBodyFont;
        if (font == null) {
            return;
        }
        float y = Settings.HEIGHT - 150.0f;

        Color textColor = Settings.CREAM_COLOR.cpy();
        textColor.a = alpha;
        FontHelper.renderFontCentered(spriteBatch, font,
                currentMessage, Settings.WIDTH / 2.0f, y + 16.0f, textColor);
    }

    private float alphaFor(long elapsed) {
        if (elapsed < FADE_IN_MS) {
            return elapsed / (float) FADE_IN_MS;
        }
        long fadeOutStart = FADE_IN_MS + HOLD_MS;
        if (elapsed < fadeOutStart) {
            return 1.0f;
        }
        return 1.0f - (elapsed - fadeOutStart) / (float) FADE_OUT_MS;
    }
}
