package actionrecorder.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/** Public Match and Keep board. Unseen faces never enter the snapshot. */
public final class MatchGameState {
    private static final Map<Object, Memory> MEMORIES = new WeakHashMap<Object, Memory>();

    private static final class Entry {
        final Object card;
        final Integer position;
        boolean revealed;
        Entry(Object card, Integer position) { this.card = card; this.position = position; }
    }

    private static final class Memory {
        final List<Entry> entries = new ArrayList<Entry>();
    }

    private MatchGameState() { }

    @SuppressWarnings("unchecked")
    private static List<Object> cards(Object event) {
        Object value = RuntimeStateFields.read(RuntimeStateFields.read(event, "cards"), "group");
        return value instanceof List ? (List<Object>) value : null;
    }

    /** Called after placeCards, when the original shuffled board order is known. */
    public static void initialize(Object event) {
        List<Object> cards = cards(event);
        if (cards == null) return;
        Memory memory = new Memory();
        for (int i = 0; i < cards.size(); i++) {
            // Exactly the base game's placeCards layout and CM cardPositions order.
            Integer position = cards.size() == 12 ? i % 4 + 4 * (i % 3) : null;
            memory.entries.add(new Entry(cards.get(i), position));
        }
        Collections.sort(memory.entries, Comparator.comparingInt(e -> e.position == null ? 99 : e.position));
        MEMORIES.put(event, memory);
    }

    /** Call AFTER the accepted face-down -> face-up assignment, never before it. */
    public static void revealed(Object event, Object card) {
        Memory memory = memory(event);
        if (memory == null) return;
        for (Entry entry : memory.entries) if (entry.card == card) entry.revealed = true;
    }

    private static Memory memory(Object event) {
        if (!MEMORIES.containsKey(event)) initialize(event);
        return MEMORIES.get(event);
    }

    public static Integer position(Object event, Object card) {
        Memory memory = memory(event);
        if (memory != null) for (Entry entry : memory.entries) if (entry.card == card) return entry.position;
        return null;
    }

    public static Map<String, Object> snapshot(Object event, Function<Object, Object> convertCard) {
        Map<String, Object> result = new HashMap<String, Object>();
        Object phase = RuntimeStateFields.read(event, "screen");
        String phaseName = phase == null ? null : phase.toString();
        result.put("phase", phaseName);
        result.put("remaining_attempts", RuntimeStateFields.read(event, "attemptCount"));
        result.put("matched_pairs", RuntimeStateFields.read(event, "cardsMatched"));
        result.put("game_done", RuntimeStateFields.read(event, "gameDone"));
        Object timer = RuntimeStateFields.read(event, "waitTimer");
        result.put("awaiting_resolution", timer instanceof Number ? ((Number) timer).floatValue() > 0 : null);
        Memory memory = memory(event);
        List<Object> current = cards(event);
        if (memory == null || current == null) {
            result.put("board", null);
            result.put("selected_positions", null);
            return result;
        }
        Map<Object, Boolean> present = new IdentityHashMap<Object, Boolean>();
        for (Object card : current) present.put(card, true);
        boolean boardVisible = "PLAY".equals(phaseName) || "CLEAN_UP".equals(phaseName);
        List<Map<String, Object>> board = new ArrayList<Map<String, Object>>();
        List<Integer> selected = new ArrayList<Integer>();
        Object chosen = RuntimeStateFields.read(event, "chosenCard");
        Object hovered = RuntimeStateFields.read(event, "hoveredCard");
        for (Entry entry : memory.entries) {
            boolean matched = !present.containsKey(entry.card);
            Object flipped = RuntimeStateFields.read(entry.card, "isFlipped");
            Boolean faceUp = matched || !boardVisible ? false
                    : flipped instanceof Boolean ? !((Boolean) flipped) : null;
            if (Boolean.TRUE.equals(faceUp)) entry.revealed = true;
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("position", entry.position);
            item.put("row", entry.position == null ? null : entry.position / 4);
            item.put("column", entry.position == null ? null : entry.position % 4);
            Object uuid = RuntimeStateFields.read(entry.card, "uuid");
            item.put("uuid", uuid == null ? null : uuid.toString());
            item.put("face_up", faceUp);
            item.put("revealed", entry.revealed);
            item.put("matched", matched);
            // Use the ordinary source converter only for publicly revealed cards.
            item.put("card", entry.revealed ? convertCard.apply(entry.card) : null);
            board.add(item);
            if (Boolean.TRUE.equals(faceUp) && (entry.card == chosen || entry.card == hovered)) {
                selected.add(entry.position);
            }
        }
        result.put("board", board);
        result.put("selected_positions", selected);
        return result;
    }
}
