package actionrecorder.runtime;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Raw field projection, with cached lookup and null for unavailable values. */
public final class RuntimeStateFields {
    private static final String[][] CARD_FLAGS = {
            {"retain", "retain"}, {"self_retain", "selfRetain"},
            {"free_to_play_once", "freeToPlayOnce"},
            {"is_cost_modified", "isCostModified"},
            {"is_cost_modified_for_turn", "isCostModifiedForTurn"},
            {"is_innate", "isInnate"}, {"purge_on_use", "purgeOnUse"},
            {"exhaust_on_use_once", "exhaustOnUseOnce"},
            {"in_bottle_flame", "inBottleFlame"},
            {"in_bottle_lightning", "inBottleLightning"},
            {"in_bottle_tornado", "inBottleTornado"}
    };
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<Map<String, Field>>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> fields = new HashMap<String, Field>();
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (fields.containsKey(field.getName())) continue;
                    try {
                        field.setAccessible(true);
                        fields.put(field.getName(), field);
                    } catch (RuntimeException ignored) { }
                }
            }
            return fields;
        }
    };

    private RuntimeStateFields() { }

    public static Object read(Object source, String fieldName) {
        if (source == null) return null;
        Field field = FIELDS.get(source.getClass()).get(fieldName);
        try { return field == null ? null : field.get(source); }
        catch (IllegalAccessException | RuntimeException ignored) { return null; }
    }

    public static void addCardFlags(Map<String, Object> result, Object card) {
        for (String[] field : CARD_FLAGS) result.put(field[0], read(card, field[1]));
    }

    public static void addPlayerFields(Map<String, Object> result, Object player) {
        Object energy = read(player, "energy");
        result.put("base_energy_per_turn", read(energy, "energyMaster"));
        result.put("energy_per_turn", read(energy, "energy"));
        result.put("master_hand_size", read(player, "masterHandSize"));
        result.put("game_hand_size", read(player, "gameHandSize"));
        result.put("cards_played_this_turn", read(player, "cardsPlayedThisTurn"));
        Object stance = read(player, "stance");
        Map<String, Object> value = null;
        if (stance != null) {
            value = new HashMap<String, Object>();
            value.put("id", read(stance, "ID"));
            value.put("name", read(stance, "name"));
            value.put("description", read(stance, "description"));
        }
        result.put("stance", value);
    }

    /** One-card preview keeps the actual target in hoveredCard, not selectedCards. */
    @SuppressWarnings("unchecked")
    public static <T> List<T> gridConfirmationCards(Object screen) {
        if (Boolean.TRUE.equals(read(screen, "confirmScreenUp"))) {
            Object target = read(screen, "hoveredCard");
            // No previous selection/history fallback: use only the live preview object.
            return target == null ? null : Collections.singletonList((T) target);
        }
        Object selected = read(screen, "selectedCards");
        return selected instanceof List ? new ArrayList<T>((List<T>) selected) : null;
    }
}
