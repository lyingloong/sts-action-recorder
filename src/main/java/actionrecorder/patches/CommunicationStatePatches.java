package actionrecorder.patches;

import actionrecorder.runtime.ActionRecorderRuntime;
import actionrecorder.runtime.RuntimeStateFields;
import actionrecorder.runtime.RuntimeDescriptions;
import actionrecorder.runtime.MatchGameState;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import javassist.expr.NewExpr;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.powers.AbstractPower;
import java.util.ArrayList;
import java.util.Map;

/**
 * Adds fields that the optional CommunicationMod converter does not expose.
 * The patch is declared by class name so ActionRecorder remains independent
 * of CommunicationMod at compile time and still loads without that mod.
 */
public final class CommunicationStatePatches {
    private static boolean fieldsLookedUp;
    private static Field rubyKeyField;
    private static Field emeraldKeyField;
    private static Field sapphireKeyField;
    private static Method cardConverter;

    private CommunicationStatePatches() {
    }

    @SpirePatch(
            cls = "communicationmod.GameStateConverter",
            method = "getCommunicationState",
            requiredModId = "CommunicationMod",
            optional = true)
    public static class AddHeartKeys {
        @SpirePostfixPatch
        public static String postfix(String __result) {
            return addKeys(__result);
        }
    }

