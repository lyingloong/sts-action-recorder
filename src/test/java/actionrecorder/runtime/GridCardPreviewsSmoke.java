package actionrecorder.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exercise the real grid enrichment and isolation guards without initializing game assets. */
public final class GridCardPreviewsSmoke {
    /** Model ModTheSpire's patched classes while preserving their original JAR resources. */
    private static final class PatchedClasses extends ClassLoader {
        private final String hiddenResource;
        PatchedClasses(String hiddenResource) {
            super(GridCardPreviewsSmoke.class.getClassLoader());
            this.hiddenResource = hiddenResource;
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (!name.startsWith("com.megacrit.cardcrawl.cards.")
                    && !name.startsWith("actionrecorder.runtime.GridCardPreviews")) {
                return super.loadClass(name, resolve);
            }
            Class<?> result = findLoadedClass(name);
            if (result == null) {
                String path = name.replace('.', '/') + ".class";
                try (InputStream input = getParent().getResourceAsStream(path)) {
                    if (input == null) throw new ClassNotFoundException(name);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
                    byte[] bytes = output.toByteArray();
                    result = defineClass(name, bytes, 0, bytes.length, (ProtectionDomain) null);
                } catch (IOException error) { throw new ClassNotFoundException(name, error); }
            }
            if (resolve) resolveClass(result);
            return result;
        }
        @Override public URL getResource(String name) {
            return name.equals(hiddenResource) ? null : super.getResource(name);
        }
    }

    private static void checkPatchedClassOrigin() throws Exception {
        String cardName = "com.megacrit.cardcrawl.cards.red.Strike_Red";
        PatchedClasses loader = new PatchedClasses(null);
        // Loading without initialization must not require textures, localization, or a running game.
        Class<?> card = Class.forName(cardName, false, loader);
        Class<?> base = Class.forName("com.megacrit.cardcrawl.cards.AbstractCard", false, loader);
        Class<?> helper = Class.forName("actionrecorder.runtime.GridCardPreviews", true, loader);
        check(card.getClassLoader() == loader && base.getClassLoader() == loader
                && helper.getClassLoader() == loader, "patched classes use one isolated loader");
        for (Class<?> type : Arrays.asList(card, base)) {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            check(source == null || source.getLocation() == null,
                    "patched game classes have no CodeSource location");
        }
        Method builtIn = helper.getDeclaredMethod("builtInCardClass", Class.class);
        builtIn.setAccessible(true);
        check(Boolean.TRUE.equals(builtIn.invoke(null, card)), "null-domain original game card is supported");
        check(Boolean.TRUE.equals(builtIn.invoke(null, card)), "cached original game support stays stable");
        check(Boolean.FALSE.equals(builtIn.invoke(null, Card.class)), "unknown classes remain unsupported");
        check(!GridCardPreviews.builtInCardClass(card), "another loader cannot impersonate this game's card");

        // A class name and superclass alone are insufficient when its original JAR cannot be verified.
        PatchedClasses hidden = new PatchedClasses(cardName.replace('.', '/') + ".class");
        Class<?> hiddenCard = Class.forName(cardName, false, hidden);
        Class<?> hiddenHelper = Class.forName("actionrecorder.runtime.GridCardPreviews", true, hidden);
        Method hiddenBuiltIn = hiddenHelper.getDeclaredMethod("builtInCardClass", Class.class);
        hiddenBuiltIn.setAccessible(true);
        check(Boolean.FALSE.equals(hiddenBuiltIn.invoke(null, hiddenCard)),
                "missing original card resource is rejected even under the game package");
    }

