package actionrecorder;

import actionrecorder.patches.CommunicationStatePatches;
import actionrecorder.runtime.CampfireOptions;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Synthetic options: runtime text and CM choice alignment without game startup. */
public final class CampfireOptionsSmoke {
    private static class OptionFields {
        private final String choice;
        protected String label;
        protected String description;
        public boolean usable;
        OptionFields(String choice, String label, String description, boolean usable) {
            this.choice = choice;
            this.label = label;
            this.description = description;
            this.usable = usable;
        }
        public void useOption() { throw new AssertionError("must never execute an option"); }
    }
    private static final class RestOption extends OptionFields {
        RestOption() { super("rest", "休息", "回复 #g24 点生命。 NL [#aabbcc]额外回复 15 点生命。[]", true); }
    }
    private static final class UnknownModOption extends OptionFields {
        UnknownModOption() { super("mod-choice-alias", "调谐", "@下场战斗~获得 #b3 点能量 [R]。", true); }
    }
    private static final class WrongFieldTypes {
        private Integer label = 9;
        private Object description = new Object();
        private String usable = "true";
    }
    /** No localized option assets: just the game's base fields and a mod shadow. */
    private static final class ShadowedGameOption extends AbstractCampfireOption {
        private String label = "子类名称";
        private String description = "子类额外状态，回复 999 点生命。";
        public boolean usable = true;
        ShadowedGameOption() {
            setUiFields("游戏显示名称", "回复 #g24 点生命。", false);
        }
        void setUiFields(String label, String description, boolean usable) {
            super.label = label;
            super.description = description;
            super.usable = usable;
        }
        @Override public void useOption() { throw new AssertionError("must never execute an option"); }
    }
    private static final class UnreadableList extends AbstractList<Object> {
        @Override public Object get(int index) { throw new IllegalStateException("unavailable"); }
        @Override public int size() { throw new IllegalStateException("unavailable"); }
    }