    /** Preserve nulls at the source serializer, including nested maps/board cards. */
    @SpirePatch(cls = "communicationmod.GameStateConverter", method = "getCommunicationState",
            requiredModId = "CommunicationMod", optional = true)
    public static class PreserveNullValues {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(NewExpr expression) throws CannotCompileException {
                    if ("com.autoplay.gson.Gson".equals(expression.getClassName())) {
                        expression.replace("{ $_ = new com.autoplay.gson.GsonBuilder().serializeNulls().create(); }");
                    }
                }
            };
        }
    }

    /** Preserve runtime card numbers; these are not static knowledge entries. */
    @SpirePatch(cls = "communicationmod.GameStateConverter", method = "convertCardToJson",
            paramtypez = {AbstractCard.class}, requiredModId = "CommunicationMod", optional = true)
    public static class CardRuntimeFields {
        @SpirePostfixPatch public static HashMap<String, Object> postfix(
                HashMap<String, Object> __result, AbstractCard card) {
            if (__result == null || card == null) return __result;
            __result.put("base_damage", card.baseDamage);
            __result.put("damage", card.damage);
            __result.put("base_block", card.baseBlock);
            __result.put("block", card.block);
            __result.put("base_magic_number", card.baseMagicNumber);
            __result.put("magic_number", card.magicNumber);
            __result.put("base_cost", card.cost);
            __result.put("cost_for_turn", card.costForTurn);
            __result.put("target", card.target.name());
            RuntimeDescriptions.addCard(__result, card);
            __result.put("color", card.color.name());
            RuntimeStateFields.addCardFlags(__result, card);
            return __result;
        }
    }

    @SpirePatch(cls = "communicationmod.GameStateConverter", method = "convertRelicToJson",
            paramtypez = {AbstractRelic.class}, requiredModId = "CommunicationMod", optional = true)
    public static class RelicDescription {
        @SpirePostfixPatch public static HashMap<String, Object> postfix(
                HashMap<String, Object> __result, AbstractRelic relic) {
            if (__result != null && relic != null) RuntimeDescriptions.addText(__result, relic.description);
            return __result;
        }
    }

    @SpirePatch(cls = "communicationmod.GameStateConverter", method = "convertPotionToJson",
            paramtypez = {AbstractPotion.class}, requiredModId = "CommunicationMod", optional = true)
    public static class PotionDescription {
        @SpirePostfixPatch public static HashMap<String, Object> postfix(
                HashMap<String, Object> __result, AbstractPotion potion) {
            if (__result != null && potion != null) RuntimeDescriptions.addPotion(__result, potion);
            return __result;
        }
    }

    @SpirePatch(cls = "communicationmod.GameStateConverter", method = "convertCreaturePowersToJson",
            paramtypez = {AbstractCreature.class}, requiredModId = "CommunicationMod", optional = true)
    public static class PowerDescriptions {
        @SuppressWarnings("unchecked")
        @SpirePostfixPatch public static ArrayList<Object> postfix(
                ArrayList<Object> __result, AbstractCreature creature) {
            if (__result == null || creature == null || creature.powers == null) return __result;
            for (Object entry : __result) {
                if (!(entry instanceof Map)) continue;
                Map<String, Object> value = (Map<String, Object>) entry;
                for (AbstractPower power : creature.powers) {
                    if (power != null && power.ID != null && power.ID.equals(value.get("id"))) {
                        RuntimeDescriptions.addText(value, power.description);
                        break;
                    }
                }
            }
            return __result;
        }
    }

    @SpirePatch(cls = "communicationmod.GameStateConverter", method = "convertPlayerToJson",
            paramtypez = {AbstractPlayer.class}, requiredModId = "CommunicationMod", optional = true)
    public static class PlayerRuntimeFields {
        @SpirePostfixPatch public static HashMap<String, Object> postfix(
                HashMap<String, Object> __result, AbstractPlayer player) {
            if (__result != null) RuntimeStateFields.addPlayerFields(__result, player);
            return __result;
        }
    }

    @SpirePatch(cls = "communicationmod.GameStateConverter", method = "getEventState",
            requiredModId = "CommunicationMod", optional = true)
    public static class MatchGamePublicState {
        @SpirePostfixPatch public static HashMap<String, Object> postfix(HashMap<String, Object> __result) {
            if (__result == null || com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom() == null) return __result;
            Object event = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom().event;
            if (event instanceof com.megacrit.cardcrawl.events.shrines.GremlinMatchGame) {
                __result.put("match_game", MatchGameState.snapshot(event, CommunicationStatePatches::convertPublicCard));
            }
            return __result;
        }
    }

    private static Object convertPublicCard(Object card) {
        try {
            if (cardConverter == null) {
                cardConverter = Class.forName("communicationmod.GameStateConverter")
                        .getDeclaredMethod("convertCardToJson", AbstractCard.class);
                cardConverter.setAccessible(true);
            }
            return cardConverter.invoke(null, card);
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    @SpirePatch(cls = "communicationmod.GameStateConverter", method = "getHandSelectState",
            requiredModId = "CommunicationMod", optional = true)
    public static class HandSelectionContext {
        @SpirePostfixPatch public static HashMap<String, Object> postfix(HashMap<String, Object> __result) {
            com.megacrit.cardcrawl.screens.select.HandCardSelectScreen screen =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.handCardSelectScreen;
            if (__result == null || screen == null) return __result;
            __result.put("selection_reason", screen.selectionReason);
            __result.put("up_to", screen.upTo);
            __result.put("any_number", privateValue(screen, "anyNumber"));
            __result.put("for_upgrade", privateValue(screen, "forUpgrade"));
            __result.put("for_transform", privateValue(screen, "forTransform"));
            return __result;
        }
    }

    @SpirePatch(cls = "communicationmod.GameStateConverter", method = "getGridState",
            requiredModId = "CommunicationMod", optional = true)
    public static class GridSelectionContext {
        @SpirePostfixPatch public static HashMap<String, Object> postfix(HashMap<String, Object> __result) {
            com.megacrit.cardcrawl.screens.select.GridCardSelectScreen screen =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen;
            if (__result == null || screen == null) return __result;
            __result.put("selection_reason", privateValue(screen, "tipMsg"));
            __result.put("confirm_screen_up", screen.confirmScreenUp);
            __result.put("any_number", screen.anyNumber);
            __result.put("for_clarity", screen.forClarity);
            Object hovered = privateValue(screen, "hoveredCard");
            HashMap<String, Object> confirmation = null;
            if (screen.confirmScreenUp && hovered instanceof AbstractCard) {
                AbstractCard card = (AbstractCard) hovered;
                confirmation = new HashMap<String, Object>();
                confirmation.put("id", card.cardID);
                confirmation.put("name", card.name);
                confirmation.put("uuid", String.valueOf(card.uuid));
            }
            __result.put("confirmation_card", confirmation);
            return __result;
        }
    }

    private static Object privateValue(Object instance, String name) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException ignored) { return null; }
    }

    /**
     * Stamp the actual outgoing message, not a second converter invocation.
     * The same ID is visible in the pipe, local journal and action marker.
     */
    @SpirePatch(
            cls = "communicationmod.CommunicationMod",
            method = "sendGameState",
            requiredModId = "CommunicationMod",
            optional = true)
    public static class CachePublishedState {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if ("sendMessage".equals(call.getMethodName())) {
                        call.replace("{ $proceed(actionrecorder.runtime.ActionRecorderRuntime.getInstance()"
                                + ".capturePublishedState($1)); }");
                    }
                }
            };
        }
    }

    private static String addKeys(String result) {
        if (result == null || result.length() == 0) {
            return result;
        }
        try {
            JsonElement parsed = new JsonParser().parse(result);
            if (!parsed.isJsonObject()) {
                return result;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonElement gameState = root.get("game_state");
            if (gameState == null || !gameState.isJsonObject()) {
                return result;
            }
            Boolean ruby = readSetting("hasRubyKey");
            Boolean emerald = readSetting("hasEmeraldKey");
            Boolean sapphire = readSetting("hasSapphireKey");
            if (ruby == null && emerald == null && sapphire == null) {
                return result;
            }
            JsonObject keys = new JsonObject();
            if (ruby != null) keys.addProperty("ruby", ruby);
            if (emerald != null) keys.addProperty("emerald", emerald);
            if (sapphire != null) keys.addProperty("sapphire", sapphire);
            gameState.getAsJsonObject().add("keys", keys);
            return root.toString();
        } catch (Throwable ignored) {
            // State enrichment must never break CommunicationMod's protocol.
            return result;
        }
    }

    private static Boolean readSetting(String fieldName) {
        lookupFields();
        try {
            Field field = "hasRubyKey".equals(fieldName) ? rubyKeyField
                    : "hasEmeraldKey".equals(fieldName) ? emeraldKeyField : sapphireKeyField;
            return field == null ? null : field.getBoolean(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void lookupFields() {
        if (fieldsLookedUp) {
            return;
        }
        fieldsLookedUp = true;
        try {
            Class<?> settings = Class.forName("com.megacrit.cardcrawl.core.Settings");
            rubyKeyField = settings.getField("hasRubyKey");
            emeraldKeyField = settings.getField("hasEmeraldKey");
            sapphireKeyField = settings.getField("hasSapphireKey");
        } catch (Throwable ignored) {
            // Older game builds may not have the heart-key fields.
        }
    }
}
