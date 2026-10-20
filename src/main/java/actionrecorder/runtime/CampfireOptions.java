package actionrecorder.runtime;

import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Read-only projection of the same live options CommunicationMod can choose. */
public final class CampfireOptions {
    private static final Field LABEL_FIELD = gameField("label");
    private static final Field DESCRIPTION_FIELD = gameField("description");
    private static final Field USABLE_FIELD = gameField("usable");
    private static Method cachedMethod;
    private static Method cachedNameMethod;
    private static boolean methodLookedUp;

    private CampfireOptions() { }

    /** getRoomState is also used outside rest rooms; only enrich its rest result. */
    public static void addRoomState(Map<String, Object> roomState) {
        if (roomState == null || !roomState.containsKey("rest_options")) return;
        Object choices = roomState.get("rest_options");
        roomState.put("rest_option_details", choices instanceof List
                ? snapshot(choices, validOptions(), CampfireOptions::choiceName) : null);
    }

    /**
     * Positional join to the already published choices, without deriving names,
     * filtering a second time, or guessing an option's effect from player state.
     * A valid empty choice list produces []; an unavailable alignment produces null.
     */
    public static List<Map<String, Object>> snapshot(Object choicesValue, Object optionsValue,
            Function<Object, String> choiceName) {
        if (!(choicesValue instanceof List) || !(optionsValue instanceof List) || choiceName == null) return null;
        try {
            List<?> choices = new ArrayList<Object>((List<?>) choicesValue);
            List<?> options = new ArrayList<Object>((List<?>) optionsValue);
            if (choices.size() != options.size()) return null;
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < choices.size(); i++) {
                Object option = options.get(i);
                if (!(choices.get(i) instanceof String) || option == null) return null;
                // Use CM's own name function on these exact provider objects.
                // Counts alone cannot detect equally sized lists in a different order.
                if (!choices.get(i).equals(choiceName.apply(option))) return null;
                String rawDescription = text(readOptionField(option, "description", DESCRIPTION_FIELD));
                Map<String, Object> value = new LinkedHashMap<String, Object>();
                value.put("choice_index", i);
                value.put("choice", choices.get(i));
                value.put("name", text(readOptionField(option, "label", LABEL_FIELD)));
                value.put("description", RuntimeDescriptions.plain(rawDescription));
                value.put("raw_description", rawDescription);
                value.put("description_source", "game_runtime");
                Object usable = readOptionField(option, "usable", USABLE_FIELD);
                value.put("usable", usable instanceof Boolean ? usable : null);
                value.put("option_class", option.getClass().getName());
                result.add(value);
            }
            return result;
        } catch (RuntimeException | LinkageError ignored) {
            // Modded fields/collections must not break CommunicationMod's state.
            return null;
        }
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Field gameField(String name) {
        try {
            Field field = AbstractCampfireOption.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object readOptionField(Object option, String name, Field gameField) {
        if (!(option instanceof AbstractCampfireOption)) return RuntimeStateFields.read(option, name);
        try {
            // The UI and CM bind these fields to AbstractCampfireOption itself.
            // A mod's same-named child field is separate storage and not a fallback.
            return gameField == null ? null : gameField.get(option);
        } catch (IllegalAccessException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object validOptions() {
        Method method = validButtonsMethod();
        if (method == null) return null;
        try {
            // This is the read-only provider used by getRestRoomChoices and CHOOSE.
            return method.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static String choiceName(Object option) {
        validButtonsMethod();
        if (cachedNameMethod == null) return null;
        try {
            return text(cachedNameMethod.invoke(null, option));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static synchronized Method validButtonsMethod() {
        if (!methodLookedUp) {
            methodLookedUp = true;
            try {
                Class<?> choices = Class.forName("communicationmod.ChoiceScreenUtils", false,
                        CampfireOptions.class.getClassLoader());
                Method method = choices.getDeclaredMethod("getValidRestRoomButtons");
                Class<?> option = Class.forName("com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption", false,
                        CampfireOptions.class.getClassLoader());
                Method nameMethod = choices.getDeclaredMethod("getCampfireOptionName", option);
                method.setAccessible(true);
                nameMethod.setAccessible(true);
                cachedMethod = method;
                cachedNameMethod = nameMethod;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Optional CommunicationMod or an incompatible build is unavailable.
            }
        }
        return cachedMethod;
    }
}
