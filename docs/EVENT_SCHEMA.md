# Event Schema

CommunicationMod bridge 生成的规范化轨迹中的 `observation_before` 和
`observation_after` 必须遵循 [STATE_SCHEMA.md](STATE_SCHEMA.md)。本文档只
定义 ActionRecorder 的事件 envelope、动作事务和生命周期；状态字段不在这里
重复维护。

The recorder writes UTF-8 JSONL. Each line is one JSON object. The schema is intentionally append-only: consumers should tolerate fields added in later versions.

## Common envelope

Ordinary events contain:

```json
{
  "schema_version": "0.4",
  "mod_version": "0.1.0",
  "recorder_session": "...",
  "capture_mode": "game_actions",
  "event_seq": 17,
  "timestamp_ms": 1780000000000,
  "type": "action_accepted",
  "run_id": "..."
}
```

`event_seq` is process-wide rather than reset per run. `run_id` identifies the current game run and remains stable when the same save is reopened. Events before a run is recognized may not have `run_id`. `step_id` identifies a decision within one process/run; do not assume its counter survives a process restart.

## Action transactions (schema 0.4)

Every semantic decision has one `transaction_id`. `action_begin` is emitted at
the game method entry, before mutable state changes; `action_accepted` is
emitted only after the method accepts the choice. A failed or cancelled choice
emits `action_rejected`. These markers intentionally do not contain a canonical
state snapshot: the CommunicationMod bridge owns that state and performs the
strict join.

```json
{"type":"action_begin","transaction_id":"session:tx-...","expected_screen":"MAP"}
{"type":"action_accepted","transaction_id":"session:tx-...","action":{"id":"MAP:x=1:y=0","kind":"map_node_selected","x":1,"y":0}}
```

The bridge must not emit a normalized trajectory row unless it can match the
same transaction to one unique stable before-state. Match failures are kept in
the bridge diagnostic log as `unmatched` or `ambiguous`.

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

## Normalized trajectory row (CommunicationMod bridge output)

The following payload describes a normalized trajectory row written by the
CommunicationMod bridge. It is not an event emitted by the ActionRecorder
Mod's local JSONL/TCP stream.

The bridge writes one row for each matched transaction. This row is separate
from the ActionRecorder Mod's local event stream:

```json
{
  "schema_version": "0.4",
  "source": "communicationmod_action_recorder_bridge",
  "run_id": "run-id",
  "step_id": "session:tx-id",
  "pre_state": {"screen_type": "NONE", "combat_state": {}},
  "policy_observation": {"screen_type": "NONE", "combat_state": {}},
  "legal_actions": [{"id": "PLAY:card=2:target=0", "kind": "play_card"}],
  "action": {"id": "PLAY:card=2:target=0", "kind": "play_card"},
  "raw_action": {"id": "PLAY:card=2:target=0", "kind": "play_card"},
  "post_state": {"screen_type": "NONE", "combat_state": {}},
  "state_match": {"match_status": "exact_legal_action", "before_state_seq": 12}
}
```

The bridge derives `legal_actions` and `policy_observation` using the same
sts-agent runtime used during play. `raw_action` preserves the game-side event.
The action `id` is the selected label; action-specific details remain attached
to `action` and `raw_action`.

### Common action identifiers

