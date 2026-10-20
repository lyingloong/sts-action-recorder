package actionrecorder.runtime;

import basemod.helpers.CardModifierManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Public grid cards and upgrade previews, without changing the source cards. */
public final class GridCardPreviews {
    private static final ClassValue<Boolean> BUILT_IN_CARD_CLASSES = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                if (type.getClassLoader() != AbstractCard.class.getClassLoader()
                        || !AbstractCard.class.isAssignableFrom(type)) return false;
                URL game = classResourceJar(AbstractCard.class);
                URL candidate = classResourceJar(type);
                return game != null && candidate != null
                        && game.toExternalForm().equals(candidate.toExternalForm());
            } catch (IOException | RuntimeException | LinkageError ignored) { return false; }
        }
    };

    /** Kept separate so copy isolation and unknown mod behavior can be tested without game assets. */
    interface CardCopies {
        boolean supports(Object card);
        Object copy(Object card);
        void upgrade(Object copy);
        void displayUpgrades(Object copy);
    }

    private static final CardCopies GAME_COPIES = new CardCopies() {
        @Override public boolean supports(Object card) {
            if (!(card instanceof AbstractCard) || !builtInCardClass(card.getClass())) return false;
            try {
                // BaseMod copies modifiers during makeStatEquivalentCopy and may invoke mod callbacks.
                // Such cards can still expose an already-created UI preview, but are not synthesized here.
                return CardModifierManager.modifiers((AbstractCard) card).isEmpty();
            } catch (RuntimeException | LinkageError ignored) { return false; }
        }
        @Override public Object copy(Object card) { return ((AbstractCard) card).makeStatEquivalentCopy(); }
        @Override public void upgrade(Object copy) { ((AbstractCard) copy).upgrade(); }
        @Override public void displayUpgrades(Object copy) { ((AbstractCard) copy).displayUpgrades(); }
    };

    private GridCardPreviews() { }

    public static void addGridState(Map<String, Object> result, Object screen,
            Function<Object, Object> converter) {
        addGridState(result, screen, converter, GAME_COPIES);
    }

    static void addGridState(Map<String, Object> result, Object screen,
            Function<Object, Object> converter, CardCopies copies) {
        if (result == null || screen == null) return;
        boolean confirming = Boolean.TRUE.equals(RuntimeStateFields.read(screen, "confirmScreenUp"));
        boolean upgrading = Boolean.TRUE.equals(RuntimeStateFields.read(screen, "forUpgrade"));
        Object target = confirming ? RuntimeStateFields.read(screen, "hoveredCard") : null;
        Map<String, Object> confirmation = convert(target, converter);
        result.put("confirmation_card", confirmation);
        if (!upgrading) return;

        addCards(result.get("cards"), RuntimeStateFields.read(
                RuntimeStateFields.read(screen, "targetGroup"), "group"), screen, converter, copies);
        addCards(result.get("selected_cards"), RuntimeStateFields.read(screen, "selectedCards"),
                screen, converter, copies);
        if (confirmation != null) addPreview(confirmation, target, screen, converter, copies);
    }

    @SuppressWarnings("unchecked")
    private static void addCards(Object serialized, Object originals, Object screen,
            Function<Object, Object> converter, CardCopies copies) {
        if (!(serialized instanceof List)) return;
        List<?> values = (List<?>) serialized;
        List<?> cards = originals instanceof List ? (List<?>) originals : null;
        boolean sameSize = cards != null && cards.size() == values.size();
        for (int i = 0; i < values.size(); i++) {
            if (!(values.get(i) instanceof Map)) continue;
            Map<String, Object> value = (Map<String, Object>) values.get(i);
            Object card = sameSize ? cards.get(i) : null;
            if (card != null && sameCard(value, card)) addPreview(value, card, screen, converter, copies);
            else unavailable(value, "unavailable");
        }
    }

    private static boolean sameCard(Map<String, Object> value, Object card) {
        Object uuid = RuntimeStateFields.read(card, "uuid");
        Object id = RuntimeStateFields.read(card, "cardID");
        return uuid != null && id != null && String.valueOf(uuid).equals(value.get("uuid"))
                && id.equals(value.get("id"));
    }

    private static void addPreview(Map<String, Object> value, Object card, Object screen,
            Function<Object, Object> converter, CardCopies copies) {
        Object preview;
        String source;
        if (Boolean.TRUE.equals(RuntimeStateFields.read(screen, "confirmScreenUp"))
                && RuntimeStateFields.read(screen, "hoveredCard") == card) {
            // The game already built and upgraded the right-hand UI card. Never upgrade it again.
            preview = RuntimeStateFields.read(screen, "upgradePreviewCard");
            source = "game_ui";
        } else {
            if (!copies.supports(card)) {
                unavailable(value, "unsupported_card");
                return;
            }
            try {
                preview = copies.copy(card);
                // Check isolation before requesting the next upgrade.
                if (preview == null || preview == card || preview.getClass() != card.getClass()) {
                    unavailable(value, "unavailable");
                    return;
                }
                copies.upgrade(preview);
                copies.displayUpgrades(preview);
            } catch (RuntimeException | LinkageError ignored) {
                unavailable(value, "unavailable");
                return;
            }
            source = "game_copy";
        }
        Map<String, Object> projected = preview == card ? null : convert(preview, converter);
        if (projected == null) {
            unavailable(value, "unavailable");
            return;
        }
        // A preview is an effect projection, not an independently selectable card identity.
        // Copies generate random UUIDs; omitting them keeps repeated STATE observations stable.
        projected.remove("uuid");
        projected.remove("upgrade_preview");
        projected.remove("upgrade_preview_source");
        projected.remove("upgrade_preview_status");
        value.put("upgrade_preview", projected);
        value.put("upgrade_preview_source", source);
        value.put("upgrade_preview_status", "available");
    }

    private static void unavailable(Map<String, Object> value, String status) {
        value.put("upgrade_preview", null);
        value.put("upgrade_preview_source", null);
        value.put("upgrade_preview_status", status);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> convert(Object card, Function<Object, Object> converter) {
        if (card == null) return null;
        try {
            Object value = converter.apply(card);
            return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : null;
        } catch (RuntimeException | LinkageError ignored) { return null; }
    }

    /** No calls into arbitrary mod copy/upgrade methods while merely observing candidates. */
    static boolean builtInCardClass(Class<?> type) {
        if (type == null || !type.getName().startsWith("com.megacrit.cardcrawl.cards.")) return false;
        return BUILT_IN_CARD_CLASSES.get(type);
    }

    private static URL classResourceJar(Class<?> type) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        // ModTheSpire defines patched classes without a CodeSource. The class
        // resources still identify their original JAR, even after patching.
        URL resource = type.getResource("/" + entry);
        if (resource == null || !"jar".equals(resource.getProtocol())) return null;
        URLConnection connection = resource.openConnection();
        if (!(connection instanceof JarURLConnection)) return null;
        JarURLConnection jar = (JarURLConnection) connection;
        return entry.equals(jar.getEntryName()) ? jar.getJarFileURL() : null;
    }
}
