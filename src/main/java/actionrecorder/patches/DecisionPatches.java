package actionrecorder.patches;

import actionrecorder.runtime.ActionRecorderRuntime;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
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
import com.megacrit.cardcrawl.ui.buttons.EndTurnButton;
import com.megacrit.cardcrawl.ui.buttons.CancelButton;
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(CardRewardScreen screen, AbstractCard card) {
            if (card == null) {
                ActionRecorderRuntime.getInstance().discardDecision();
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(CardRewardScreen screen) {
            ActionRecorderRuntime.getInstance().recordAction(
                    "OPEN:CARD_REWARD", "card_reward_opened", "");
        }
    }

    @SpirePatch(clz = ShopScreen.class, method = "purchaseCard")
    public static class ShopCardPurchase {
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(ShopScreen screen, AbstractCard card) {
            if (card == null) {
                ActionRecorderRuntime.getInstance().discardDecision();
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(StoreRelic store) {
            if (store == null || store.relic == null) {
                ActionRecorderRuntime.getInstance().discardDecision();
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(StorePotion store) {
            if (store == null || store.potion == null) {
                ActionRecorderRuntime.getInstance().discardDecision();
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix() {
            ActionRecorderRuntime.getInstance().recordAction(
                    "SHOP:PURGE", "shop_card_purged", "");
        }
    }

    @SpirePatch(clz = ShopScreen.class, method = "open")
    public static class ShopOpened {
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(ShopScreen screen) {
            ActionRecorderRuntime.getInstance().recordAction(
                    "OPEN:SHOP", "shop_opened", "");
        }
    }

    @SpirePatch(clz = EndTurnButton.class, method = "disable", paramtypez = {boolean.class})
    public static class EndTurn {
        private static boolean wasEnabled;
        @SpirePrefixPatch
        public static void prefix(EndTurnButton button, boolean endTurn) {
            wasEnabled = endTurn && button.enabled;
            if (wasEnabled) { ActionRecorderRuntime.getInstance().beginDecision(); }
        }
        @SpirePostfixPatch
        public static void postfix(EndTurnButton button, boolean endTurn) {
            if (wasEnabled && endTurn && AbstractDungeon.player != null
                    && AbstractDungeon.player.endTurnQueued) {
                ActionRecorderRuntime.getInstance().recordAction(
                        "END_TURN", "end_turn", "\"turn\":" + GameActionManager.turn);
            } else if (wasEnabled) {
                ActionRecorderRuntime.getInstance().discardDecision();
            }
            wasEnabled = false;
        }
    }

    @SpirePatch(clz = AbstractPlayer.class, method = "playCard")
    public static class CardQueued {
        private static int queueSize;
        @SpirePrefixPatch
        public static void prefix(AbstractPlayer player) {
            queueSize = AbstractDungeon.actionManager.cardQueue.size();
            ActionRecorderRuntime.getInstance().beginDecision();
        }
        @SpirePostfixPatch
        public static void postfix(AbstractPlayer player) {
            if (AbstractDungeon.actionManager.cardQueue.size() > queueSize) {
                CardQueueItem item = AbstractDungeon.actionManager.cardQueue.get(queueSize);
                ActionRecorderRuntime.getInstance().recordCardQueued(item);
            } else {
                ActionRecorderRuntime.getInstance().discardDecision();
            }
        }
    }

    @SpirePatch(clz = AbstractEvent.class, method = "logInput")
    public static class EventOption {
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static boolean postfix(boolean claimed, RewardItem item) {
            if (item != null && claimed && item.type != null) {
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
            } else {
                ActionRecorderRuntime.getInstance().discardDecision();
            }
            return claimed;
        }
    }

    @SpirePatch(clz = AbstractChest.class, method = "open", paramtypez = {boolean.class})
    public static class ChestOpen {
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(AbstractChest chest, boolean bossChest) {
            if (chest == null) {
                ActionRecorderRuntime.getInstance().discardDecision();
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(BossRelicSelectScreen screen, AbstractRelic relic) {
            if (relic == null) {
                ActionRecorderRuntime.getInstance().discardDecision();
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
        private static boolean attempted;
        @SpirePrefixPatch
        public static void prefix() {
            attempted = selectionInput();
            if (attempted) { begin(); }
        }
        @SpirePostfixPatch
        public static void postfix(GridCardSelectScreen screen) {
            if (attempted && !ActionRecorderRuntime.getInstance().recordGridSelection(screen)) {
                ActionRecorderRuntime.getInstance().discardDecision();
            }
            attempted = false;
        }
    }

    @SpirePatch(clz = HandCardSelectScreen.class, method = "update")
    public static class HandSelection {
        private static boolean attempted;
        @SpirePrefixPatch
        public static void prefix() {
            attempted = selectionInput();
            if (attempted) { begin(); }
        }
        @SpirePostfixPatch
        public static void postfix(HandCardSelectScreen screen) {
            if (attempted && !ActionRecorderRuntime.getInstance().recordHandSelection(screen)) {
                ActionRecorderRuntime.getInstance().discardDecision();
            }
            attempted = false;
        }
    }

    private static boolean selectionInput() {
        return InputHelper.justClickedLeft || InputHelper.justClickedRight
                || (com.megacrit.cardcrawl.helpers.controller.CInputActionSet.select != null
                && com.megacrit.cardcrawl.helpers.controller.CInputActionSet.select.isJustPressed());
    }

    @SpirePatch(clz = RestOption.class, method = "useOption")
    public static class CampfireRest { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(RestOption option) { campfire(option, "REST"); } }
    @SpirePatch(clz = SmithOption.class, method = "useOption")
    public static class CampfireSmith { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(SmithOption option) { campfire(option, "SMITH"); } }
    @SpirePatch(clz = LiftOption.class, method = "useOption")
    public static class CampfireLift { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(LiftOption option) { campfire(option, "LIFT"); } }
    @SpirePatch(clz = TokeOption.class, method = "useOption")
    public static class CampfireToke { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(TokeOption option) { campfire(option, "TOKE"); } }
    @SpirePatch(clz = DigOption.class, method = "useOption")
    public static class CampfireDig { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(DigOption option) { campfire(option, "DIG"); } }
    @SpirePatch(clz = RecallOption.class, method = "useOption")
    public static class CampfireRecall { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(RecallOption option) { campfire(option, "RECALL"); } }

    private static void begin() { ActionRecorderRuntime.getInstance().beginDecision(); }

    private static void campfire(AbstractCampfireOption option, String action) {
        ActionRecorderRuntime.getInstance().recordAction(
                "CAMPFIRE:" + action, "campfire_action", "\"option\":" + quote(action));
    }

    @SpirePatch(clz = ProceedButton.class, method = "update")
    public static class ProceedInput {
        private static boolean candidate;
        private static boolean clickedBefore;
        private static boolean releasedOnButton;
        private static String beforeScreen;

        @SpirePrefixPatch
        public static void prefix(ProceedButton button) {
            Object hitbox = field(button, "hb");
            clickedBefore = clicked(hitbox);
            releasedOnButton = boolField(hitbox, "clickStarted")
                    && boolField(hitbox, "hovered") && InputHelper.justReleasedClickLeft;
            candidate = !boolField(button, "isHidden") && (clickedBefore || releasedOnButton
                    || (com.megacrit.cardcrawl.helpers.controller.CInputActionSet.proceed != null
                    && com.megacrit.cardcrawl.helpers.controller.CInputActionSet.proceed.isJustPressed()));
            if (candidate) {
                beforeScreen = String.valueOf(AbstractDungeon.screen);
                begin();
            }
        }

        @SpirePostfixPatch
        public static void postfix(ProceedButton button) {
            if (!candidate) { return; }
            if (!clickedBefore || !clicked(field(button, "hb"))) {
                ActionRecorderRuntime.getInstance().recordAction("PROCEED", "continue_button",
                        "\"screen_before\":" + quote(beforeScreen));
            } else {
                ActionRecorderRuntime.getInstance().discardDecision();
            }
            candidate = false;
        }
    }

    @SpirePatch(clz = CancelButton.class, method = "update")
    public static class CancelScreen {
        private static boolean candidate;
        private static boolean clickedBefore;
        private static boolean releasedOnButton;
        private static boolean escapeBefore;
        private static String beforeScreen;
        private static boolean upgradeConfirmationBefore;

        @SpirePrefixPatch
        public static void prefix(CancelButton button) {
            clickedBefore = button.hb.clicked;
            releasedOnButton = button.hb.clickStarted && button.hb.hovered
                    && InputHelper.justReleasedClickLeft;
            escapeBefore = InputHelper.pressedEscape;
            candidate = !button.isHidden && (clickedBefore || releasedOnButton || escapeBefore
                    || (com.megacrit.cardcrawl.helpers.controller.CInputActionSet.cancel != null
                    && com.megacrit.cardcrawl.helpers.controller.CInputActionSet.cancel.isJustPressed()));
            if (candidate) {
                beforeScreen = String.valueOf(AbstractDungeon.screen);
                upgradeConfirmationBefore = "GRID".equals(beforeScreen)
                        && AbstractDungeon.gridSelectScreen != null
                        && AbstractDungeon.gridSelectScreen.confirmScreenUp;
                begin();
            }
        }

        @SpirePostfixPatch
        public static void postfix(CancelButton button) {
            if (!candidate) { return; }
            if (button.isHidden || (clickedBefore && !button.hb.clicked)
                    || (escapeBefore && !InputHelper.pressedEscape)
                    || (releasedOnButton && !button.hb.clickStarted
                    && (!beforeScreen.equals(String.valueOf(AbstractDungeon.screen))
                    || (upgradeConfirmationBefore && AbstractDungeon.gridSelectScreen != null
                    && !AbstractDungeon.gridSelectScreen.confirmScreenUp)))) {
                boolean leave = "SHOP".equals(beforeScreen) || "COMBAT_REWARD".equals(beforeScreen)
                        || "CARD_REWARD".equals(beforeScreen) || "BOSS_REWARD".equals(beforeScreen);
                ActionRecorderRuntime.getInstance().recordAction(leave ? "LEAVE" : "RETURN",
                        leave ? "leave_button" : "return_button",
                        "\"screen_before_close\":" + quote(beforeScreen));
            } else {
                ActionRecorderRuntime.getInstance().discardDecision();
            }
            candidate = false;
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
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
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
