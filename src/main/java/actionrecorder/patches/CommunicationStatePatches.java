package actionrecorder.patches;

import actionrecorder.runtime.ActionRecorderRuntime;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;

/**
 * Adds fields that the optional CommunicationMod converter does not expose.
 * The patch is declared by class name so ActionRecorder remains independent
 * of CommunicationMod at compile time and still loads without that mod.
 */
public final class CommunicationStatePatches {
    private static boolean fieldsLookedUp;
    private static Field rubyKeyField;
    private static Field emeraldKeyField;
    private static Field sapphireKeyField;

    private CommunicationStatePatches() {
    }

    @SpirePatch(
            cls = "communicationmod.GameStateConverter",
            method = "getCommunicationState",
            requiredModId = "CommunicationMod",
            optional = true)
    public static class AddHeartKeys {
        @SpirePostfixPatch
        public static String postfix(String __result) {
            return addKeys(__result);
        }
    }

    /**
     * CommunicationMod only publishes a new external state after its own
     * change detector says the game state changed. Reuse that boundary for the
     * recorder cache instead of serializing the complete state every update.
     */
    @SpirePatch(
            cls = "communicationmod.CommunicationMod",
            method = "publishOnGameStateChange",
            requiredModId = "CommunicationMod",
            optional = true)
    public static class CachePublishedState {
        @SpirePostfixPatch
        public static void postfix() {
            ActionRecorderRuntime.getInstance().cachePublishedFrameSnapshot();
        }
    }

    private static String addKeys(String result) {
        if (result == null || result.length() == 0) {
            return result;
        }
        try {
            JsonElement parsed = new JsonParser().parse(result);
            if (!parsed.isJsonObject()) {
                return result;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonElement gameState = root.get("game_state");
            if (gameState == null || !gameState.isJsonObject()) {
                return result;
            }
            Boolean ruby = readSetting("hasRubyKey");
            Boolean emerald = readSetting("hasEmeraldKey");
            Boolean sapphire = readSetting("hasSapphireKey");
            if (ruby == null && emerald == null && sapphire == null) {
                return result;
            }
            JsonObject keys = new JsonObject();
            if (ruby != null) keys.addProperty("ruby", ruby);
            if (emerald != null) keys.addProperty("emerald", emerald);
            if (sapphire != null) keys.addProperty("sapphire", sapphire);
            gameState.getAsJsonObject().add("keys", keys);
            return root.toString();
        } catch (Throwable ignored) {
            // State enrichment must never break CommunicationMod's protocol.
            return result;
        }
    }

    private static Boolean readSetting(String fieldName) {
        lookupFields();
        try {
            Field field = "hasRubyKey".equals(fieldName) ? rubyKeyField
                    : "hasEmeraldKey".equals(fieldName) ? emeraldKeyField : sapphireKeyField;
            return field == null ? null : field.getBoolean(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void lookupFields() {
        if (fieldsLookedUp) {
            return;
        }
        fieldsLookedUp = true;
        try {
            Class<?> settings = Class.forName("com.megacrit.cardcrawl.core.Settings");
            rubyKeyField = settings.getField("hasRubyKey");
            emeraldKeyField = settings.getField("hasEmeraldKey");
            sapphireKeyField = settings.getField("hasSapphireKey");
        } catch (Throwable ignored) {
            // Older game builds may not have the heart-key fields.
        }
    }
}
