package actionrecorder.patches;

import actionrecorder.runtime.ActionRecorderRuntime;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.neow.NeowEvent;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rewards.chests.AbstractChest;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.screens.select.BossRelicSelectScreen;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;
import com.megacrit.cardcrawl.ui.campfire.DigOption;
import com.megacrit.cardcrawl.ui.campfire.LiftOption;
import com.megacrit.cardcrawl.ui.campfire.RecallOption;
import com.megacrit.cardcrawl.ui.campfire.RestOption;
import com.megacrit.cardcrawl.ui.campfire.SmithOption;
import com.megacrit.cardcrawl.ui.campfire.TokeOption;
import com.megacrit.cardcrawl.ui.panels.PotionPopUp;
import com.megacrit.cardcrawl.ui.buttons.ProceedButton;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

import java.lang.reflect.Field;

/**
 * Small, explicit patches for decisions that have a stable semantic boundary.
 * More screens will be added only after their method signatures are verified
 * against the installed game jar.
 */
public final class DecisionPatches {
    private DecisionPatches() {
    }

    @SpirePatch(clz = AbstractDungeon.class, method = "setCurrMapNode")
    public static class MapNodeSelection {
        @SpirePrefixPatch
        public static void prefix(MapRoomNode node) {
            if (node == null) {
                return;
            }
            ActionRecorderRuntime.getInstance().recordAction(
                    "MAP:x=" + node.x + ":y=" + node.y,
                    "map_node_selected",
                    "\"x\":" + node.x + ",\"y\":" + node.y);
        }
    }

    @SpirePatch(clz = CardRewardScreen.class, method = "acquireCard")
    public static class CardRewardSelection {
        @SpirePostfixPatch
        public static void postfix(CardRewardScreen screen, AbstractCard card) {
            if (card == null) {
                return;
            }
            int index = screen == null || screen.rewardGroup == null
                    ? -1 : screen.rewardGroup.indexOf(card);
            ActionRecorderRuntime.getInstance().recordAction(
                    index < 0 ? "CHOOSE:card_id=" + card.cardID : "CHOOSE:index=" + index,
                    "card_reward_selected",
                    "\"card_id\":" + quote(card.cardID)
                            + ",\"card_name\":" + quote(card.name)
                            + ",\"card_uuid\":" + quote(String.valueOf(card.uuid)));
        }
    }

    @SpirePatch(clz = CardRewardScreen.class, method = "skippedCards")
    public static class CardRewardSkip {
        @SpirePostfixPatch
        public static void postfix(CardRewardScreen screen) {
            ActionRecorderRuntime.getInstance().recordAction(
                    "SKIP",
                    "card_reward_skipped",
                    "");
        }
    }

    @SpirePatch(clz = CardRewardScreen.class, method = "open")
    public static class CardRewardOpened {
        @SpirePostfixPatch
        public static void postfix(CardRewardScreen screen) {
            ActionRecorderRuntime.getInstance().recordAction(
                    "OPEN:CARD_REWARD", "card_reward_opened", "");
        }
    }

    @SpirePatch(clz = ShopScreen.class, method = "purchaseCard")
    public static class ShopCardPurchase {
        @SpirePostfixPatch
        public static void postfix(ShopScreen screen, AbstractCard card) {
            if (card == null) {
                return;
            }
            ActionRecorderRuntime.getInstance().recordAction(
                    "SHOP:BUY_CARD:" + card.cardID,
                    "shop_card_purchased",
                    "\"card_id\":" + quote(card.cardID)
                            + ",\"card_name\":" + quote(card.name)
                            + ",\"card_uuid\":" + quote(String.valueOf(card.uuid)));
        }
    }

    @SpirePatch(clz = StoreRelic.class, method = "purchaseRelic")
    public static class ShopRelicPurchase {
        @SpirePostfixPatch
        public static void postfix(StoreRelic store) {
            if (store == null || store.relic == null) {
                return;
            }
            ActionRecorderRuntime.getInstance().recordAction(
                    "SHOP:BUY_RELIC:" + store.relic.relicId,
                    "shop_relic_purchased",
                    "\"relic_id\":" + quote(store.relic.relicId)
                            + ",\"relic_name\":" + quote(store.relic.name)
                            + ",\"price\":" + store.price);
        }
    }