| ID pattern | `kind` | Notes |
| --- | --- | --- |
| `PLAY:card=<n>` or `PLAY:card=<n>:target=<m>` | `play_card` | `n` is one-based hand position when the player's card enters the queue; automated card plays are excluded. |
| `END_TURN` | `end_turn` | End current combat turn. |
| `POTION_USE:slot=<n>` or `POTION_USE:slot=<n>:target=<m>` | `potion_use_requested` | Potion slot is zero-based. |
| `POTION_DISCARD:slot=<n>` | `potion_discarded` | Potion slot is zero-based. |
| `MAP:x=<x>:y=<y>` | `map_node_selected` | Map coordinate. |
| `CHEST:OPEN:<class>` | `chest_opened` | Small/medium/large and boss chests record the concrete chest class and reward flags. |
| `CHOOSE:index=<n>` | `event_option_selected`, `neow_option_selected`, or reward selection | Meaning depends on `kind` and surrounding state. |
| `CAMPFIRE:<action>` | `campfire_action` | `REST`, `LIFT`, `TOKE`, `DIG`, or `RECALL`. |
| `CAMPFIRE:SMITH:card=<uuid>` | `campfire_smith` | Legacy schema 0.2: one complete smith decision. |
| `CAMPFIRE:SMITH` | `campfire_action` | Schema 0.3: choose the smith action before the card grid opens. |
| `REWARD:TAKE:<type>` | `reward_<type>_claimed` | Reward type is also present in payload. |
| `CHOOSE:index=<n>` | `card_option_selected` | Discovery-style potion/card or Choose One option. `selection_mode` is `discovery` or `choose_one`; the offered card ID, name and UUID are included. |
| `SKIP` | `card_reward_skipped` or `boss_relic_skipped` | Use `kind` to distinguish screen. Card-reward skip is captured before the screen closes. |
| `SHOP:PURGE:card=<uuid>` | `shop_purge` | Legacy schema 0.2: one complete purge decision. |
| `SHOP:PURGE` | `shop_purge_opened` | Schema 0.3: choose the purge service before selecting a card. |
| `SELECT_CARDS:grid:<uuid-list>` | `card_selection_changed` | Schema 0.3: select/deselect grid cards. |
| `SELECT_CARDS:CONFIRM` | `card_selection_confirmed` | Schema 0.3: accept the current grid selection. |
| `SELECT_CARDS:HAND_CONFIRM` | `card_selection_confirmed` | Schema 0.3: accept the current hand selection (for example Armaments). |
| `PROCEED` | `continue_button` | Explicit proceed click from combat rewards, campfire, events or boss transitions. `screen_before` preserves the originating screen. |
| `RETURN` | `return_button` | Explicit cancel/back button input. |
| `LEAVE` | `leave_button` | Explicit cancel/leave button input on a reward/shop screen. Automatic screen closure is excluded. |

Some actions include a human-readable entity name and stable game ID. Consumers should prefer IDs over localized names when available.

Opening the card-selection screen as a consequence of `REWARD:TAKE:CARD` is not a second player action. Likewise, reopening the shop after the purge grid closes is not a new `OPEN:SHOP` action.

`run_ended.reason` is `death` or `victory` when a terminal screen was reached before the dungeon reference was cleared. Leaving or abandoning a live run remains `dungeon_left` with `terminal: false`.

Discovery-style selections do not call `CardRewardScreen.acquireCard()`: the selected card is assigned to `discoveryCard`. Choose One effects instead invoke `AbstractCard.onChoseThisOption()`. Both are captured before the screen or the option effect mutates state. Initial empty hand/grid selection sets are not player actions; a later deselection to an empty set still is.

Grid selections are captured at the accepted `selectedCards.add(card)` call. This matters for effects such as Secret Technique, whose one-card grid closes within the same update and would be missed by an update postfix. The recorder checks the actual grid selection list, so unrelated `ArrayList.add` calls are ignored.

Shop purchase events are emitted only after a purchase is accepted. The recorder
captures the original relic or potion before the purchase method runs, so
Courier-style shop replacement does not change the entity written to the
trace. A failed purchase caused by insufficient gold, Sozu, or a full potion
inventory is not emitted as a purchase action.

## Historical room-level card decisions (event schema 0.2)

Smithing and shop purging are recorded directly as complete decisions by the
Mod. Their `observation_before` and `available_actions` come from the room-level
state saved before opening the card grid. The completion hook writes one
`action_observed`, with `card_id`, `card_name`, `card_uuid`, `upgrades_after` and
`decision_started_at_ms`. Its `step_resolved` contains the later stable state.
The UUID identifies the deck instance, including when several cards share an ID.
If the start was missed (for example when resuming inside a grid), the actual
action is still recorded, but the unavailable before-state is `null`.

The upgrade callback wraps `AbstractCard.upgrade()` inside
`CampfireSmithEffect.update()`. The purge callback wraps `CardGroup.removeCard()`
inside `ShopScreen.updatePurge()`. Both run after the original call. They do not
depend on button click flags or downstream inference from state differences.

