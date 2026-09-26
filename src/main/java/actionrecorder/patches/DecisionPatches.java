package actionrecorder.patches;

import actionrecorder.runtime.ActionRecorderRuntime;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.shop.ShopScreen;

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
            ActionRecorderRuntime.getInstance().recordAction(
                    "CHOOSE:card_id=" + card.cardID,
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
                    "SKIP:CARD_REWARD",
                    "card_reward_skipped",
                    "");
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

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
