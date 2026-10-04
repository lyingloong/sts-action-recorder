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
    private static long lastEventActionFrame;
    private static AbstractChest pendingChest;

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

    @SpirePatch(clz = com.megacrit.cardcrawl.screens.VictoryScreen.class,
            method = SpirePatch.CONSTRUCTOR, paramtypez = {MonsterGroup.class})
    public static class VictoryRunTerminal {
        @SpirePostfixPatch public static void postfix() {
            ActionRecorderRuntime.getInstance().markRunTerminal("victory");
        }
    }

    @SpirePatch(clz = MapRoomNode.class, method = "playNodeSelectedSound")
    public static class MapNodeSelection {
        @SpirePrefixPatch
        public static void prefix(MapRoomNode __instance) {
            MapRoomNode node = __instance;
            if (node == null || node.y < 0 || AbstractDungeon.screen != AbstractDungeon.CurrentScreen.MAP) {
                return;
            }
            ActionRecorderRuntime.getInstance().recordAction(
                    "MAP:x=" + node.x + ":y=" + node.y,
                    "map_node_selected",
                    "\"x\":" + node.x + ",\"y\":" + node.y);
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.map.DungeonMap.class, method = "update")
    public static class BossMapSelection {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(FieldAccess access) throws CannotCompileException {
                    if (access.isWriter() && "taken".equals(access.getFieldName())
                            && "com.megacrit.cardcrawl.map.MapRoomNode".equals(access.getClassName())) {
                        access.replace("{ if ($1) actionrecorder.runtime.ActionRecorderRuntime.getInstance().recordAction("
                                + "\"CHOOSE:index=0\", \"map_boss_selected\", \"\"); $proceed($$); }");
                    }
                }
            };
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
                ActionRecorderRuntime.getInstance().recordEndTurnQueued();
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

    @SpirePatch(clz = GameActionManager.class, method = "getNextAction")
    public static class QueuedExecutionBoundary {
        @SpirePrefixPatch public static void prefix(GameActionManager __instance) {
            ActionRecorderRuntime.getInstance().beforeQueueExecution(__instance);
        }
        @SpirePostfixPatch public static void postfix(GameActionManager __instance) {
            ActionRecorderRuntime.getInstance().afterQueueExecution(__instance);
        }
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if ("com.megacrit.cardcrawl.characters.AbstractPlayer".equals(call.getClassName())
                            && "useCard".equals(call.getMethodName())) {
                        call.replace("{ $proceed($$); actionrecorder.runtime.ActionRecorderRuntime.getInstance()"
                                + ".cardExecutionDispatched($1); }");
                    } else if ("callEndOfTurnActions".equals(call.getMethodName())
                            && GameActionManager.class.getName().equals(call.getClassName())) {
                        call.replace("{ $proceed($$); actionrecorder.runtime.ActionRecorderRuntime.getInstance()"
                                + ".endTurnExecutionDispatched(); }");
                    }
                }
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
        long now = com.badlogic.gdx.Gdx.graphics.getFrameId();
        if (event == lastEventAction && optionIndex == lastEventOption
                && now == lastEventActionFrame) {
            return;
        }
        lastEventAction = event;
        lastEventOption = optionIndex;
        lastEventActionFrame = now;
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
        private static String pendingId;
        private static String pendingKind;
        private static String pendingDetails;
        @SpirePrefixPatch
        public static void prefix(RewardItem __instance) {
            pendingId = null;
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
            begin();
            pendingId = id;
            pendingKind = "reward_" + type + "_claimed";
            pendingDetails = details;
        }

        @SpirePostfixPatch
        public static boolean postfix(boolean __result, RewardItem __instance) {
            // Legacy @SpirePatch binds a return-value parameter only when it
            // is first and matches the postfix return type. Preserve it.
            if (pendingId == null) return __result;
            boolean cardOpened = __instance.type == RewardItem.RewardType.CARD
                    && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CARD_REWARD;
            if (__result || cardOpened) {
                ActionRecorderRuntime.getInstance().recordAction(pendingId, pendingKind, pendingDetails);
            } else {
                ActionRecorderRuntime.getInstance().discardDecision();
            }
            pendingId = null;
            return __result;
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

    // keyRequirement sets isOpen BEFORE open() is called. Capture at its
    // accepted click boundary, otherwise the pre-state already says opened.
    @SpirePatch(clz = AbstractChest.class, method = "keyRequirement")
    public static class ChestOpeningBoundary {
        @SpirePrefixPatch public static void prefix(AbstractChest __instance) {
            beginChestOpening(__instance);
        }
        @SpirePostfixPatch public static boolean postfix(boolean __result, AbstractChest __instance) {
            if (!__result && pendingChest == __instance) {
                pendingChest = null;
                ActionRecorderRuntime.getInstance().discardDecision();
            }
            return __result;
        }
    }

    public static void beginChestOpening(AbstractChest chest) {
        if (pendingChest == chest) return;
        ActionRecorderRuntime.getInstance().beginDecision();
        pendingChest = chest;
    }

    @SpirePatch(cls = "communicationmod.ChoiceScreenUtils", method = "makeChestRoomChoice",
            paramtypez = {int.class}, requiredModId = "CommunicationMod", optional = true)
    public static class CommunicationChestOpeningBoundary {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(FieldAccess field) throws CannotCompileException {
                    if (field.isWriter() && "isOpen".equals(field.getFieldName())
                            && "com.megacrit.cardcrawl.rewards.chests.AbstractChest".equals(field.getClassName())) {
                        field.replace("{ if ($1 && !$0.isOpen) actionrecorder.patches.DecisionPatches"
                                + ".beginChestOpening($0); $proceed($$); }");
                    }
                }
            };
        }
    }

    private static void beginChestOpenMethod(AbstractChest chest) {
        if (pendingChest != chest) ActionRecorderRuntime.getInstance().beginDecision();
        pendingChest = null;
    }

    @SpirePatch(clz = AbstractChest.class, method = "open", paramtypez = {boolean.class})
    public static class ChestOpen {
        @SpirePrefixPatch
        public static void prefix(AbstractChest __instance) { beginChestOpenMethod(__instance); }
        @SpirePostfixPatch
        public static void postfix(AbstractChest __instance, boolean bossChest) {
            recordChestOpen(__instance, bossChest);
        }
    }

    /** BossChest overrides AbstractChest.open(boolean), so the base patch misses it. */
    @SpirePatch(clz = BossChest.class, method = "open", paramtypez = {boolean.class})
    public static class BossChestOpen {
        @SpirePrefixPatch
        public static void prefix(BossChest __instance) { beginChestOpenMethod(__instance); }
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
                "\"boss_chest\":" + (chest instanceof BossChest)
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
        private static int depth;
        @SpirePrefixPatch
        public static void prefix() {
            // Press only arms the hitbox; mouse release is the semantic commit.
            // Keep controller press because that path accepts immediately.
            attempted = InputHelper.justReleasedClickLeft
                    || com.megacrit.cardcrawl.helpers.controller.CInputActionSet.select.isJustPressed();
            depth = ActionRecorderRuntime.getInstance().decisionDepth();
            if (attempted) begin();
        }
        @SpirePostfixPatch
        public static void postfix(GridCardSelectScreen __instance) {
            GridCardSelectScreen screen = __instance;
            if (attempted) ActionRecorderRuntime.getInstance().recordGridSelection(screen);
            ActionRecorderRuntime.getInstance().discardDecisionsAfter(depth);
            attempted = false;
        }
    }

    /** A one-card grid often closes within update(), before its postfix runs. */
    @SpirePatch(clz = GridCardSelectScreen.class, method = "update")
    public static class GridSelectionAccepted {
        @SpireInstrumentPatch
        public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(FieldAccess access) throws CannotCompileException {
                    if (access.isWriter() && "confirmScreenUp".equals(access.getFieldName())) {
                        access.replace("{ if ($1 && !$0.confirmScreenUp)"
                                + " actionrecorder.runtime.ActionRecorderRuntime.getInstance().recordGridPreview($0);"
                                + " $proceed($$); }");
                    }
                }
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

    @SpirePatch(clz = HandCardSelectScreen.class, method = "selectHoveredCard")
    public static class HandSelection {
        private static int depth;
        private static String cardUuid;
        @SpirePrefixPatch
        public static void prefix(HandCardSelectScreen __instance) {
            depth = ActionRecorderRuntime.getInstance().decisionDepth();
            cardUuid = __instance.hoveredCard == null ? null : String.valueOf(__instance.hoveredCard.uuid);
            begin();
        }
        @SpirePostfixPatch
        public static void postfix(HandCardSelectScreen __instance) {
            HandCardSelectScreen screen = __instance;
            ActionRecorderRuntime.getInstance().recordHandSelection(screen, cardUuid);
            ActionRecorderRuntime.getInstance().discardDecisionsAfter(depth);
        }
    }

    @SpirePatch(clz = HandCardSelectScreen.class, method = "updateSelectedCards")
    public static class HandDeselection {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(MethodCall call) throws CannotCompileException {
                    if ("addToTop".equals(call.getMethodName())
                            && "com.megacrit.cardcrawl.cards.CardGroup".equals(call.getClassName())) {
                        call.replace("{ actionrecorder.runtime.ActionRecorderRuntime.getInstance()"
                                + ".recordHandDeselection($0, $1); $proceed($$); }");
                    }
                }
            };
        }
    }

    /** Preserve the original shuffled layout, not later remaining-list indices. */
    @SpirePatch(clz = com.megacrit.cardcrawl.events.shrines.GremlinMatchGame.class, method = "placeCards")
    public static class MatchGameBoardPlaced {
        @SpirePostfixPatch public static void postfix(com.megacrit.cardcrawl.events.shrines.GremlinMatchGame __instance) {
            actionrecorder.runtime.MatchGameState.initialize(__instance);
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.events.shrines.GremlinMatchGame.class,
            method = "updateMatchGameLogic")
    public static class MatchGameCardFlip {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(FieldAccess access) throws CannotCompileException {
                    if (access.isWriter() && "justClickedLeft".equals(access.getFieldName())
                            && "com.megacrit.cardcrawl.helpers.input.InputHelper".equals(access.getClassName())) {
                        // This accepted input branch precedes CM's CardIdentificationPatch,
                        // which reveals the ID before the isFlipped assignment itself.
                        access.replace("{ if (!$1) actionrecorder.patches.DecisionPatches.recordMatchGameInput(this);"
                                + " $proceed($$); }");
                    } else if (access.isWriter() && "isFlipped".equals(access.getFieldName())) {
                        access.replace("{ boolean revealing = !$1 && $0.isFlipped;"
                                + " $proceed($$); if (revealing)"
                                + " actionrecorder.runtime.MatchGameState.revealed(this, $0); }");
                    }
                }
            };
        }
    }

    public static void recordMatchGameInput(Object event) {
        Object hovered = field(event, "hoveredCard");
        if (hovered instanceof AbstractCard && ((AbstractCard) hovered).isFlipped) {
            recordMatchCard(event, (AbstractCard) hovered);
        }
    }

    public static void recordMatchCard(Object event, AbstractCard card) {
        Object group = field(event, "cards");
        if (!(group instanceof com.megacrit.cardcrawl.cards.CardGroup)) return;
        int index = ((com.megacrit.cardcrawl.cards.CardGroup) group).group.indexOf(card);
        if (index < 0) return;
        String actionId = "EVENT:FLIP:card=" + card.uuid;
        try {
            // CommunicationMod sorts these cards by board position, not by
            // the shuffled CardGroup order. Use precisely its public ordering.
            Object cards = Class.forName("communicationmod.patches.GremlinMatchGamePatch")
                    .getMethod("getOrderedCards").invoke(null);
            if (cards instanceof java.util.List) {
                index = ((java.util.List<?>) cards).indexOf(card);
                if (index >= 0) actionId = "CHOOSE:index=" + index;
            }
        } catch (ReflectiveOperationException ignored) { }
        ActionRecorderRuntime.getInstance().recordAction(actionId, "event_card_flipped",
                "\"option_index\":" + index + ",\"card_uuid\":" + quote(String.valueOf(card.uuid))
                        + ",\"board_position\":" + actionrecorder.runtime.MatchGameState.position(event, card)
                        + ",\"event_class\":" + quote(event.getClass().getName()));
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.events.shrines.GremlinWheelGame.class, method = "update")
    public static class WheelSpin {
        @SpireInstrumentPatch public static ExprEditor instrument() {
            return new ExprEditor() {
                @Override public void edit(FieldAccess access) throws CannotCompileException {
                    if (access.isWriter() && "buttonPressed".equals(access.getFieldName())) {
                        access.replace("{ if ($1 && !$0.buttonPressed)"
                                + " actionrecorder.runtime.ActionRecorderRuntime.getInstance().recordAction("
                                + "\"CHOOSE:index=0\", \"event_wheel_spun\", \"\"); $proceed($$); }");
                    }
                }
            };
        }
    }

    @SpirePatch(clz = com.megacrit.cardcrawl.ui.buttons.SingingBowlButton.class, method = "onClick")
    public static class SingingBowlChoice {
        @SpirePrefixPatch public static void prefix() {
            ActionRecorderRuntime.getInstance().recordAction("REWARD:BOWL", "singing_bowl_chosen", "\"max_hp_gain\":2");
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
        @SpireInstrumentPatch public static ExprEditor instrument() { return potionUseEditor(); }
        @SpirePrefixPatch
        public static void prefix(PotionPopUp __instance) {
            PotionPopUp popup = __instance;
            Object top = field(popup, "hbTop");
            Object bottom = field(popup, "hbBot");
            com.megacrit.cardcrawl.potions.AbstractPotion potion =
                    (com.megacrit.cardcrawl.potions.AbstractPotion) field(popup, "potion");
            if (clicked(bottom) && !clicked(top) && potion != null) {
                ActionRecorderRuntime.getInstance().recordPotionAction(
                        "POTION_DISCARD", "potion_discarded", intField(popup, "slot"),
                        potion);
            }
        }
    }

    @SpirePatch(clz = PotionPopUp.class, method = "updateTargetMode")
    public static class PotionTargetInput {
        @SpireInstrumentPatch public static ExprEditor instrument() { return potionUseEditor(); }
    }

    private static ExprEditor potionUseEditor() {
        return new ExprEditor() {
            @Override public void edit(MethodCall call) throws CannotCompileException {
                if ("use".equals(call.getMethodName())
                        && "com.megacrit.cardcrawl.potions.AbstractPotion".equals(call.getClassName())) {
                    call.replace("{ actionrecorder.patches.DecisionPatches.recordPotionUse(this, $0, $1); $proceed($$); }");
                }
            }
        };
    }

    public static void recordPotionUse(PotionPopUp popup, AbstractPotion potion,
                                      com.megacrit.cardcrawl.core.AbstractCreature target) {
        if (target instanceof AbstractMonster) {
            ActionRecorderRuntime.getInstance().recordPotionUseTarget(intField(popup, "slot"), potion, (AbstractMonster) target);
        } else {
            ActionRecorderRuntime.getInstance().recordPotionAction("POTION_USE", "potion_use_requested", intField(popup, "slot"), potion);
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