    public static void main(String[] args) {
        RestOption rest = new RestOption();
        UnknownModOption custom = new UnknownModOption();
        List<String> choices = new ArrayList<String>(Arrays.asList("mod-choice-alias", "rest"));
        List<Object> options = new ArrayList<Object>(Arrays.asList(custom, rest));
        List<Map<String, Object>> details = snapshot(choices, options);
        check(details != null && details.size() == 2, "all choices have details");
        Map<String, Object> first = details.get(0), second = details.get(1);
        check(first.size() == 8 && second.size() == 8, "stable eight-field contract");
        check(first.get("choice_index").equals(0) && second.get("choice_index").equals(1), "zero-based CM order");
        check(first.get("choice").equals("mod-choice-alias"), "choice is the published string, not a derived class name");
        check(first.get("name").equals("调谐") && second.get("name").equals("休息"), "inherited protected labels");
        check(first.get("option_class").equals(UnknownModOption.class.getName()), "unknown mod class retained");
        check(second.get("raw_description").equals(rest.description), "unaltered game runtime description");
        check(second.get("description").equals("回复 24 点生命。 \n 额外回复 15 点生命。"), "Chinese formatting removed and current values retained");
        check(first.get("description").equals("下场战斗获得 3 点能量 红色能量。"), "game formatting cleanup");
        check(first.get("description_source").equals("game_runtime") && Boolean.TRUE.equals(first.get("usable")), "source and inherited usable");
        check(snapshot(Arrays.asList("rest", "mod-choice-alias"), options) == null,
                "equal counts with wrong choice order are unavailable");
        check(snapshot(Arrays.asList("mod-choice-alias", "different"), options) == null,
                "equal counts with a changed choice identity are unavailable");
        check(CampfireOptions.snapshot(choices, options, option -> null) == null, "unavailable provider name is null");
        check(CampfireOptions.snapshot(choices, options, option -> { throw new IllegalStateException("unavailable"); }) == null,
                "provider name failures are contained");
        check(CampfireOptions.snapshot(choices, options, null) == null, "missing provider name function is unavailable");

        choices.clear(); options.clear(); rest.description = "已变化"; custom.usable = false;
        check(details.size() == 2 && second.get("choice").equals("rest")
                && second.get("description").equals("回复 24 点生命。 \n 额外回复 15 点生命。")
                && Boolean.TRUE.equals(first.get("usable")), "snapshot independent of UI cleanup and later field updates");
        List<Map<String, Object>> disabled = snapshot(Arrays.asList("mod-choice-alias"), Arrays.asList(custom));
        check(Boolean.FALSE.equals(disabled.get(0).get("usable")) && disabled.get(0).get("choice_index").equals(0),
                "projection does not refilter provider entries or turn false into null");
        List<Map<String, Object>> missing = snapshot(Arrays.asList("unknown"), Arrays.asList(new Object()));
        check(missing.get(0).get("name") == null && missing.get(0).get("description") == null
                && missing.get(0).get("raw_description") == null && missing.get(0).get("usable") == null,
                "unavailable fields are explicit nulls");
        Map<String, Object> wrong = snapshot(Arrays.asList("wrong"), Arrays.asList(new WrongFieldTypes())).get(0);
        check(wrong.get("name") == null && wrong.get("description") == null && wrong.get("usable") == null, "invalid field types are not coerced");
        ShadowedGameOption shadow = new ShadowedGameOption();
        Map<String, Object> game = CampfireOptions.snapshot(Arrays.asList("mod-runtime"), Arrays.asList(shadow),
                option -> "mod-runtime").get(0);
        check(game.get("name").equals("游戏显示名称") && game.get("raw_description").equals("回复 #g24 点生命。")
                && game.get("description").equals("回复 24 点生命。") && Boolean.FALSE.equals(game.get("usable")),
                "custom game options read UI base fields rather than same-named child shadows");
        check(game.get("option_class").equals(ShadowedGameOption.class.getName()), "custom game subclass identity retained");
        shadow.setUiFields(null, null, true);
        game = CampfireOptions.snapshot(Arrays.asList("mod-runtime"), Arrays.asList(shadow), option -> "mod-runtime").get(0);
        check(game.get("name") == null && game.get("raw_description") == null && game.get("description") == null
                && Boolean.TRUE.equals(game.get("usable")), "base nulls never fall back to a child's unrelated values");
        check(snapshot(Arrays.asList("rest"), new ArrayList<Object>()) == null, "count mismatch is unavailable");
        check(snapshot(null, options) == null && snapshot(choices, null) == null,
                "unavailable source is null");
        check(snapshot("rest", options) == null && snapshot(choices, "buttons") == null,
                "invalid source types are null");
        check(snapshot(Arrays.asList("rest"), Arrays.asList((Object) null)) == null, "missing option cannot be aligned");
        check(snapshot(Arrays.asList(7), Arrays.asList(rest)) == null, "invalid published choice cannot be guessed");
        check(snapshot(new UnreadableList(), options) == null, "collection read failures are contained");
        check(snapshot(choices, options).isEmpty(), "valid zero choices is an empty list");

        HashMap<String, Object> room = new HashMap<String, Object>();
        room.put("chest_open", false);
        check(CommunicationStatePatches.RestOptionDescriptions.postfix(room) == room
                && !room.containsKey("rest_option_details"), "non-rest rooms are untouched");
        check(CommunicationStatePatches.RestOptionDescriptions.postfix(null) == null, "null room result is preserved");
        room.put("rest_options", null);
        room.put("has_rested", true);
        CommunicationStatePatches.RestOptionDescriptions.postfix(room);
        check(room.containsKey("rest_option_details") && room.get("rest_option_details") == null
                && room.get("rest_options") == null && Boolean.TRUE.equals(room.get("has_rested")),
                "unavailable runtime adds null and retains original room fields");
        System.out.println("PASS campfire UI base fields / exact CM choice alignment / mod options / null handling / independent snapshots");
    }

    private static List<Map<String, Object>> snapshot(Object choices, Object options) {
        return CampfireOptions.snapshot(choices, options, option -> {
            if (option instanceof OptionFields) return ((OptionFields) option).choice;
            return option instanceof WrongFieldTypes ? "wrong" : "unknown";
        });
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
