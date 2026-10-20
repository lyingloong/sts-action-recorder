package actionrecorder.runtime;

import basemod.BaseMod;
import basemod.abstracts.DynamicVariable;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.helpers.PowerTip;
import com.megacrit.cardcrawl.helpers.GameDictionary;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only projection of game text. Never calls applyPowers/upgrade/use. */
public final class RuntimeDescriptions {
    private static final Pattern VARIABLE = Pattern.compile("!([^!]+)!");
    private RuntimeDescriptions() { }

    public static String plain(String value) {
        if (value == null) return null;
        return value.replaceAll("\\bNL\\b", "\n")
                .replaceAll("#[rgbypow]", "")
                .replaceAll("\\[#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})\\]", "")
                .replace("[]", "")
                .replace("~", "").replace("@", "")
                .replace("[E]", "能量").replace("[R]", "红色能量")
                .replace("[G]", "绿色能量").replace("[B]", "蓝色能量")
                .replace("[W]", "紫色能量").trim();
    }

    public static void addCard(Map<String, Object> output, AbstractCard card) {
        output.put("raw_description", card.rawDescription);
        ArrayList<String> unresolved = new ArrayList<>();
        output.put("description", renderCard(card, unresolved));
        output.put("description_source", "game_runtime");
        output.put("description_complete", card.rawDescription != null && unresolved.isEmpty());
        output.put("unresolved_description_variables", unresolved);
        output.put("multi_damage", card.multiDamage == null ? null : card.multiDamage.clone());
        output.put("keyword_tooltips", keywordTips(card.keywords, GameDictionary.keywords, GameDictionary.parentWord));
    }

    public static String renderCard(AbstractCard card, ArrayList<String> unresolved) {
        if (card.rawDescription == null) return null;
        Matcher matcher = VARIABLE.matcher(card.rawDescription);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Integer value = null;
            try {
                DynamicVariable variable = BaseMod.cardDynamicVariableMap == null
                        ? null : BaseMod.cardDynamicVariableMap.get(key);
                if (variable != null) value = variable.value(card);
                else if ("D".equals(key)) value = card.damage;
                else if ("B".equals(key)) value = card.block;
                else if ("M".equals(key)) value = card.magicNumber;
                // Deck/reward cards can have uninitialized preview values (-1).
                // Use the actual object's base value, while preserving a live
                // zero from combat. Custom variables may legitimately be negative.
                if (value != null && value < 0
                        && ("D".equals(key) || "B".equals(key) || "M".equals(key))) {
                    int base = variable != null ? variable.baseValue(card)
                            : "D".equals(key) ? card.baseDamage
                            : "B".equals(key) ? card.baseBlock : card.baseMagicNumber;
                    value = builtinValue(value, base);
                }
            } catch (RuntimeException ignored) {
                // A custom variable must not break game execution/recording.
            }
            if (value == null) {
                if (!unresolved.contains(key)) unresolved.add(key);
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
            }
        }
        matcher.appendTail(result);
        return plain(result.toString());
    }

    private static Integer builtinValue(int preview, int base) {
        if (preview >= 0) return preview;
        return base >= 0 ? Integer.valueOf(base) : null;
    }

    public static void addPotion(Map<String, Object> output, AbstractPotion potion) {
        output.put("raw_description", potion.description);
        LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        String primary = plain(potion.description);
        if (primary != null && !primary.isEmpty()) descriptions.add(primary);
        ArrayList<Map<String, Object>> tips = new ArrayList<>();
        if (potion.tips != null) {
            for (PowerTip tip : potion.tips) {
                if (tip == null) continue;
                java.util.LinkedHashMap<String, Object> value = new java.util.LinkedHashMap<>();
                value.put("header", plain(tip.header));
                value.put("body", plain(tip.body));
                tips.add(value);
                String body = plain(tip.body);
                if (body != null && !body.isEmpty()) descriptions.add(body);
            }
        }
        output.put("description", descriptions.isEmpty() ? null : String.join("\n", descriptions));
        output.put("tooltips", tips);
        output.put("description_source", "game_runtime");
        output.put("target", potion.targetRequired);
        // Actual secondary game tips, not text/name matches against a dictionary.
        output.put("keyword_tooltips", secondaryTips(potion.tips, potion.name, potion.description));
    }

    public static void addText(Map<String, Object> output, String text) {
        output.put("raw_description", text);
        output.put("description", plain(text));
        output.put("description_source", "game_runtime");
        // AbstractCreature.renderPowerTips uses this exact object's name and
        // description. It does NOT look up a same-named generic keyword.
        output.put("keyword_tooltips", new ArrayList<Map<String, Object>>());
    }

    public static void addRelic(Map<String, Object> output, com.megacrit.cardcrawl.relics.AbstractRelic relic) {
        addText(output, relic.description);
        output.put("keyword_tooltips", secondaryTips(relic.tips, relic.name, relic.description));
    }

    /** Game-associated keywords only; never scan names or arbitrary prose. */
    public static List<Map<String, Object>> keywordTips(Iterable<String> declared,
            Map<String, String> dictionary, Map<String, String> parents) {
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        if (dictionary == null) return result;
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        if (declared != null) for (String word : declared) {
            if (word != null) wanted.add(word.toLowerCase(Locale.ROOT));
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String word : wanted) {
            String parent = parents == null ? null : parents.get(word);
            String header = parent == null ? word : parent;
            String body = dictionary.get(header);
            if (body == null) body = dictionary.get(word);
            body = plain(body);
            if (body == null || body.isEmpty() || !seen.add(header + "\n" + body)) continue;
            LinkedHashMap<String, Object> tip = new LinkedHashMap<>();
            tip.put("header", plain(header)); tip.put("body", body);
            result.add(tip);
        }
        return result;
    }

    public static List<Map<String, Object>> secondaryTips(Iterable<PowerTip> tips, String name, String description) {
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (tips != null) for (PowerTip tip : tips) {
            if (tip == null) continue;
            String header = plain(tip.header), body = plain(tip.body);
            if (header == null || body == null || body.isEmpty() || header.equals(plain(name))
                    || body.equals(plain(description)) || !seen.add(header + "\n" + body)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("header", header); item.put("body", body); result.add(item);
        }
        return result;
    }
}
