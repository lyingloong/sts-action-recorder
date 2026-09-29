package actionrecorder;

import basemod.BaseMod;
import basemod.ModLabeledToggleButton;
import basemod.ModPanel;
import com.badlogic.gdx.graphics.Color;
import com.evacipated.cardcrawl.modthespire.lib.SpireConfig;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;

import java.io.IOException;

/** Persistent user-facing options for ActionRecorder. */
public final class ActionRecorderConfig {
    private static final String MOD_ID = "ActionRecorder";
    private static final String CONFIG_NAME = "settings";
    private static final String SHOW_ACTION_TOAST = "showActionToast";
    private static final String SHOW_AVAILABLE_ACTIONS = "showAvailableActions";

    private volatile boolean showActionToast;
    private volatile boolean showAvailableActions;
    private SpireConfig config;

    public ActionRecorderConfig() {
        showActionToast = false;
        showAvailableActions = false;
    }

    public boolean isActionToastEnabled() {
        return showActionToast;
    }

    public boolean isAvailableActionsEnabled() {
        return showAvailableActions;
    }

    public void load() {
        try {
            config = new SpireConfig(MOD_ID, CONFIG_NAME);
            config.load();
            if (config.has(SHOW_ACTION_TOAST)) {
                showActionToast = config.getBool(SHOW_ACTION_TOAST);
            } else {
                config.setBool(SHOW_ACTION_TOAST, showActionToast);
                config.save();
            }
            if (config.has(SHOW_AVAILABLE_ACTIONS)) {
                showAvailableActions = config.getBool(SHOW_AVAILABLE_ACTIONS);
            } else {
                config.setBool(SHOW_AVAILABLE_ACTIONS, showAvailableActions);
                config.save();
            }
        } catch (IOException exception) {
            BaseMod.logger.error("ActionRecorder could not load its settings", exception);
        }
    }

    public void registerSettingsPanel() {
        ModPanel panel = new ModPanel();
        ModLabeledToggleButton actionToastToggle = new ModLabeledToggleButton(
                "Show Action Record Toasts",
                "Display a fading toast after each recorded game action",
                350.0f,
                700.0f,
                Settings.CREAM_COLOR,
                FontHelper.buttonLabelFont,
                showActionToast,
                panel,
                label -> { },
                button -> {
                    showActionToast = button.enabled;
                    save();
                });
        panel.addUIElement(actionToastToggle);
        ModLabeledToggleButton availableActionsToggle = new ModLabeledToggleButton(
                "Show Current Available Actions",
                "Display CommunicationMod commands and choices in a compact in-game debug panel",
                350.0f,
                630.0f,
                Settings.CREAM_COLOR,
                FontHelper.buttonLabelFont,
                showAvailableActions,
                panel,
                label -> { },
                button -> {
                    showAvailableActions = button.enabled;
                    save();
                });
        panel.addUIElement(availableActionsToggle);
        // This is a game-provided texture, so the mod does not depend on a local
        // absolute path or an additional binary asset just for its settings badge.
        BaseMod.registerModBadge(
                ImageMaster.MAP_NODE_EVENT,
                "StS Action Recorder",
                "ActionRecorder contributors",
                "Configure ActionRecorder.",
                panel);
    }

    private void save() {
        if (config == null) {
            return;
        }
        try {
            config.setBool(SHOW_ACTION_TOAST, showActionToast);
            config.setBool(SHOW_AVAILABLE_ACTIONS, showAvailableActions);
            config.save();
        } catch (IOException exception) {
            BaseMod.logger.error("ActionRecorder could not save its settings", exception);
        }
    }
}
