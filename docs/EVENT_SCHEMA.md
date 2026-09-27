# Event Schema

The recorder writes UTF-8 JSONL. Each line is one JSON object. The schema is intentionally append-only: consumers should tolerate fields added in later versions.

## Common envelope

Ordinary events contain:

```json
{
  "schema_version": "0.1",
  "mod_version": "0.1.0",
  "recorder_session": "...",
  "capture_mode": "game_actions",
  "event_seq": 17,
  "timestamp_ms": 1780000000000,
  "type": "action_observed",
  "run_id": "..."
}
```

`event_seq` is process-wide rather than reset per run. `run_id` identifies the current game run and remains stable when the same save is reopened. Events before a run is recognized may not have `run_id`.

## Lifecycle events

### `run_started`

Contains the character, act, ascension, seed and `run_id`.

```json
{
  "type": "run_started",
  "run_id": "...",
  "character": "IRONCLAD",
  "act": 1,
  "ascension": 0,
  "seed": "4QTKQ7VBMFX47"
}
```

### `room_changed`

```json
{
  "type": "room_changed",
  "room_class": "com.megacrit.cardcrawl.rooms.MonsterRoomElite",
  "act": 1,
  "floor": 6
}
```

### `combat_turn_changed`

Contains the new combat turn number.

### `run_ended`

Contains a reason and whether the recorder identified the run as terminal. Closing the game or abandoning a run may produce a non-terminal end event.

## `action_observed`

Semantic player actions are nested in an `action` object:

```json
{
  "type": "action_observed",
  "action": {
    "id": "PLAY:card=2:target=0",
    "kind": "play_card",
    "card_id": "Strike_R",
    "card_name": "打击",
    "card_uuid": "...",
    "hand_index": 1,
    "target_index": 0,
    "target_id": "JawWorm",
    "energy_on_use": 1,
    "autoplay": false
  }
}
```

The `id` is intended as a stable trajectory action identifier. The `kind` describes the event category. Additional fields are action-specific.

### Common action identifiers

| ID pattern | `kind` | Notes |
| --- | --- | --- |
| `PLAY:card=<n>` or `PLAY:card=<n>:target=<m>` | `play_card` | `n` is one-based hand position at queue time. |
| `END_TURN` | `end_turn` | End current combat turn. |
| `POTION_USE:slot=<n>` or `POTION_USE:slot=<n>:target=<m>` | `potion_use_requested` | Potion slot is zero-based. |
| `POTION_DISCARD:slot=<n>` | `potion_discarded` | Potion slot is zero-based. |
| `MAP:x=<x>:y=<y>` | `map_node_selected` | Map coordinate. |
| `CHOOSE:index=<n>` | `event_option_selected`, `neow_option_selected`, or reward selection | Meaning depends on `kind` and surrounding state. |
| `CAMPFIRE:<action>` | `campfire_action` | `REST`, `SMITH`, `LIFT`, `TOKE`, `DIG`, or `RECALL`. |
| `REWARD:TAKE:<type>` | `reward_<type>_claimed` | Reward type is also present in payload. |
| `SKIP` | `card_reward_skipped` or `boss_relic_skipped` | Use `kind` to distinguish screen. |
| `PROCEED:<destination>` | `continue_button` | A continue/proceed action; destination depends on the game flow. |
| `RETURN` | `return_button` | Generic screen return. |
| `LEAVE` | `leave_button` | Leaving a reward/shop screen. |

Some actions include a human-readable entity name and stable game ID. Consumers should prefer IDs over localized names when available.

## `raw_input`

Only emitted in `raw_input` mode. It contains an `input_type`, raw input fields and a best-effort game context. Raw input is supplementary and should not replace semantic actions for training.

```json
{
  "type": "raw_input",
  "input_type": "key_down",
  "keycode": 32,
  "context": {
    "in_game": true,
    "character": "IRONCLAD",
    "ascension": 0,
    "act": 1,
    "floor": 2,
    "screen": "NONE",
    "room": "com.megacrit.cardcrawl.rooms.MonsterRoom",
    "turn": 1
  }
}
```

## Ordering and state joins

Events are emitted in the order observed by the game thread and queued in that order. A consumer building transitions should associate an action with the nearest preceding state observation from its own state source, then wait for the next state observation after the action. ActionRecorder itself does not capture full game state.

For complete human trajectories, combine CommunicationMod state messages, ActionRecorder `action_observed` messages, timestamps, room/turn events, and local JSONL files for recovery if the TCP stream was interrupted.
