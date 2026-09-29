package actionrecorder.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional, in-process state provider; CommunicationMod is not a load dependency. */
final class CommunicationStateBridge {
    private Method converter;
    private boolean lookedUp;
    private boolean warned;
    private static boolean keyFieldsLookedUp;
    private static Field rubyKeyField;
    private static Field emeraldKeyField;
    private static Field sapphireKeyField;

    JsonObject snapshot() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                converter = Class.forName("communicationmod.GameStateConverter")
                        .getMethod("getCommunicationState");
            } catch (ReflectiveOperationException ignored) {
                System.err.println("[ActionRecorder] CommunicationMod unavailable; recording actions without state");
            }
        }
        if (converter == null) {
            return null;
        }
        try {
            JsonElement result = new JsonParser().parse((String) converter.invoke(null));
            if (result.isJsonObject() && result.getAsJsonObject().has("game_state")) {
                JsonObject root = result.getAsJsonObject();
                addKeys(root);
                return root;
            }
        } catch (Throwable exc) {
            if (!warned) {
                warned = true;
                System.err.println("[ActionRecorder] CommunicationMod state unavailable: " + exc);
            }
        }
        return null;
    }

    /**
     * CommunicationMod's current converter omits the three heart keys even
     * though the game keeps them in Settings. Add a small public snapshot so
     * action traces can distinguish an ordinary run from a heart run.
     * Reflection keeps ActionRecorder optional and compatible with older StS
     * builds where one of the fields may not exist.
     */
    private static void addKeys(JsonObject root) {
        JsonObject state = root.getAsJsonObject("game_state");
        if (state == null) {
            return;
        }
        Boolean ruby = readSetting("hasRubyKey");
        Boolean emerald = readSetting("hasEmeraldKey");
        Boolean sapphire = readSetting("hasSapphireKey");
        if (ruby == null && emerald == null && sapphire == null) {
            return;
        }
        JsonObject keys = new JsonObject();
        if (ruby != null) keys.addProperty("ruby", ruby);
        if (emerald != null) keys.addProperty("emerald", emerald);
        if (sapphire != null) keys.addProperty("sapphire", sapphire);
        state.add("keys", keys);
    }

    private static Boolean readSetting(String fieldName) {
        if (!keyFieldsLookedUp) {
            keyFieldsLookedUp = true;
            try {
                Class<?> settings = Class.forName("com.megacrit.cardcrawl.core.Settings");
                rubyKeyField = settings.getField("hasRubyKey");
                emeraldKeyField = settings.getField("hasEmeraldKey");
                sapphireKeyField = settings.getField("hasSapphireKey");
            } catch (Throwable ignored) {
                // A game build without the heart keys simply omits the field.
            }
        }
        try {
            Field field = "hasRubyKey".equals(fieldName) ? rubyKeyField
                    : "hasEmeraldKey".equals(fieldName) ? emeraldKeyField : sapphireKeyField;
            if (field == null) {
                return null;
            }
            return field.getBoolean(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

}