    private static final class Card {
        private final String cardID;
        private final UUID uuid = UUID.randomUUID();
        private String name = "打击";
        private int upgrades, damage = 6, cost = 1;
        private boolean modded;
        Card(String id) { cardID = id; }
    }
    private static final class Group { private List<Card> group = new ArrayList<>(); }
    private static final class Grid {
        private boolean forUpgrade = true, confirmScreenUp;
        private Card hoveredCard, upgradePreviewCard;
        private Group targetGroup = new Group();
        private List<Card> selectedCards = new ArrayList<>();
    }
    private static class Copies implements GridCardPreviews.CardCopies {
        private int copies, upgrades, displays;
        @Override public boolean supports(Object card) { return !((Card) card).modded; }
        @Override public Object copy(Object card) {
            copies++;
            Card source = (Card) card, result = new Card(source.cardID);
            result.name = source.name; result.upgrades = source.upgrades;
            result.damage = source.damage; result.cost = source.cost;
            return result;
        }
        @Override public void upgrade(Object card) {
            upgrades++;
            Card copy = (Card) card;
            copy.upgrades++; copy.name += "+"; copy.damage += 3; copy.cost = 0;
        }
        @Override public void displayUpgrades(Object card) { displays++; }
    }
    private static Map<String, Object> convert(Object object) {
        Card card = (Card) object;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", card.cardID); result.put("uuid", card.uuid.toString());
        result.put("name", card.name); result.put("type", "ATTACK");
        result.put("cost", card.cost); result.put("upgrades", card.upgrades);
        result.put("description", "造成" + card.damage + "点伤害。");
        result.put("raw_description", "造成!D!点伤害。");
        result.put("base_damage", card.damage); result.put("retain", false);
        result.put("unknown_mod_field", "preserve");
        return result;
    }
    private static Map<String, Object> state(Grid grid) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> cards = new ArrayList<>(), selected = new ArrayList<>();
        for (Card card : grid.targetGroup.group) cards.add(convert(card));
        for (Card card : grid.selectedCards) selected.add(convert(card));
        result.put("cards", cards); result.put("selected_cards", selected);
        result.put("for_upgrade", grid.forUpgrade);
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> card(Map<String, Object> state, int index) {
        return (Map<String, Object>) ((List<?>) state.get("cards")).get(index);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> preview(Map<String, Object> card) {
        return (Map<String, Object>) card.get("upgrade_preview");
    }
    public static void main(String[] args) throws Exception {
        Grid grid = new Grid();
        Card first = new Card("Strike_R"), mod = new Card("mod:unknown"); mod.modded = true;
        grid.targetGroup.group.addAll(Arrays.asList(first, mod));
        Copies copies = new Copies();
        Map<String, Object> before = state(grid), enriched = state(grid);
        GridCardPreviews.addGridState(enriched, grid, GridCardPreviewsSmoke::convert, copies);
        check(first.upgrades == 0 && first.damage == 6 && first.cost == 1, "original card must never be upgraded");
        check(copies.copies == 1 && copies.upgrades == 1 && copies.displays == 1, "game copy/upgrade/display sequence");
        check(card(enriched, 0).get("uuid").equals(first.uuid.toString()), "source identity preserved");
        check(card(enriched, 0).get("unknown_mod_field").equals("preserve"), "raw unknown fields preserved");
        Map<String, Object> upgraded = preview(card(enriched, 0));
        check(upgraded.get("description").equals("造成9点伤害。") && upgraded.get("cost").equals(0)
                && upgraded.get("upgrades").equals(1) && upgraded.get("type").equals("ATTACK"), "full upgraded card fields");
        check(!upgraded.containsKey("uuid") && !upgraded.containsKey("upgrade_preview"), "no random copy identity or recursive preview");
        check(card(enriched, 1).get("upgrade_preview") == null
                && "unsupported_card".equals(card(enriched, 1).get("upgrade_preview_status")), "unknown mod preview is explicit");
        Map<String, Object> repeated = state(grid);
        GridCardPreviews.addGridState(repeated, grid, GridCardPreviewsSmoke::convert, new Copies());
        check(enriched.equals(repeated), "repeated observations remain stable despite fresh copy UUIDs");
        check(!card(before, 0).containsKey("upgrade_preview"), "previous snapshots remain unchanged");

        // Actual UI preview can be serialized even for mod cards, without invoking mod copy/upgrade.
        grid.confirmScreenUp = true; grid.hoveredCard = mod;
        Card ui = new Card(mod.cardID); ui.name = "模组牌+"; ui.damage = 27; ui.upgrades = 1;
        grid.upgradePreviewCard = ui;
        copies = new Copies(); enriched = state(grid);
        GridCardPreviews.addGridState(enriched, grid, GridCardPreviewsSmoke::convert, copies);
        @SuppressWarnings("unchecked") Map<String, Object> confirmation = (Map<String, Object>) enriched.get("confirmation_card");
        check(confirmation.get("uuid").equals(mod.uuid.toString()) && confirmation.get("cost").equals(1)
                && confirmation.get("type").equals("ATTACK") && confirmation.get("description").equals("造成6点伤害。"),
                "confirmation contains full original card, not the empty selectedCards");
        check(preview(confirmation).get("description").equals("造成27点伤害。")
                && confirmation.get("upgrade_preview_source").equals("game_ui"), "live UI preview has priority");
        check(copies.copies == 1 && mod.upgrades == 0 && ui.upgrades == 1, "mod original and existing preview are not modified");
        grid.upgradePreviewCard = null; enriched = state(grid);
        GridCardPreviews.addGridState(enriched, grid, GridCardPreviewsSmoke::convert, new Copies());
        check(preview(card(enriched, 1)) == null && card(enriched, 1).get("upgrade_preview_status").equals("unavailable"),
                "missing confirmation preview never invents a fallback");

        grid.forUpgrade = false; enriched = state(grid); copies = new Copies();
        GridCardPreviews.addGridState(enriched, grid, GridCardPreviewsSmoke::convert, copies);
        check(copies.copies == 0 && !card(enriched, 0).containsKey("upgrade_preview"), "non-upgrade grids have no preview");
        check(((Map<?, ?>) enriched.get("confirmation_card")).containsKey("description"), "purge/transform confirmation is also complete");
        grid.hoveredCard = null; enriched = state(grid);
        GridCardPreviews.addGridState(enriched, grid, GridCardPreviewsSmoke::convert, copies);
        check(enriched.get("confirmation_card") == null, "missing live target remains unknown");

        grid.forUpgrade = true; grid.confirmScreenUp = false; enriched = state(grid);
        Copies unsafe = new Copies() { @Override public Object copy(Object card) { return card; } };
        GridCardPreviews.addGridState(enriched, grid, GridCardPreviewsSmoke::convert, unsafe);
        check(unsafe.upgrades == 0 && first.upgrades == 0 && preview(card(enriched, 0)) == null,
                "copy returning original is rejected before upgrade");
        enriched = state(grid); card(enriched, 0).put("uuid", "wrong-order"); copies = new Copies();
        GridCardPreviews.addGridState(enriched, grid, GridCardPreviewsSmoke::convert, copies);
        check(copies.copies == 0 && preview(card(enriched, 0)) == null, "identity mismatch cannot enrich wrong candidate");
        check(GridCardPreviews.builtInCardClass(Class.forName("com.megacrit.cardcrawl.cards.red.Strike_Red", false,
                GridCardPreviewsSmoke.class.getClassLoader())), "actual game card origin supported");
        check(!GridCardPreviews.builtInCardClass(Card.class), "arbitrary mod origin is not a built-in card");
        checkPatchedClassOrigin();
        System.out.println("PASS grid upgrade preview / copy isolation / UI priority / mod unknown / complete confirmation / stable identity / null-domain loader");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
