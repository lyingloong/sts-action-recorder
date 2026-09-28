# ActionRecorder State Schema

状态规范是 ActionRecorder 的公共数据契约。所有直接接入 ActionRecorder、消费其 JSONL/TCP 事件，或把这些事件转换为训练数据的程序，都必须以本文档和 `docs/schema/state.schema.json` 为准。

本文档当前是 Draft 0.1，用于审查字段和边界。现有版本仍会保留部分 CommunicationMod 原始字段；完成审查后，recorder 将把状态输出固定为这里定义的 canonical state。

## 1. 设计目标

每个被游戏接受的语义动作都应尽可能关联一个动作前状态：

```text
observation_before + available_actions + action
```

动作后的状态通过相同 `step_id` 的 `step_resolved` 事件关联：

```text
action_observed + step_resolved(observation_after)
```

动作前状态是训练和离线分析的主样本。动作后状态主要用于验证动作是否生效、判断界面是否稳定和构建状态转移。

## 2. Canonical State

`action_observed.observation_before` 和 `step_resolved.observation_after` 都是一个状态对象，而不是另一个事件 envelope。

```json
{
  "state_schema_version": "0.1",
  "screen_type": "NONE",
  "screen_name": "NONE",
  "screen_up": false,
  "room_phase": "COMBAT",
  "action_phase": "WAITING_ON_USER",
  "room_type": "MonsterRoom",
  "act": 1,
  "floor": 2,
  "class": "IRONCLAD",
  "ascension_level": 0,
  "current_hp": 75,
  "max_hp": 88,
  "gold": 117,
  "act_boss": "TheGuardian",
  "keys": {
    "ruby": false,
    "emerald": false,
    "sapphire": false
  },
  "relics": [],
  "potions": [],
  "deck": [],
  "map": null,
  "screen_state": {},
  "choices": [],
  "combat_state": null
}
```

### 2.1 Top-level fields

| 字段 | 类型 | 必须 | 含义 |
| --- | --- | --- | --- |
| `state_schema_version` | string | 是 | 状态契约版本，不等同于事件 `schema_version`。 |
| `screen_type` | string/null | 是 | 当前需要玩家作出决策的界面类型。 |
| `screen_name` | string/null | 是 | 游戏原始界面名；没有界面时通常为 `NONE`。 |
| `screen_up` | boolean/null | 是 | 游戏是否显示覆盖界面。 |
| `room_phase` | string/null | 是 | 当前房间阶段。 |
| `action_phase` | string/null | 是 | 游戏动作管理器阶段。 |
| `room_type` | string/null | 是 | 当前房间的稳定类型名。 |
| `act` | integer/null | 是 | 当前幕，从 1 开始。 |
| `floor` | integer/null | 是 | 当前层的游戏原始值，消费者不得自行重编号。 |
| `class` | string/null | 是 | 角色稳定 ID。 |
| `ascension_level` | integer/null | 是 | 进阶等级。 |
| `current_hp` | integer/null | 是 | 当前生命。 |
| `max_hp` | integer/null | 是 | 最大生命。 |
| `gold` | integer/null | 是 | 当前金币。 |
| `act_boss` | string/null | 是 | 当前幕首领稳定 ID；暂时无法获取时为 `null`。 |
| `keys` | object/null | 是 | 三色心脏钥匙状态，见下文。 |
| `relics` | array | 是 | 当前持有遗物，按游戏状态顺序。 |
| `potions` | array | 是 | 当前药水栏。 |
| `deck` | array | 是 | 当前牌组中的全部卡牌，不应只保留计数摘要。 |
| `map` | object/null | 是 | 仅地图决策时提供完整地图。 |
| `screen_state` | object | 是 | 当前界面的结构化内容。 |
| `choices` | array | 是 | 游戏提供的界面选项原始顺序和显示文本。 |
| `combat_state` | object/null | 是 | 仅战斗决策时提供战斗详情。 |

canonical state 中定义的字段必须始终存在。字段暂时不可用或不适用于当前场景时使用 `null`；已确认为空的列表使用空数组。状态源整体不可用时，`observation_before` 或 `observation_after` 才使用 `null`。消费者不得把 `null` 解释为 `false`、0 或空列表。

### 2.2 Keys

```json
"keys": {"ruby": false, "emerald": true, "sapphire": false}
```

`ruby` 是红宝石钥匙，`emerald` 是翡翠钥匙，`sapphire` 是蓝宝石钥匙。钥匙字段描述当前持有状态，而不是是否见过对应奖励。无法读取时应置为 `null`，不能伪造三个 `false`。

### 2.3 Inventory entities

实体优先使用稳定 `id`，`name` 仅用于显示和自然语言消费。未知字段可以追加，但不能改变既有字段含义。