    @SpirePatch(clz = StorePotion.class, method = "purchasePotion")
    public static class ShopPotionPurchase {
        @SpirePostfixPatch
        public static void postfix(StorePotion store) {
            if (store == null || store.potion == null) {
                return;
            }
            ActionRecorderRuntime.getInstance().recordAction(
                    "SHOP:BUY_POTION:" + store.potion.ID,
                    "shop_potion_purchased",
                    "\"potion_id\":" + quote(store.potion.ID)
                            + ",\"potion_name\":" + quote(store.potion.name)
                            + ",\"price\":" + store.price);
        }
    }

    @SpirePatch(clz = ShopScreen.class, method = "purgeCard")
    public static class ShopPurge {
        @SpirePostfixPatch
        public static void postfix() {
            ActionRecorderRuntime.getInstance().recordAction(
                    "SHOP:PURGE", "shop_card_purged", "");
        }
    }

    @SpirePatch(clz = ShopScreen.class, method = "open")
    public static class ShopOpened {
        @SpirePostfixPatch
        public static void postfix(ShopScreen screen) {
            ActionRecorderRuntime.getInstance().recordAction(
                    "OPEN:SHOP", "shop_opened", "");
        }
    }

    @SpirePatch(clz = GameActionManager.class, method = "endTurn")
    public static class EndTurn {
        @SpirePostfixPatch
        public static void postfix(GameActionManager manager) {
            ActionRecorderRuntime.getInstance().recordAction(
                    "END_TURN", "end_turn", "\"turn\":" + GameActionManager.turn);
        }
    }

    @SpirePatch(clz = GameActionManager.class, method = "addCardQueueItem",
            paramtypez = {CardQueueItem.class, boolean.class})
    public static class CardQueued {
        @SpirePostfixPatch
        public static void postfix(GameActionManager manager, CardQueueItem item, boolean autoplay) {
            ActionRecorderRuntime.getInstance().recordCardQueued(item);
        }
    }

    @SpirePatch(clz = AbstractEvent.class, method = "logInput")
    public static class EventOption {
        @SpirePostfixPatch
        public static void postfix(AbstractEvent event, int optionIndex) {
            ActionRecorderRuntime.getInstance().recordAction(
                    "CHOOSE:index=" + optionIndex,
                    "event_option_selected",
                    "\"option_index\":" + optionIndex
                            + ",\"event_class\":" + quote(event == null ? null : event.getClass().getName()));
        }
    }

    @SpirePatch(clz = NeowEvent.class, method = "buttonEffect", paramtypez = {int.class})
    public static class NeowOption {
        @SpirePostfixPatch
        public static void postfix(NeowEvent event, int optionIndex) {
            ActionRecorderRuntime.getInstance().recordAction(
                    "CHOOSE:index=" + optionIndex,
                    "neow_option_selected",
                    "\"option_index\":" + optionIndex);
        }
    }

    @SpirePatch(clz = RewardItem.class, method = "claimReward")
    public static class RewardClaim {
        @SpirePostfixPatch
        public static void postfix(RewardItem item, boolean claimed) {
            if (item == null || !claimed || item.type == null) {
                return;
            }
            String type = item.type.name().toLowerCase();
            String id = "REWARD:TAKE:" + item.type.name();
            String details = "\"reward_type\":" + quote(item.type.name())
                    + ",\"gold\":" + item.goldAmt;
            if (item.relic != null) {
                details += ",\"relic_id\":" + quote(item.relic.relicId)
                        + ",\"relic_name\":" + quote(item.relic.name);
            }
            if (item.potion != null) {
                details += ",\"potion_id\":" + quote(item.potion.ID)
                        + ",\"potion_name\":" + quote(item.potion.name);
            }
            ActionRecorderRuntime.getInstance().recordAction(id, "reward_" + type + "_claimed", details);
        }
    }

