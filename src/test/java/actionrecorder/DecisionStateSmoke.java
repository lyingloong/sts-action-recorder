package actionrecorder;

import actionrecorder.runtime.DecisionState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Guard ignores proven off-hand display resets, not actual decisions or identity changes. */
public final class DecisionStateSmoke {
    public static void main(String[] args) throws Exception {
        JsonObject before = observation();
        String original = before.toString();
        for (String pile : new String[]{"draw_pile", "discard_pile", "exhaust_pile"}) {
            JsonObject after = copy(before);
            JsonObject card = card(after, pile);
            card.addProperty("damage", 6); card.addProperty("block", -1);
            card.addProperty("description", "Deal 6 damage.");
            check(same(before, after), "off-hand reset: " + pile);
        }
        check(original.equals(before.toString()), "projection never changes raw observation");
        for (String pile : new String[]{"hand", "limbo"}) {
            JsonObject after = copy(before);
            card(after, pile).addProperty("damage", 6);
            check(!same(before, after), "active card values retained: " + pile);
        }
        for (String key : new String[]{"uuid", "id", "raw_description", "target", "unknown_mod_field"}) {
            JsonObject after = copy(before);
            card(after, "discard_pile").addProperty(key, "changed");
            check(!same(before, after), "card identity/template/unknown retained: " + key);
        }
        for (String key : new String[]{"base_damage", "base_block", "upgrades", "cost", "cost_for_turn"}) {
            JsonObject after = copy(before);
            card(after, "discard_pile").addProperty(key, 100);
            check(!same(before, after), "base stats/upgrade/cost retained: " + key);
        }
        JsonObject after = copy(before);
        card(after, "discard_pile").addProperty("retain", true);
        check(!same(before, after), "card flags retained");
        after = copy(before);
        combat(after).getAsJsonArray("discard_pile").add(card(after, "draw_pile"));
        check(!same(before, after), "pile membership/count retained");
        after = copy(before);
        JsonArray pile = combat(after).getAsJsonArray("draw_pile");
        JsonElement first = pile.remove(0); pile.add(first);
        check(!same(before, after), "pile order retained");
        for (String key : new String[]{"energy", "current_hp", "block"}) {
            after = copy(before);
            combat(after).getAsJsonObject("player").addProperty(key, 100);
            check(!same(before, after), "player decision value retained: " + key);
        }
        after = copy(before); combat(after).addProperty("turn", 2);
        check(!same(before, after), "turn retained");
        after = copy(before);
        combat(after).getAsJsonArray("monsters").get(0).getAsJsonObject().addProperty("current_hp", 2);
        check(!same(before, after), "target state retained");
        after = copy(before); after.addProperty("ready_for_command", false);
        check(!same(before, after), "readiness retained");
        after = copy(before);
        after.getAsJsonArray("available_commands").add("play");
        check(!same(before, after), "command capabilities retained");
        after = copy(before); after.getAsJsonObject("game_state").addProperty("screen_type", "GRID");
        check(!same(before, after), "screen retained");
        after = copy(before);
        after.getAsJsonObject("game_state").getAsJsonObject("screen_state").addProperty("selection", 1);
        check(!same(before, after), "selection state retained");
        // Unknown base/template: do not assume differing values are only presentation.
        JsonObject unknown = copy(before);
        card(unknown, "discard_pile").remove("base_damage");
        card(unknown, "discard_pile").remove("base_block");
        card(unknown, "discard_pile").remove("raw_description");
        for (String key : new String[]{"damage", "block", "description"}) {
            after = copy(unknown);
            card(after, "discard_pile").addProperty(key, 100);
            check(!same(unknown, after), "unverified derived field retained: " + key);
        }
        JsonObject custom = copy(before);
        card(custom, "discard_pile").addProperty("raw_description", "Deal !D! damage; gain !custom:X! stacks.");
        after = copy(custom);
        card(after, "discard_pile").addProperty("description", "Deal 6 damage; gain 100 stacks.");
        check(!same(custom, after), "mod variable without authoritative field remains exact");
        JsonObject staticText = copy(before);
        card(staticText, "discard_pile").addProperty("raw_description", "Draw a card.");
        after = copy(staticText);
        card(after, "discard_pile").addProperty("description", "Draw two cards.");
        check(!same(staticText, after), "static description stays exact");
        JsonObject noncombat = copy(before);
        noncombat.getAsJsonObject("game_state").remove("combat_state");
        JsonArray deck = new JsonArray(); deck.add(card(before, "discard_pile"));
        noncombat.getAsJsonObject("game_state").add("deck", deck);
        after = copy(noncombat);
        after.getAsJsonObject("game_state").getAsJsonArray("deck").get(0).getAsJsonObject().addProperty("damage", 100);
        check(!same(noncombat, after), "noncombat deck not normalized");
        if (args.length != 0) {
            if (args.length != 4 || !"--replay".equals(args[0]))
                throw new IllegalArgumentException("Usage: --replay <messages.jsonl> <before_index> <after_index>");
            replay(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        }
        System.out.println("PASS decision guard / off-hand display reset / immutable raw state / ordered identities / safety fields");
    }

    private static void replay(String path, int beforeIndex, int afterIndex) throws Exception {
        JsonObject before = null, after = null;
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            JsonObject row = new JsonParser().parse(line).getAsJsonObject();
            if (!row.has("message_index") || !row.has("message")) continue;
            int index = row.get("message_index").getAsInt();
            if (index != beforeIndex && index != afterIndex) continue;
            JsonObject state = copy(row.getAsJsonObject("message"));
            // Recorder metadata is added after the converter used by the execution guard.
            List<String> metadata = new ArrayList<>();
            for (java.util.Map.Entry<String, JsonElement> field : state.entrySet())
                if (field.getKey().startsWith("recorder_")) metadata.add(field.getKey());
            for (String key : metadata) state.remove(key);
            if (index == beforeIndex) before = state; else after = state;
        }
        check(before != null && after != null, "replay indices found");
        check(!before.equals(after), "recorded raw states really differed");
        check(same(before, after), "recorded drift is only verified off-hand presentation");
        System.out.println("PASS recorded display-reset regression " + beforeIndex + " -> " + afterIndex);
    }

