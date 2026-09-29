package actionrecorder.ui;

import actionrecorder.ActionRecorderConfig;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;

import java.util.ArrayList;
import java.util.List;

/** Shows the raw CommunicationMod commands and choices for in-game debugging. */
public final class AvailableActionsOverlay {
    private static final int MAX_LINES = 24;
    private static final int WRAP_AT = 42;

    private final ActionRecorderConfig config;
    private volatile List<String> lines = new ArrayList<String>();

    public AvailableActionsOverlay(ActionRecorderConfig config) {
        this.config = config;
    }

    public void setSnapshot(JsonObject snapshot) {
        if (snapshot == null || !snapshot.has("game_state")) {
            lines = new ArrayList<String>();
            return;
        }
        List<String> result = new ArrayList<String>();
        appendWrapped(result, "commands: " + value(snapshot.get("available_commands")));
        JsonObject state = snapshot.getAsJsonObject("game_state");
        if (state.has("choice_list")) {
            appendWrapped(result, "choices: " + value(state.get("choice_list")));
        }
        // The CommunicationMod command/choice pair is the source of truth this
        // panel is intended to inspect; do not label it as the agent's derived
        // legal-action list.
        if (result.size() > MAX_LINES) {
            result = new ArrayList<String>(result.subList(0, MAX_LINES));
            result.add("… (truncated; full data remains in the JSONL trace)");
        }
        lines = result;
    }

    public void render(SpriteBatch batch) {
        if (!config.isAvailableActionsEnabled()) {
            return;
        }
        List<String> current = lines;
        BitmapFont font = FontHelper.tipBodyFont != null
                ? FontHelper.tipBodyFont : FontHelper.buttonLabelFont;
        if (font == null || current.isEmpty()) {
            return;
        }

        float scale = Settings.scale;
        float x = 24.0f * scale;
        float lineHeight = Math.max(font.getLineHeight(), 16.0f * scale);
        float padding = 14.0f * scale;
        float topMargin = 190.0f * scale;
        float textX = x + padding;
        float textY = Settings.HEIGHT - topMargin;
        FontHelper.renderFontLeftTopAligned(batch, font,
                "CommunicationMod · commands / choices（协议提示）",
                textX, textY, Settings.CREAM_COLOR);
        textY -= lineHeight * 1.35f;
        for (String line : current) {
            font.draw(batch, line, textX, textY);
            textY -= lineHeight;
        }
    }

    private static String value(JsonElement element) {
        return element == null || element.isJsonNull() ? "null" : element.toString();
    }

    private static void appendWrapped(List<String> target, String value) {
        String remaining = value == null ? "null" : value;
        while (remaining.length() > WRAP_AT) {
            int split = remaining.lastIndexOf(',', WRAP_AT);
            if (split < WRAP_AT / 2) split = WRAP_AT;
            target.add(remaining.substring(0, split + (split < WRAP_AT ? 1 : 0)));
            remaining = "  " + remaining.substring(split + (split < WRAP_AT ? 1 : 0));
        }
        target.add(remaining);
    }
}