    @SpirePatch(clz = AbstractChest.class, method = "open", paramtypez = {boolean.class})
    public static class ChestOpen {
        @SpirePostfixPatch
        public static void postfix(AbstractChest chest, boolean bossChest) {
            if (chest == null) {
                return;
            }
            ActionRecorderRuntime.getInstance().recordAction(
                    "CHEST:OPEN:" + chest.getClass().getSimpleName(),
                    "chest_opened",
                    "\"boss_chest\":" + bossChest
                            + ",\"gold_reward\":" + chest.goldReward
                            + ",\"gold_amount\":" + chest.GOLD_AMT
                            + ",\"cursed\":" + chest.cursed);
        }
    }

    @SpirePatch(clz = BossRelicSelectScreen.class, method = "relicObtainLogic",
            paramtypez = {AbstractRelic.class})
    public static class BossRelicPick {
        @SpirePostfixPatch
        public static void postfix(BossRelicSelectScreen screen, AbstractRelic relic) {
            if (relic == null) {
                return;
            }
            ActionRecorderRuntime.getInstance().recordAction(
                    "CHOOSE:index=" + (screen == null || screen.relics == null
                            ? -1 : screen.relics.indexOf(relic)),
                    "boss_relic_selected",
                    "\"relic_id\":" + quote(relic.relicId)
                            + ",\"relic_name\":" + quote(relic.name));
        }
    }

    @SpirePatch(clz = GridCardSelectScreen.class, method = "update")
    public static class GridSelection {
        @SpirePostfixPatch
        public static void postfix(GridCardSelectScreen screen) {
            ActionRecorderRuntime.getInstance().recordGridSelection(screen);
        }
    }

    @SpirePatch(clz = HandCardSelectScreen.class, method = "update")
    public static class HandSelection {
        @SpirePostfixPatch
        public static void postfix(HandCardSelectScreen screen) {
            ActionRecorderRuntime.getInstance().recordHandSelection(screen);
        }
    }

    @SpirePatch(clz = RestOption.class, method = "useOption")
    public static class CampfireRest { @SpirePostfixPatch public static void postfix(RestOption option) { campfire(option, "REST"); } }
    @SpirePatch(clz = SmithOption.class, method = "useOption")
    public static class CampfireSmith { @SpirePostfixPatch public static void postfix(SmithOption option) { campfire(option, "SMITH"); } }
    @SpirePatch(clz = LiftOption.class, method = "useOption")
    public static class CampfireLift { @SpirePostfixPatch public static void postfix(LiftOption option) { campfire(option, "LIFT"); } }
    @SpirePatch(clz = TokeOption.class, method = "useOption")
    public static class CampfireToke { @SpirePostfixPatch public static void postfix(TokeOption option) { campfire(option, "TOKE"); } }
    @SpirePatch(clz = DigOption.class, method = "useOption")
    public static class CampfireDig { @SpirePostfixPatch public static void postfix(DigOption option) { campfire(option, "DIG"); } }
    @SpirePatch(clz = RecallOption.class, method = "useOption")
    public static class CampfireRecall { @SpirePostfixPatch public static void postfix(RecallOption option) { campfire(option, "RECALL"); } }

    private static void campfire(AbstractCampfireOption option, String action) {
        ActionRecorderRuntime.getInstance().recordAction(
                "CAMPFIRE:" + action, "campfire_action", "\"option\":" + quote(action));
    }

    @SpirePatch(clz = ProceedButton.class, method = "goToTreasureRoom")
    public static class ProceedTreasure { @SpirePostfixPatch public static void postfix(ProceedButton button) { proceed("TREASURE"); } }
    @SpirePatch(clz = ProceedButton.class, method = "goToTrueVictoryRoom")
    public static class ProceedTrueVictory { @SpirePostfixPatch public static void postfix(ProceedButton button) { proceed("TRUE_VICTORY"); } }
    @SpirePatch(clz = ProceedButton.class, method = "goToVictoryRoomOrTheDoor")
    public static class ProceedVictory { @SpirePostfixPatch public static void postfix(ProceedButton button) { proceed("VICTORY_OR_DOOR"); } }
    @SpirePatch(clz = ProceedButton.class, method = "goToDoubleBoss")
    public static class ProceedDoubleBoss { @SpirePostfixPatch public static void postfix(ProceedButton button) { proceed("DOUBLE_BOSS"); } }
    @SpirePatch(clz = ProceedButton.class, method = "goToDemoVictoryRoom")
    public static class ProceedDemoVictory { @SpirePostfixPatch public static void postfix(ProceedButton button) { proceed("DEMO_VICTORY"); } }
    private static void proceed(String destination) {
        ActionRecorderRuntime.getInstance().recordAction(
                "PROCEED:" + destination, "continue_button", "\"destination\":" + quote(destination));
    }

