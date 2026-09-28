# Event Schema

事件中的 `observation_before` 和 `observation_after` 必须遵循
[STATE_SCHEMA.md](STATE_SCHEMA.md)。本文档只定义事件 envelope、动作事件和
生命周期；状态字段不在这里重复维护。

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

`event_seq` is process-wide rather than reset per run. `run_id` identifies the current game run and remains stable when the same save is reopened. Events before a run is recognized may not have `run_id`. `step_id` identifies a decision within one process/run; do not assume its counter survives a process restart.

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
  },
  "chosen_action": {
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
  },
  "step_id": "run-id:12",
  "observation_before": {"screen_type": "NONE", "combat_state": {}},
  "available_actions": {"commands": ["play", "end", "potion"], "choices": []}
}
```

`chosen_action` is identical to `action` (including all action-specific fields), provided as an explicit training label. The `id` is intended as a stable trajectory action identifier. The `kind` describes the event category. Additional fields are action-specific.

### Common action identifiers

| ID pattern | `kind` | Notes |
| --- | --- | --- |
| `PLAY:card=<n>` or `PLAY:card=<n>:target=<m>` | `play_card` | `n` is one-based hand position when the player's card enters the queue; automated card plays are excluded. |
| `END_TURN` | `end_turn` | End current combat turn. |
| `POTION_USE:slot=<n>` or `POTION_USE:slot=<n>:target=<m>` | `potion_use_requested` | Potion slot is zero-based. |
| `POTION_DISCARD:slot=<n>` | `potion_discarded` | Potion slot is zero-based. |
| `MAP:x=<x>:y=<y>` | `map_node_selected` | Map coordinate. |
| `CHOOSE:index=<n>` | `event_option_selected`, `neow_option_selected`, or reward selection | Meaning depends on `kind` and surrounding state. |
| `CAMPFIRE:<action>` | `campfire_action` | `REST`, `SMITH`, `LIFT`, `TOKE`, `DIG`, or `RECALL`. |
| `REWARD:TAKE:<type>` | `reward_<type>_claimed` | Reward type is also present in payload. |
| `SKIP` | `card_reward_skipped` or `boss_relic_skipped` | Use `kind` to distinguish screen. |
| `PROCEED` | `continue_button` | Explicit proceed click from combat rewards, campfire, events or boss transitions. `screen_before` preserves the originating screen. |
| `RETURN` | `return_button` | Explicit cancel/back button input. |
| `LEAVE` | `leave_button` | Explicit cancel/leave button input on a reward/shop screen. Automatic screen closure is excluded. |

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

## Decision snapshots and state joins

When CommunicationMod is enabled in the same game process, ActionRecorder calls its public state converter on the game thread. `observation_before` is the raw CommunicationMod `game_state` captured at the action entry (or directly at the action boundary for actions already recorded at entry). `available_actions` contains CommunicationMod's coarse `available_commands` and, when present, `choice_list`. **These are not an exhaustive, target-resolved legal-action list**: combat hand, enemies and potions are available inside `observation_before.combat_state` and the consumer must expand them for a training action space.

The recorder adds a normalized `game_state.keys` object when the game exposes the
standard Slay the Spire heart-key flags:

```json
{"keys":{"ruby":false,"emerald":true,"sapphire":false}}
```

`ruby` is the red key, `emerald` is the green key, and `sapphire` is the blue
key. This field is obtained from the game's `Settings` flags because the
current CommunicationMod converter does not serialize them. If the game cannot
expose the key flags, the canonical state uses `keys: null`; it must not
synthesize three false values. Files written before state schema 0.1 may still
omit this field and should be treated as legacy data. The canonical state
contract and its machine-readable draft are maintained in `STATE_SCHEMA.md` and
`schema/state.schema.json`.

With no CommunicationMod, or if its converter cannot produce a state at that instant, `observation_before` and `available_actions` are `null`, while the action is still saved. A consumer should reject these steps for state-conditioned training rather than silently treating the null as a valid observation.

After an `action_observed`, the recorder waits for queued actions to clear and for two matching state samples at least 120 ms apart. It then emits a `step_resolved` event with the *same* `step_id`:

```json
{"type":"step_resolved","step_id":"run-id:12","observation_after":{},"resolution":"stable_state"}
```

If another human decision occurs first, the next decision's pre-state closes the previous step with `resolution: "next_decision"`. If the run closes or the state provider fails, `observation_after` is `null` and `resolution` states why. States from rapid animations or unusual modded screens should still be audited before training. Local JSONL and the TCP stream carry the same ordered events; no timestamp-based join to another process is required.
