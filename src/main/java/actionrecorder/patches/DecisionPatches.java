package actionrecorder.patches;

import actionrecorder.runtime.ActionRecorderRuntime;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireInstrumentPatch;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.MethodCall;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.neow.NeowEvent;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rewards.chests.AbstractChest;
import com.megacrit.cardcrawl.rewards.chests.BossChest;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.screens.DeathScreen;
import com.megacrit.cardcrawl.screens.select.BossRelicSelectScreen;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.events.GenericEventDialog;
import com.megacrit.cardcrawl.events.RoomEventDialog;
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
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.SkipCardButton;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;

import java.lang.reflect.Field;

/**
 * Small, explicit patches for decisions that have a stable semantic boundary.
 * More screens will be added only after their method signatures are verified
 * against the installed game jar.
 */
public final class DecisionPatches {
    private static AbstractEvent lastEventAction;
    private static int lastEventOption = -1;
    private static long lastEventActionAt;

    private DecisionPatches() {
    }

    /** Death can clear CardCrawlGame.dungeon before the runtime reads HP. */
    @SpirePatch(clz = DeathScreen.class, method = SpirePatch.CONSTRUCTOR,
            paramtypez = {MonsterGroup.class})
    public static class DeathRunTerminal {
        @SpirePostfixPatch
        public static void postfix(DeathScreen __instance) {
            ActionRecorderRuntime.getInstance().markRunTerminal("death");
        }
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
        public static void postfix(CardRewardScreen __instance, AbstractCard card) {
            if (card == null) {
                ActionRecorderRuntime.getInstance().discardDecision();
                return;
            }
            int index = __instance == null || __instance.rewardGroup == null
                    ? -1 : __instance.rewardGroup.indexOf(card);
            ActionRecorderRuntime.getInstance().recordAction(
                    index < 0 ? "CHOOSE:card_id=" + card.cardID : "CHOOSE:index=" + index,
                    "card_reward_selected",
                    "\"card_id\":" + quote(card.cardID)
                            + ",\"card_name\":" + quote(card.name)
                            + ",\"card_uuid\":" + quote(String.valueOf(card.uuid)));
        }
    }

    // Permanent rewards call acquireCard(), while Discovery choices assign
    // discoveryCard and Choose One effects invoke their option card directly.
    @SpirePatch(clz = CardRewardScreen.class, method = "update")
    public static class CardRewardOptionTouch {
        @SpireInstrumentPatch
        public static ExprEditor instrument() { return cardRewardOptionEditor(); }
    }

    @SpirePatch(clz = CardRewardScreen.class, method = "cardSelectUpdate")
    public static class CardRewardOptionMouse {
        @SpireInstrumentPatch
        public static ExprEditor instrument() { return cardRewardOptionEditor(); }
    }

    private static ExprEditor cardRewardOptionEditor() {
        return new ExprEditor() {
            @Override
            public void edit(FieldAccess field) throws CannotCompileException {
                if (field.isWriter()
                        && "com.megacrit.cardcrawl.screens.CardRewardScreen".equals(field.getClassName())
                        && "discoveryCard".equals(field.getFieldName())) {
                    field.replace("{ actionrecorder.runtime.ActionRecorderRuntime.getInstance()"
                            + ".recordCardRewardOption(com.megacrit.cardcrawl.dungeons.AbstractDungeon.cardRewardScreen,"
                            + " $1, \"discovery\"); $proceed($$); }");
                }
            }

            @Override
            public void edit(MethodCall call) throws CannotCompileException {
                if ("com.megacrit.cardcrawl.cards.AbstractCard".equals(call.getClassName())
                        && "onChoseThisOption".equals(call.getMethodName())
                        && "()V".equals(call.getSignature())) {
                    call.replace("{ actionrecorder.runtime.ActionRecorderRuntime.getInstance()"
                            + ".recordCardRewardOption(com.megacrit.cardcrawl.dungeons.AbstractDungeon.cardRewardScreen,"
                            + " $0, \"choose_one\"); $proceed($$); }");
                }
            }
        };
    }