    @SpirePatch(clz = AbstractDungeon.class, method = "closeCurrentScreen")
    public static class CloseScreen {
        private static String screenBeforeClose = "";

        @SpirePrefixPatch
        public static void prefix() {
            screenBeforeClose = String.valueOf(AbstractDungeon.screen);
        }

        @SpirePostfixPatch
        public static void postfix() {
            boolean likelyLeave = "SHOP".equals(screenBeforeClose)
                    || "COMBAT_REWARD".equals(screenBeforeClose)
                    || "CARD_REWARD".equals(screenBeforeClose)
                    || "BOSS_REWARD".equals(screenBeforeClose);
            String actionId = likelyLeave ? "LEAVE" : "RETURN";
            String actionKind = likelyLeave ? "leave_button" : "return_button";
            ActionRecorderRuntime.getInstance().recordAction(
                    actionId, actionKind,
                    "\"screen_before_close\":" + quote(screenBeforeClose)
                            + ",\"screen_after_close\":" + quote(String.valueOf(AbstractDungeon.screen))
                            + ",\"previous_screen\":" + quote(String.valueOf(AbstractDungeon.previousScreen)));
        }
    }

    @SpirePatch(clz = PotionPopUp.class, method = "updateInput")
    public static class PotionInput {
        @SpirePrefixPatch
        public static void prefix(PotionPopUp popup) {
            Object top = field(popup, "hbTop");
            Object bottom = field(popup, "hbBot");
            com.megacrit.cardcrawl.potions.AbstractPotion potion =
                    (com.megacrit.cardcrawl.potions.AbstractPotion) field(popup, "potion");
            if (clicked(top)) {
                if (potion == null || !potion.targetRequired) {
                    ActionRecorderRuntime.getInstance().recordPotionAction(
                            "POTION_USE", "potion_use_requested", intField(popup, "slot"), potion);
                }
            } else if (clicked(bottom)) {
                ActionRecorderRuntime.getInstance().recordPotionAction(
                        "POTION_DISCARD", "potion_discarded", intField(popup, "slot"),
                        potion);
            }
        }
    }

    @SpirePatch(clz = PotionPopUp.class, method = "updateTargetMode")
    public static class PotionTargetInput {
        @SpirePrefixPatch
        public static void prefix(PotionPopUp popup) {
            if (!InputHelper.justClickedLeft || !boolField(popup, "targetMode")) {
                return;
            }
            Object hovered = field(popup, "hoveredMonster");
            if (!(hovered instanceof AbstractMonster)) {
                return;
            }
            ActionRecorderRuntime.getInstance().recordPotionUseTarget(
                    intField(popup, "slot"),
                    (com.megacrit.cardcrawl.potions.AbstractPotion) field(popup, "potion"),
                    (AbstractMonster) hovered);
        }
    }

    private static Object field(Object object, String name) {
        if (object == null) {
            return null;
        }
        try {
            Field value = object.getClass().getDeclaredField(name);
            value.setAccessible(true);
            return value.get(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int intField(Object object, String name) {
        Object value = field(object, name);
        return value instanceof Integer ? ((Integer) value).intValue() : -1;
    }

    private static boolean boolField(Object object, String name) {
        Object value = field(object, name);
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static boolean clicked(Object hitbox) {
        if (hitbox == null) {
            return false;
        }
        Object value = field(hitbox, "clicked");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    @SpirePatch(clz = BossRelicSelectScreen.class, method = "noPick")
    public static class BossRelicSkip {
        @SpirePostfixPatch
        public static void postfix(BossRelicSelectScreen screen) {
            ActionRecorderRuntime.getInstance().recordAction(
                    "SKIP", "boss_relic_skipped", "");
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
