package actionrecorder;

import actionrecorder.runtime.MatchGameState;
import actionrecorder.runtime.RuntimeStateFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Synthetic runtime objects: field semantics and privacy without game startup. */
public final class RuntimeStateFieldsSmoke {
    private static class CardFlags {
        private boolean retain = true, selfRetain = true, freeToPlayOnce = true;
        private boolean isCostModified = false, isCostModifiedForTurn = true, isInnate = true;
        private boolean purgeOnUse = false, exhaustOnUseOnce = true;
        private boolean inBottleFlame = true, inBottleLightning = false, inBottleTornado = false;
    }
    private static final class Card extends CardFlags {
        private final UUID uuid = UUID.randomUUID();
        private final String cardID;
        private boolean isFlipped = true;
        Card(String id) { cardID = id; }
    }
    private static final class Energy { private int energyMaster = 4, energy = 3; }
    private static final class Stance { private String ID = "Wrath", name = "愤怒", description = "游戏原文"; }
    private static final class Player {
        private Energy energy = new Energy();
        private int masterHandSize = 5, gameHandSize = 7, cardsPlayedThisTurn = 2;
        private Stance stance = new Stance();
    }
    private static final class Group { private List<Object> group = new ArrayList<Object>(); }
    private static final class Grid {
        private List<Card> selectedCards = new ArrayList<Card>();
        private boolean confirmScreenUp;
        private Card hoveredCard;
    }
    private static final class Event {
        private Group cards = new Group();
        private String screen = "INTRO";
        private int attemptCount = 5, cardsMatched = 0;
        private boolean gameDone = false;
        private float waitTimer = 0;
        private Card chosenCard, hoveredCard;
    }
    private static int converted;
    private static Object convert(Object value) {
        converted++;
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("id", RuntimeStateFields.read(value, "cardID"));
        RuntimeStateFields.addCardFlags(result, value);
        return result;
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> board(Map<String, Object> state) {
        return (List<Map<String, Object>>) state.get("board");
    }
    private static Map<String, Object> snapshot(Event event) {
        return MatchGameState.snapshot(event, RuntimeStateFieldsSmoke::convert);
    }
    public static void main(String[] args) {
        Map<String, Object> fields = new HashMap<String, Object>();
        RuntimeStateFields.addCardFlags(fields, new Card("test"));
        check(fields.size() == 11 && Boolean.TRUE.equals(fields.get("free_to_play_once")), "all inherited card flags");
        check(Boolean.FALSE.equals(fields.get("is_cost_modified")), "false is not unavailable");
        RuntimeStateFields.addCardFlags(fields, new Object());
        check(fields.get("retain") == null, "missing is null, not false");

        Player player = new Player();
        fields.clear();
        fields.put("energy", 9);
        RuntimeStateFields.addPlayerFields(fields, player);
        check(fields.get("energy").equals(9), "current energy is not overwritten");
        check(fields.get("base_energy_per_turn").equals(4) && fields.get("energy_per_turn").equals(3), "master vs combat recharge");
        check(fields.get("master_hand_size").equals(5) && fields.get("game_hand_size").equals(7), "base vs combat draw");
        check(fields.get("cards_played_this_turn").equals(2), "turn count");
        check(((Map<?, ?>) fields.get("stance")).get("id").equals("Wrath"), "stance identity");
        player.stance = null; player.energy = null;
        RuntimeStateFields.addPlayerFields(fields, player);
        check(fields.get("stance") == null && fields.get("energy_per_turn") == null, "unavailable player objects");

        Grid grid = new Grid();
        Card upgrade = new Card("upgrade"), purge = new Card("purge");
        grid.confirmScreenUp = true; grid.hoveredCard = upgrade;
        check(RuntimeStateFields.<Card>gridConfirmationCards(grid).get(0) == upgrade, "upgrade preview target is not the empty selectedCards");
        grid.hoveredCard = purge;
        check(RuntimeStateFields.<Card>gridConfirmationCards(grid).get(0) == purge, "delete/transform preview uses the actual current target");
        grid.hoveredCard = null;
        check(RuntimeStateFields.gridConfirmationCards(grid) == null, "missing preview target is unknown, not a stale choice");
        grid.confirmScreenUp = false;
        check(RuntimeStateFields.gridConfirmationCards(grid).isEmpty(), "valid zero-card confirmation");
        grid.selectedCards.add(upgrade); grid.selectedCards.add(purge);
        List<Card> targets = RuntimeStateFields.gridConfirmationCards(grid);
        grid.selectedCards.clear();
        check(targets.size() == 2 && targets.get(1) == purge, "multi-selection snapshot survives UI cleanup");

        Event event = new Event();
        for (int i = 0; i < 12; i++) event.cards.group.add(new Card("secret-" + i));
        // Constructor's unflipped cards are not yet public in INTRO.
        for (Object value : event.cards.group) ((Card) value).isFlipped = false;
        Map<String, Object> intro = snapshot(event);
        check(converted == 0 && board(intro).size() == 12, "intro does not leak hidden faces");
        for (int i = 0; i < 12; i++) check(board(intro).get(i).get("position").equals(i), "stable CM board order");
        check(MatchGameState.position(event, event.cards.group.get(1)).equals(5), "original shuffle index != board index");
        for (Object value : event.cards.group) ((Card) value).isFlipped = true;
        event.screen = "PLAY";
        MatchGameState.initialize(event);
        Card first = (Card) event.cards.group.get(0), second = (Card) event.cards.group.get(1);
        Map<String, Object> before = snapshot(event);
        check(converted == 0 && board(before).get(0).get("card") == null, "before first flip remains hidden");
        first.isFlipped = false;
        MatchGameState.revealed(event, first);
        event.chosenCard = first;
        Map<String, Object> afterFirst = snapshot(event);
        check(Boolean.TRUE.equals(board(afterFirst).get(0).get("face_up")), "first exposed face");
        check(((List<?>) afterFirst.get("selected_positions")).contains(0), "first choice public");
        check(board(before).get(0).get("card") == null, "snapshots do not mutate retrospectively");
        second.isFlipped = false;
        MatchGameState.revealed(event, second);
        event.hoveredCard = second; event.waitTimer = 1.25f;
        check(Boolean.TRUE.equals(snapshot(event).get("awaiting_resolution")), "pending pair resolution");
        first.isFlipped = second.isFlipped = true;
        event.chosenCard = event.hoveredCard = null;
        event.attemptCount--; event.waitTimer = 0;
        Map<String, Object> mismatch = snapshot(event);
        check(board(mismatch).get(0).get("card") != null && board(mismatch).get(5).get("card") != null, "revealed memory survives re-hide");
        check(Boolean.FALSE.equals(board(mismatch).get(0).get("face_up")), "re-hidden state");
        check(mismatch.get("remaining_attempts").equals(4), "attempt countdown");
        check(board(mismatch).get(1).get("card") == null, "unseen card still hidden");
        event.cards.group.remove(first); event.cards.group.remove(second); event.cardsMatched++;
        Map<String, Object> matched = snapshot(event);
        check(board(matched).size() == 12 && Boolean.TRUE.equals(board(matched).get(5).get("matched")), "removed pair retains original positions");
        check(matched.get("matched_pairs").equals(1), "matched count");
        Event other = new Event();
        for (int i = 0; i < 12; i++) other.cards.group.add(new Card("different"));
        check(board(snapshot(other)).get(0).get("card") == null, "new event does not inherit knowledge");
        Map<String, Object> unavailable = MatchGameState.snapshot(new Object(), RuntimeStateFieldsSmoke::convert);
        check(unavailable.get("board") == null && unavailable.get("remaining_attempts") == null, "unavailable board is null");
        System.out.println("PASS card flags / player counters / stance / grid confirmation targets / public board / reveal privacy");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
