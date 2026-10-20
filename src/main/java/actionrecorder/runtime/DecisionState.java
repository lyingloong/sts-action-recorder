package actionrecorder.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Execution guard projection, deliberately separate from the complete recorded observation. */
public final class DecisionState {
    private static final Pattern VARIABLE = Pattern.compile("!([^!]+)!");
    private DecisionState() { }

    public static JsonElement project(JsonElement observation) {
        // This copy is only for equality checks: never mutate the journal or CM payload.
        JsonElement copy = new JsonParser().parse(observation.toString());
        if (!copy.isJsonObject()) throw new IllegalArgumentException("Observation must be an object");
        JsonObject root = copy.getAsJsonObject();
        JsonElement game = root.get("game_state");
        if (game == null || !game.isJsonObject()) return copy;
        JsonElement combat = game.getAsJsonObject().get("combat_state");
        if (combat == null || !combat.isJsonObject()) return copy;
        JsonObject battle = combat.getAsJsonObject();
        for (String pile : new String[]{"draw_pile", "discard_pile", "exhaust_pile"}) {
            JsonElement cards = battle.get(pile);
            if (cards == null || !cards.isJsonArray()) continue;
            for (JsonElement value : cards.getAsJsonArray()) {
                if (!value.isJsonObject()) continue;
                JsonObject card = value.getAsJsonObject();
                // resetAttributes() restores these display values after a card leaves the hand.
                // Only ignore derived values when their authoritative base/template is supplied.
                if (knownNumber(card, "base_damage")) card.remove("damage");
                if (knownNumber(card, "base_block")) card.remove("block");
                if (derivedDescription(card)) card.remove("description");
            }
        }
        // Hand, limbo, ordered pile identities, base values, costs, upgrades, flags,
        // HP/energy/powers, targets, choices, phase/readiness and unknown fields stay exact.
        return copy;
    }

    private static boolean knownNumber(JsonObject card, String key) {
        JsonElement value = card.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    private static boolean derivedDescription(JsonObject card) {
        JsonElement template = card.get("raw_description");
        if (template == null || !template.isJsonPrimitive() || !template.getAsJsonPrimitive().isString()) return false;
        Matcher variables = VARIABLE.matcher(template.getAsString());
        boolean hasDisplayValue = false;
        while (variables.find()) {
            String key = variables.group(1);
            if ("D".equals(key) && knownNumber(card, "base_damage")) hasDisplayValue = true;
            else if ("B".equals(key) && knownNumber(card, "base_block")) hasDisplayValue = true;
            else if ("M".equals(key) && knownNumber(card, "magic_number")) { /* compared exactly */ }
            else return false; // Mod dynamic variables may not have separate authoritative fields.
        }
        return hasDisplayValue;
    }
}