    private static JsonObject observation() {
        JsonObject root = new JsonParser().parse("{\"in_game\":true,\"ready_for_command\":true,"
                + "\"available_commands\":[\"end\",\"state\"],\"game_state\":{\"screen_type\":\"NONE\","
                + "\"screen_state\":{},\"combat_state\":{\"turn\":1,\"player\":{\"energy\":0,\"current_hp\":80,\"block\":0},"
                + "\"monsters\":[{\"id\":\"Louse\",\"current_hp\":10}]}}}").getAsJsonObject();
        for (String name : new String[]{"draw_pile", "discard_pile", "exhaust_pile", "hand", "limbo"}) {
            JsonArray cards = new JsonArray();
            for (int i = 0; i < 2; i++) cards.add(new JsonParser().parse("{\"id\":\"Strike_R\",\"uuid\":\"" + name + i
                    + "\",\"base_damage\":6,\"damage\":9,\"base_block\":-1,\"block\":0,"
                    + "\"raw_description\":\"Deal !D! damage.\",\"description\":\"Deal 9 damage.\","
                    + "\"upgrades\":0,\"cost\":1,\"cost_for_turn\":1,\"retain\":false,\"target\":\"ENEMY\"}"));
            combat(root).add(name, cards);
        }
        return root;
    }
    private static JsonObject combat(JsonObject state) {
        return state.getAsJsonObject("game_state").getAsJsonObject("combat_state");
    }
    private static JsonObject card(JsonObject state, String pile) {
        return combat(state).getAsJsonArray(pile).get(0).getAsJsonObject();
    }
    private static JsonObject copy(JsonObject state) { return new JsonParser().parse(state.toString()).getAsJsonObject(); }
    private static boolean same(JsonObject a, JsonObject b) { return DecisionState.project(a).equals(DecisionState.project(b)); }
    private static void check(boolean result, String why) { if (!result) throw new AssertionError(why); }
}