Auxiliary events preserve flow details without creating extra decisions:

- `decision_started`: opening a smith/purge flow; field `decision` is
  `CAMPFIRE:SMITH` or `SHOP:PURGE`.
- `decision_detail`: observed intermediate selection/confirmation/back input;
  fields include `decision`, `id`, `kind` and the action-specific details.
- `decision_cancelled`: leaving the flow before completion; includes `reason`.

Returning from the upgrade preview to the card grid keeps the decision open.
Leaving the grid, changing rooms or leaving the run cancels an unfinished flow.
Cancellation never emits a successful smith/purge action. Auxiliary events are
not required to interpret the completed action and are not training labels.
Other card-selection flows retain their existing action granularity.

Version 0.1 files contain separate smith/open-purge/grid events. They are not
rewritten automatically and should not be mixed with 0.2 room-decision labels
without a separate migration.

## Historical fine-grained card selection (event schema 0.3)

In schema 0.3, smithing and shop purging are recorded as the individual inputs
the player performs rather than one synthesized room-level success action:

- `CAMPFIRE:SMITH` (`campfire_action`) records choosing Smith at the campfire.
- `SHOP:PURGE` (`shop_purge_opened`) records choosing the purge service.
- `SELECT_CARDS:grid:<uuid-list>` (`card_selection_changed`) records selecting
  or deselecting cards in the grid. The payload includes the current selection
  and flags such as `for_upgrade` and `for_purge`.
- `SELECT_CARDS:CONFIRM` (`card_selection_confirmed`) records a valid explicit
  confirm-button press and includes the selected card instances and grid use.
- `SELECT_CARDS:HAND_CONFIRM` (`card_selection_confirmed`) records confirmation
  on the distinct hand-card selection screen, including effects such as
  Armaments that select a card to upgrade.
- `RETURN` / `LEAVE` records backing out where the game exposes a return/leave
  input.

Each of these is preserved as an individual normalized trajectory row by the
bridge. There is no synthetic `CAMPFIRE:SMITH:card=<uuid>` or
`SHOP:PURGE:card=<uuid>` success action in new traces. Some one-card choices
close immediately when selected and therefore have no separate confirm input;
they are represented by the selection action followed by its resolved state.
The confirm click is intercepted immediately after the confirm button update,
before the grid screen consumes the click flag.

The card-selection event granularity also applies to other game flows that use
the same grid/hand selection screens, including event rewards and card effects.
Consumers should use each event's `kind` and observation to distinguish the
purpose. Existing 0.2 traces remain unchanged and must not be interpreted as
0.3 labels without migration.

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

## Transaction markers and external state joins (schema 0.4)

The preferred integration does not join two asynchronous streams by timestamp.
ActionRecorder emits `action_begin` immediately before a semantic game method
mutates state and `action_accepted` after the method accepts the action. Both
carry a `transaction_id`; cancellation emits `action_rejected`. The
CommunicationMod bridge assigns monotonic `state_seq` values to its stable
state stream and selects a before-state only when screen context, action
legality and temporal bounds agree. A failed or ambiguous match is retained in
the bridge diagnostic JSONL and is excluded from the normalized training
trajectory.

ActionRecorder 本地 JSONL 只记录动作事务和生命周期。规范化状态、动作后状态
以及合法动作列表由 CommunicationMod bridge 从同一状态流匹配生成；下游不得
再启动旧的独立接收器或按时间戳拼接第二条状态流。

## Decision snapshots and state joins

在 schema 0.4 路径中，外部 bridge 执行状态关联，规范化记录会保留
CommunicationMod 的粗粒度 `available_commands` 和 `choice_list`。**这些不是
exhaustive, target-resolved legal-action list**: combat hand, enemies and
potions are available inside `observation_before.combat_state` and the consumer
must expand them for a training action space.

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

With no CommunicationMod, or if its converter cannot produce a state at that instant,
the bridge records `observation_before: null` and excludes the row from state-
conditioned training. If the run closes before a post-state is observed,
`observation_after` is `null` with a diagnostic resolution. In schema 0.4 the
bridge performs this join and consumers must not repeat it with timestamps.
