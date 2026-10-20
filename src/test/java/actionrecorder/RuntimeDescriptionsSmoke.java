package actionrecorder;

import actionrecorder.runtime.RuntimeDescriptions;
import java.lang.reflect.Method;

/** Test numeric fallback and game text formatting without asset initialization. */
public final class RuntimeDescriptionsSmoke {
    public static void main(String[] args) throws Exception {
        Method value = RuntimeDescriptions.class.getDeclaredMethod("builtinValue", int.class, int.class);
        value.setAccessible(true);
        check(Integer.valueOf(6).equals(value.invoke(null, -1, 6)), "deck preview falls back to base");
        check(Integer.valueOf(9).equals(value.invoke(null, -1, 9)), "upgraded base is preserved");
        check(Integer.valueOf(0).equals(value.invoke(null, 0, 6)), "live zero is preserved");
        check(Integer.valueOf(7).equals(value.invoke(null, 7, 5)), "modified live preview is preserved");
        check(value.invoke(null, -1, -1) == null, "unknown remains unavailable");
        check(RuntimeDescriptions.plain("#rDeal 6 damage. NL Gain [R].")
                .equals("Deal 6 damage. \n Gain 红色能量."), "formatting is removed and numbers retained");
        check(RuntimeDescriptions.plain("获得 2 [E]。 [R][G][B][W] Energy ENERGY E.")
                .equals("获得 2 能量。 红色能量绿色能量蓝色能量紫色能量 Energy ENERGY E."),
                "only energy icons are localized; colors, count and ordinary words survive");
        check(RuntimeDescriptions.plain("[E][E] [unknown]").equals("能量能量 [unknown]"),
                "repeated and unknown symbols retain their meaning");
        check(RuntimeDescriptions.plain("!unknown!").equals("!unknown!"), "unknown token is not erased");
        java.util.Map<String, String> dictionary = new java.util.LinkedHashMap<>();
        dictionary.put("敏捷", "敏捷增加你从牌中获得的格挡值。");
        dictionary.put("不能被打出", "不能从手牌打出这张牌。");
        dictionary.put("block", "Block prevents damage.");
        dictionary.put("blocks", "Block prevents damage.");
        java.util.Map<String, String> parents = new java.util.HashMap<>(); parents.put("blocks", "block");
        java.util.List<java.util.Map<String, Object>> tips = RuntimeDescriptions.keywordTips(
                java.util.Arrays.asList("敏捷", "不能被打出", "block", "blocks"), dictionary, parents);
        check(tips.size() == 3, "localized and declared keywords should deduplicate aliases");
        check(RuntimeDescriptions.keywordTips(null, dictionary, parents).isEmpty(), "prose never supplies guessed keywords");
        check(RuntimeDescriptions.keywordTips(java.util.Arrays.asList("unknown"), dictionary, parents).isEmpty(), "no invented explanation");
        java.util.Map<String, Object> power = new java.util.LinkedHashMap<>();
        power.put("name", "愤怒");
        RuntimeDescriptions.addText(power, "每打出一张攻击牌，获得5点格挡。耗能随机变化。");
        check(((java.util.List<?>) power.get("keyword_tooltips")).isEmpty(), "Power names and prose never map to stance / transform");
        java.util.List<com.megacrit.cardcrawl.helpers.PowerTip> associated = java.util.Arrays.asList(
                new com.megacrit.cardcrawl.helpers.PowerTip("药水名称", "主描述"),
                new com.megacrit.cardcrawl.helpers.PowerTip("敏捷", "加格挡"),
                new com.megacrit.cardcrawl.helpers.PowerTip("敏捷", "加格挡"));
        check(RuntimeDescriptions.secondaryTips(associated, "药水名称", "主描述").size() == 1,
                "actual secondary tips only; main tip excluded, duplicate removed");
        System.out.println("PASS runtime numeric fallback / live zero / unavailable values / game text formatting");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