    @SpirePatch(clz = SkipCardButton.class, method = "update")
    public static class CardRewardSkip {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if ("com.megacrit.cardcrawl.dungeons.AbstractDungeon".equals(call.getClassName())
                            && "closeCurrentScreen".equals(call.getMethodName())
                            && "()V".equals(call.getSignature())) {
                        call.replace("{ if (com.megacrit.cardcrawl.dungeons.AbstractDungeon.screen "
                                + "== com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.CARD_REWARD) "
                                + "actionrecorder.runtime.ActionRecorderRuntime.getInstance().recordCardRewardSkip(); "
                                + "$proceed($$); }");
                    }
                }
            };
        }
    }

    @SpirePatch(clz = ShopScreen.class, method = "purchaseCard")
    public static class ShopCardPurchase {
        @SpirePrefixPatch
        public static void prefix(ShopScreen __instance, AbstractCard card) {
            // purchaseCard is private and mutates the shop immediately. Record
            // the accepted purchase before gold/card state changes.
            if (card == null || AbstractDungeon.player == null
                    || AbstractDungeon.player.gold < card.price) {
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
        public static void prefix(StoreRelic __instance) {
            StoreRelic store = __instance;
            if (store == null || store.relic == null || AbstractDungeon.player == null
                    || AbstractDungeon.player.gold < store.price) {
                return;
            }
            AbstractRelic purchased = store.relic;
            ActionRecorderRuntime.getInstance().recordAction(
                    "SHOP:BUY_RELIC:" + purchased.relicId,
                    "shop_relic_purchased",
                    "\"relic_id\":" + quote(purchased.relicId)
                            + ",\"relic_name\":" + quote(purchased.name)
                            + ",\"price\":" + store.price);
        }
    }

    @SpirePatch(clz = StorePotion.class, method = "purchasePotion")
    public static class ShopPotionPurchase {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if (call.getMethodName().equals("obtainPotion")
                            && call.getSignature().equals("(Lcom/megacrit/cardcrawl/potions/AbstractPotion;)Z")) {
                        call.replace("{ actionrecorder.runtime.ActionRecorderRuntime.getInstance().beginDecision();"
                                + " $_ = $proceed($$);"
                                + " if ($_) actionrecorder.runtime.ActionRecorderRuntime.getInstance().recordPurchasedPotion($1, this.price);"
                                + " else actionrecorder.runtime.ActionRecorderRuntime.getInstance().discardDecision(); }");
                    }
                }
            };
        }
    }

    @SpirePatch(clz = ShopScreen.class, method = "purchasePurge")
    public static class ShopPurgeOpened {
        @SpirePrefixPatch
        public static void prefix(ShopScreen __instance) {
            if (__instance == null || !__instance.purgeAvailable || AbstractDungeon.player == null
                    || AbstractDungeon.player.gold < ShopScreen.actualPurgeCost) {
                return;
            }
            ActionRecorderRuntime.getInstance().beginDecision();
            ActionRecorderRuntime.getInstance().recordAction(
                    "SHOP:PURGE", "shop_purge_opened", "\"price\":" + ShopScreen.actualPurgeCost);
        }
    }

    @SpirePatch(clz = ShopScreen.class, method = "open")
    public static class ShopOpened {
        private static boolean capture;

        @SpirePrefixPatch
        public static void prefix() {
            // Merchant.update accepts a click only while no screen is up;
            // closeCurrentScreen also calls open() when returning from a grid.
            capture = !AbstractDungeon.isScreenUp;
            if (capture) ActionRecorderRuntime.getInstance().beginDecision();
        }
        @SpirePostfixPatch
        public static void postfix(ShopScreen __instance) {
            if (capture) {
                ActionRecorderRuntime.getInstance().recordAction(
                        "OPEN:SHOP", "shop_opened", "");
            }
            capture = false;
        }
    }

    @SpirePatch(clz = EndTurnButton.class, method = "disable", paramtypez = {boolean.class})
    public static class EndTurn {
        private static boolean wasEnabled;
        @SpirePrefixPatch
        public static void prefix(EndTurnButton __instance, boolean endTurn) {
            EndTurnButton button = __instance;
            wasEnabled = endTurn && button.enabled;
            if (wasEnabled) { ActionRecorderRuntime.getInstance().beginDecision(); }
        }
        @SpirePostfixPatch
        public static void postfix(EndTurnButton __instance, boolean endTurn) {
            EndTurnButton button = __instance;
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
        public static void prefix(AbstractPlayer __instance) {
            queueSize = AbstractDungeon.actionManager.cardQueue.size();
            ActionRecorderRuntime.getInstance().beginDecision();
        }
        @SpirePostfixPatch
        public static void postfix(AbstractPlayer __instance) {
            if (AbstractDungeon.actionManager.cardQueue.size() > queueSize) {
                CardQueueItem item = AbstractDungeon.actionManager.cardQueue.get(queueSize);
                ActionRecorderRuntime.getInstance().recordCardQueued(item);
            } else {
                ActionRecorderRuntime.getInstance().discardDecision();
            };
        }
    }

    // These getters also restore waitForInput=true. Observe their result in a
    // postfix; calling either getter from an event update prefix consumes the
    // input before the game can dispatch buttonEffect().
    @SpirePatch(clz = RoomEventDialog.class, method = "getSelectedOption")
    public static class RoomEventOption {
        @SpirePostfixPatch
        public static int postfix(int __result) {
            recordCurrentEventOption(__result);
            return __result;
        }
    }

    @SpirePatch(clz = GenericEventDialog.class, method = "getSelectedOption")
    public static class ImageEventOption {
        @SpirePostfixPatch
        public static int postfix(int __result) {
            recordCurrentEventOption(__result);
            return __result;
        }
    }

    private static void recordCurrentEventOption(int optionIndex) {
        AbstractEvent event = null;
        try {
            if (AbstractDungeon.getCurrRoom() != null) {
                event = AbstractDungeon.getCurrRoom().event;
            }
        } catch (Throwable ignored) {
            // Event transitions can temporarily clear the current room.
        }
        recordEventOption(event, optionIndex);
    }

    /**
     * Fallback for custom events that override update() and explicitly call
     * AbstractEvent.logInput() instead of using the base dialog update loop.
     * Vanilla events reach this helper only after the update patches have
     * already marked the same event/option, so the duplicate is suppressed.
     */
    @SpirePatch(clz = AbstractEvent.class, method = "logInput")
    public static class EventLogInputFallback {
        @SpirePrefixPatch
        public static void prefix(AbstractEvent __instance, int optionIndex) {
            AbstractEvent event = __instance;
            recordEventOption(event, optionIndex);
        }
    }

    private static void recordEventOption(AbstractEvent event, int optionIndex) {
        if (event == null || optionIndex < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (event == lastEventAction && optionIndex == lastEventOption
                && now - lastEventActionAt < 250L) {
            return;
        }
        lastEventAction = event;
        lastEventOption = optionIndex;
        lastEventActionAt = now;
        ActionRecorderRuntime.getInstance().recordAction(
                "CHOOSE:index=" + optionIndex,
                event instanceof NeowEvent ? "neow_option_selected" : "event_option_selected",
                "\"option_index\":" + optionIndex
                        + ",\"event_class\":" + quote(event.getClass().getName()));
    }

    /** Neow uses a custom opening flow and may bypass the shared event dialogs. */
    @SpirePatch(clz = NeowEvent.class, method = "buttonEffect", paramtypez = {int.class})
    public static class NeowOption {
        @SpirePrefixPatch
        public static void prefix(NeowEvent __instance, int optionIndex) {
            recordEventOption(__instance, optionIndex);
        }
    }

    @SpirePatch(clz = RewardItem.class, method = "claimReward")
    public static class RewardClaim {
        @SpirePrefixPatch
        public static void prefix(RewardItem __instance) {
            if (__instance == null || __instance.type == null || __instance.ignoreReward) {
                return;
            }
            if (__instance.type == RewardItem.RewardType.POTION) {
                if (AbstractDungeon.player == null
                        || AbstractDungeon.player.hasRelic("Sozu")
                        || !hasPotionCapacity()) {
                    return;
                }
            }
            if (__instance.type == RewardItem.RewardType.RELIC
                    && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID) {
                return;
            }
            String type = __instance.type.name().toLowerCase();
            String id = "REWARD:TAKE:" + __instance.type.name();
            String details = "\"reward_type\":" + quote(__instance.type.name())
                    + ",\"gold\":" + __instance.goldAmt;
            if (__instance.relic != null) {
                details += ",\"relic_id\":" + quote(__instance.relic.relicId)
                        + ",\"relic_name\":" + quote(__instance.relic.name);
            }
            if (__instance.potion != null) {
                details += ",\"potion_id\":" + quote(__instance.potion.ID)
                        + ",\"potion_name\":" + quote(__instance.potion.name);
            }
            ActionRecorderRuntime.getInstance().recordAction(id, "reward_" + type + "_claimed", details);
        }

        private static boolean hasPotionCapacity() {
            if (AbstractDungeon.player == null || AbstractDungeon.player.potions == null) {
                return false;
            }
            int occupied = 0;
            for (AbstractPotion potion : AbstractDungeon.player.potions) {
                if (!(potion instanceof PotionSlot)) {
                    occupied++;
                }
            }
            return occupied < AbstractDungeon.player.potionSlots;
        }
    }

    @SpirePatch(clz = AbstractChest.class, method = "open", paramtypez = {boolean.class})
    public static class ChestOpen {
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(AbstractChest __instance, boolean bossChest) {
            recordChestOpen(__instance, bossChest);
        }
    }

    /** BossChest overrides AbstractChest.open(boolean), so the base patch misses it. */
    @SpirePatch(clz = BossChest.class, method = "open", paramtypez = {boolean.class})
    public static class BossChestOpen {
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(BossChest __instance, boolean bossChest) {
            recordChestOpen(__instance, bossChest);
        }
    }

    private static void recordChestOpen(AbstractChest chest, boolean bossChest) {
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

    @SpirePatch(clz = BossRelicSelectScreen.class, method = "relicObtainLogic",
            paramtypez = {AbstractRelic.class})
    public static class BossRelicPick {
        @SpirePrefixPatch
        public static void prefix() { ActionRecorderRuntime.getInstance().beginDecision(); }
        @SpirePostfixPatch
        public static void postfix(BossRelicSelectScreen __instance, AbstractRelic relic) {
            BossRelicSelectScreen screen = __instance;
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
            attempted = true;
        }
        @SpirePostfixPatch
        public static void postfix(GridCardSelectScreen __instance) {
            GridCardSelectScreen screen = __instance;
            if (attempted) ActionRecorderRuntime.getInstance().recordGridSelection(screen);
            attempted = false;
        }
    }

    /** A one-card grid often closes within update(), before its postfix runs. */
    @SpirePatch(clz = GridCardSelectScreen.class, method = "update")
    public static class GridSelectionAccepted {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if ("java.util.ArrayList".equals(call.getClassName())
                            && "add".equals(call.getMethodName())
                            && "(Ljava/lang/Object;)Z".equals(call.getSignature())) {
                        call.replace("{ actionrecorder.runtime.ActionRecorderRuntime.getInstance()"
                                + ".recordGridCardSelection($0, $1); $_ = $proceed($$); }");
                    }
                }
            };
        }
    }

    @SpirePatch(clz = HandCardSelectScreen.class, method = "update")
    public static class HandSelection {
        private static boolean attempted;
        @SpirePrefixPatch
        public static void prefix() {
            attempted = true;
        }
        @SpirePostfixPatch
        public static void postfix(HandCardSelectScreen __instance) {
            HandCardSelectScreen screen = __instance;
            if (attempted) ActionRecorderRuntime.getInstance().recordHandSelection(screen);
            attempted = false;
        }
    }

    /** Hand-card prompts (for example Armaments) use a different confirm button. */
    @SpirePatch(clz = HandCardSelectScreen.class, method = "update")
    public static class HandSelectionConfirmClick {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if ("com.megacrit.cardcrawl.ui.buttons.CardSelectConfirmButton".equals(call.getClassName())
                            && "update".equals(call.getMethodName())
                            && "()V".equals(call.getSignature())) {
                        call.replace("{ $proceed($$);"
                                + " actionrecorder.runtime.ActionRecorderRuntime.getInstance()"
                                + ".recordHandConfirmation((com.megacrit.cardcrawl.ui.buttons.CardSelectConfirmButton)$0); }");
                    }
                }
            };
        }
    }

    /** Observe the click after button.update(), before GridCardSelectScreen consumes and clears it. */
    @SpirePatch(clz = GridCardSelectScreen.class, method = "update")
    public static class GridSelectionConfirmClick {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if ("com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton".equals(call.getClassName())
                            && "update".equals(call.getMethodName())
                            && "()V".equals(call.getSignature())) {
                        call.replace("{ $proceed($$);"
                                + " actionrecorder.runtime.ActionRecorderRuntime.getInstance()"
                                + ".recordGridConfirmation((com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton)$0); }");
                    }
                }
            };
        }
    }

    @SpirePatch(clz = RestOption.class, method = "useOption")
    public static class CampfireRest { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(RestOption __instance) { campfire(__instance, "REST"); } }
    @SpirePatch(clz = SmithOption.class, method = "useOption")
    public static class CampfireSmith {
        @SpirePrefixPatch public static void prefix() {
            begin();
            ActionRecorderRuntime.getInstance().recordAction(
                    "CAMPFIRE:SMITH", "campfire_action", "\"option\":\"SMITH\"");
        }
    }
    @SpirePatch(clz = LiftOption.class, method = "useOption")
    public static class CampfireLift { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(LiftOption __instance) { campfire(__instance, "LIFT"); } }
    @SpirePatch(clz = TokeOption.class, method = "useOption")
    public static class CampfireToke { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(TokeOption __instance) { campfire(__instance, "TOKE"); } }
    @SpirePatch(clz = DigOption.class, method = "useOption")
    public static class CampfireDig { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(DigOption __instance) { campfire(__instance, "DIG"); } }
    @SpirePatch(clz = RecallOption.class, method = "useOption")
    public static class CampfireRecall { @SpirePrefixPatch public static void prefix() { begin(); } @SpirePostfixPatch public static void postfix(RecallOption __instance) { campfire(__instance, "RECALL"); } }

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
        public static void prefix(ProceedButton __instance) {
            ProceedButton button = __instance;
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
        public static void postfix(ProceedButton __instance) {
            ProceedButton button = __instance;
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
        public static void prefix(CancelButton __instance) {
            CancelButton button = __instance;
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
        public static void postfix(CancelButton __instance) {
            CancelButton button = __instance;
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
        public static void prefix(PotionPopUp __instance) {
            PotionPopUp popup = __instance;
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
        public static void prefix(PotionPopUp __instance) {
            PotionPopUp popup = __instance;
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
        public static void postfix(BossRelicSelectScreen __instance) {
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