```json
{"id":"Strike_R","name":"打击","upgrades":0}
```

ActionRecorder 只保存游戏运行时的原始状态和稳定实体字段，不维护静态知识库、`description` 或 `experience`。静态知识库由 `sts-agent` 在消费状态并构建 prompt 时注入，不能回写或混入 recorder 的 canonical state。

### 2.4 Map visibility

完整地图只允许出现在 `screen_type=MAP` 的状态中：

```json
"map": {"current_node":{"x":2,"y":4},"next_nodes":[],"boss_available":true,"nodes":[]}
```

战斗、事件、商店、奖励、营火和地图过渡状态中，`map` 必须为 `null`。不能把底层 CommunicationMod 残留地图复制进动作前状态，也不能保留最近一次地图快照。

### 2.5 Combat state

战斗状态至少包含 `turn`、`player`、`hand`、`monsters`、`draw_count`、`discard_count` 和 `exhaust_count`。玩家至少包含生命、格挡、能量和 Powers；敌人至少包含稳定 ID、生命、格挡、死亡标记、Powers 和当前意图；手牌至少包含稳定 ID、名称、费用、升级状态和可打出状态。实时数值优先于静态知识库。

### 2.6 Screen state

`screen_state` 只放当前界面内容，不重复复制全局状态：`EVENT` 放事件正文和选项；`CARD_REWARD`/`GRID` 放卡牌和选择约束；`COMBAT_REWARD` 放已公开奖励；`SHOP_SCREEN` 放商品和价格；`BOSS_REWARD` 放遗物；`REST` 放营火选项；`MAP` 放当前节点和下一节点。其他界面只保留稳定、公开且与当前决策相关的字段。

## 3. Action snapshot contract

每个 `action_observed` 必须包含动作前状态、动作能力线索和已经被游戏接受的语义动作：

```json
{
  "type":"action_observed",
  "step_id":"run-id:12",
  "observation_before":{"state_schema_version":"0.1"},
  "available_actions":{"commands":["play","end","potion"],"choices":[]},
  "action":{"id":"END_TURN","kind":"end_turn"}
}
```

`observation_before` 是训练样本的唯一推荐状态来源。消费者不得用时间戳去另一个 TCP 状态流中猜测匹配状态。`available_actions` 完全遵循 CommunicationMod 的原始命令和选项语义，当前不保证已经展开到每张卡牌和每个目标；需要完整合法动作集合时，由下游消费者派生，不能改变 `available_actions` 的语义。

`action` 的参数必须使用结构化字段，不能只编码在本地化显示文本中。`chosen_action` 如果存在只是兼容别名，新消费者应优先读取 `action`。

## 4. Lifecycle and validity

- `action_observed` 是动作前快照；
- `step_resolved` 用相同 `step_id` 提供动作后状态；
- `resolution=stable_state` 表示动作队列清空并观察到稳定状态；
- `resolution=next_decision` 表示下一次决策先发生；
- 任一状态为 `null` 表示状态源不可用，不是空游戏状态；
- 没有有效动作前状态的动作可以审计，但不得直接进入状态条件 SFT；
- ActionRecorder 不定义或执行“自动动作”；自动执行属于 `sts-agent` 的消费端逻辑。自动动画、卡牌自动入队、鼠标 hover、低层输入和系统过渡不属于 recorder 的语义玩家动作。

## 5. Versioning rules

事件 envelope 的 `schema_version` 和状态对象的 `state_schema_version` 分开递增。增加可选字段时保持主版本；改变字段含义、类型或可见性时增加状态主版本；删除字段只能在新主版本进行。种子属于 `run_started` 等 run 元数据，不重复写入每个状态。每个数据集必须记录 schema 和 ActionRecorder mod 版本。下游转换器必须保留版本、`run_id`、`step_id`、稳定 ID 和未知字段，不能静默丢失。

## 6. Confirmed decisions

以下决策已确认并纳入本规范：

1. `available_actions` 遵循 CommunicationMod 的原始语义，不在 recorder 中重新定义完整合法动作集合。
2. `deck`、`relics` 和 `potions` 保存完整明细，不使用数量摘要替代。
3. 非地图场景的 `map` 固定为 `null`，不保留最近一次地图快照。
4. `seed` 只保存于 run 元数据，不重复写入每个状态。
5. recorder 不包含静态知识库；知识库只在 `sts-agent` 消费状态时注入。
6. 状态字段不可用时使用 `null`；已确认为空的列表使用 `[]`。
7. recorder 只记录实际被游戏接受的语义玩家动作；自动执行只属于 `sts-agent`。

后续若修改这些约束，应提升 schema 版本或在本节追加变更记录，不要在下游脚本中私自形成第二套字段解释。
