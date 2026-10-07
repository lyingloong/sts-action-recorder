# 状态契约（事件协议 0.5）

本页定义 `state_published.message` 中的原始游戏状态，不是模型提示词或下游规范化状态。
事件关联见 [事件格式](EVENT_SCHEMA.md)，可执行 Schema 见 [state.schema.json](schema/state.schema.json)。

## 来源与发布时机

状态通过 CommunicationMod 的 GameStateConverter 和 sendGameState 发布接口获取。
ActionRecorder 在语义动作入口请求发布，在实际消息生成处加入状态身份并保存。
CommunicationMod 自身后续发布的状态也经过同一链路。

发布时加入：

- recorder_state_id：状态 ID，与事件外层 state_id 相同。
- recorder_state_seq：状态序号，与外层 state_seq 相同。
- recorder_session：记录会话身份。
- recorder_segment_id（0.1.5 起）：当前开始/读档片段，与事件外层 segment_id 对应；无活动片段时为 null。
- recorder_snapshot_only：Recorder 主动请求的采集快照为 true；CommunicationMod 正常发布为 false。
- recorder_snapshot_kind：仅采集快照包含，取 action_before、execution_before 或 effects_settled。

采集快照复用同一脚本管道，但不是命令应答，也不是让控制器提交下一动作的通知。
其 ready_for_command 保留 CommunicationMod 原值，可能仍为 true；执行端必须先检查
recorder_snapshot_only，不能用此类消息确认待执行命令或触发下一次决策。
记录端应照常保存这些快照及状态 ID，供动作前/执行前/结算状态的精确关联使用。
此标记在同步发布调用期间生效，并在成功或异常后恢复，不改变正常 CM 更新时机。

每次动作入口可以产生新状态；不按固定毫秒或每帧序列化完整状态。排队动作在处理前与
效果结算边界另行发布，见 [提交与执行](EVENT_SCHEMA.md#排队动作的提交与执行)。

状态关联使用明确 ID。可以附加校验幕、层、原始界面等 context，但不能用“同屏最近状态”
替代缺失引用。状态匹配不等于动作可重放，也不等于游戏效果已结算。

## 消息结构

message 保留 CommunicationMod 原始 envelope：

```json
{
  "in_game": true,
  "ready_for_command": true,
  "available_commands": ["choose", "proceed"],
  "game_state": {
    "screen_type": "COMBAT_REWARD",
    "map": null,
    "choice_list": ["gold"]
  },
  "recorder_state_id": "session:state-12",
  "recorder_state_seq": 12,
  "recorder_session": "session"
}
```

示例仅展示结构，实际消息保存完整明细。

| 部分 | 内容 |
| --- | --- |
| 全局 | class、ascension_level、act、floor、HP/最大 HP、gold、房间/界面/动作阶段、本幕 act_boss |
| keys | ruby（红）、emerald（绿）、sapphire（蓝） |
| deck / relics / potions | 完整实体列表，保留源 ID、名称、卡牌 UUID/升级/费用、遗物计数、药水可用信息等 |
| combat_state | turn、玩家能量/格挡/HP/Powers、手牌、敌人 HP/格挡/Powers/意图、伤害信息及牌堆 |
| map | 仅地图场景的源图结构、当前/下一节点及 Boss 可达性（源提供时） |
| screen_state | 当前事件、商店、奖励、营火、选牌等界面的完整源字段 |
| choice_list | CommunicationMod 当前选择列表 |

保留源命名和类型，例如 is_screen_up、combat_state、map 数组。未知或新增字段也保存，
不把实体明细改成数量摘要。

## Recorder 补充字段

| 位置 | 字段 / 来源 |
| --- | --- |
| game_state.keys | 从游戏 Settings 读取 hasRubyKey、hasEmeraldKey、hasSapphireKey |
| 卡牌 | base_damage/damage、base_block/block、base_magic_number/magic_number、base_cost/cost_for_turn、target、color、raw_description |
| 卡牌（0.1.2 起） | retain、self_retain、free_to_play_once、is_cost_modified、is_cost_modified_for_turn、is_innate、purge_on_use、exhaust_on_use_once、in_bottle_flame、in_bottle_lightning、in_bottle_tornado |
| combat_state.player（0.1.2 起） | stance、base_energy_per_turn、energy_per_turn、master_hand_size、game_hand_size、cards_played_this_turn |
| Match and Keep! screen_state（0.1.2 起） | match_game：阶段、剩余尝试、配对数、公开棋盘和当前选中位置 |
| HAND_SELECT screen_state | selection_reason、up_to、any_number、for_upgrade、for_transform |
| GRID screen_state | selection_reason、confirm_screen_up、any_number、for_clarity、confirmation_card（ID/名称/UUID） |
| COMBAT_REWARD screen_state.rewards（0.1.5 起） | reward_id、reward_index、linked_reward_id；只标识当前奖励对象，不提前暴露未打开的卡牌内容 |
| CARD_REWARD screen_state（0.1.5 起） | source_reward_id；无 RewardItem 来源的独立选牌为 null |
| BOSS_REWARD screen_state.relics（0.1.5 起） | 每个候选遗物的 reward_id、reward_index |

卡牌数据来自运行时对象。0.1.4 起，`description` 将原始卡牌说明中的 BaseMod 动态变量
按当前卡牌对象的值展开；`raw_description` 保留带占位符的游戏原文。字段
`description_source="game_runtime"` 标记来源，`description_complete` 表示是否仍有未解析变量，
`unresolved_description_variables` 列出未解析的变量名，`multi_damage` 保留当前缓存数组。
遗物和 Power 的 `description` 取当前游戏对象；药水的 `description` 合并对象描述和 tooltip 正文，
并保留 `tooltips`、`raw_description`、`target`。这些字段不从下游知识库补造。damage/block 等依赖游戏最近一次
计算，可能尚未包含所有目标相关和特殊效果修正，不能一律解释为最终生效值。
选择场景已有的源字段，例如 for_purge、num_cards，照常保留。

这些字段可能通过同一补丁出现在 CommunicationMod 自己的输出中；接收端仍按状态 ID
关联消息。

### 卡牌运行时标记

这些布尔值直接读取 AbstractCard，不根据描述或静态知识推断：

| 字段 | 游戏字段 | 含义 |
| --- | --- | --- |
| retain | retain | 当前保留标记 |
| self_retain | selfRetain | 卡牌自身保留标记 |
| free_to_play_once | freeToPlayOnce | 下一次打出免费标记，不改写原有费用字段 |
| is_cost_modified / is_cost_modified_for_turn | isCostModified / isCostModifiedForTurn | 费用修改标记 |
| is_innate | isInnate | 固有标记 |
| purge_on_use / exhaust_on_use_once | purgeOnUse / exhaustOnUseOnce | 使用后移除 / 单次消耗标记 |
| in_bottle_flame / in_bottle_lightning / in_bottle_tornado | inBottleFlame / inBottleLightning / inBottleTornado | 对应瓶装遗物标记 |

这些是原始对象标记，不代表综合所有 Powers/遗物后的最终规则。例如某个 Power 可以使
整手牌保留或使技能免费，但不一定逐张设置 retain/freeToPlayOnce；下游仍需结合 Powers。
字段不可读取时为 null，false 表示确实读取到 false。

### 玩家姿态和回合计数

字段位于 combat_state.player，与既有当前能量 energy 并列：

| 字段 | 来源与准确含义 |
| --- | --- |
| stance | AbstractPlayer.stance 的 `{id: ID, name, description}`；保留 Neutral/Wrath/Calm/Divinity 或自定义 ID，未提供则 null |
| base_energy_per_turn | player.energy.energyMaster：基础每回合能量 |
| energy_per_turn | player.energy.energy：当前战斗的回合能量补充基数；不是当前剩余能量，也不是能量上限 |
| master_hand_size | player.masterHandSize：基础回合抽牌数，不是手牌容量上限 |
| game_hand_size | player.gameHandSize：当前战斗的回合抽牌基数，不是当前手牌数量 |
| cards_played_this_turn | player.cardsPlayedThisTurn：游戏已计入的本回合出牌数，排队但未执行的牌不计入 |

能量/抽牌基数不综合所有额外效果，实际回合获得量可能被冰淇淋、Powers、遗物和抽牌限制
等改变。这些字段只在源提供 combat_state.player 时出现，非战斗场景不补造战斗状态。

### 翻牌小游戏的公开棋盘

事件 `Match and Keep!` 的 screen_state.match_game 保存：

- phase：游戏事件原始阶段名，例如 INTRO、RULE_EXPLANATION、PLAY、CLEAN_UP、COMPLETE。
- remaining_attempts：attemptCount；一对牌结算后减少，正在结算的一对尚可能未扣除。
- matched_pairs：cardsMatched；成功配对数，不是已移除卡牌张数。
- game_done：gameDone；awaiting_resolution：waitTimer 是否大于零。
- selected_positions：当前已翻开并仍作为 chosenCard/hoveredCard 的位置；不是鼠标悬停位置。
- board：完整固定位置列表。每项包括 position、row、column、uuid、face_up、revealed、matched、card。

position 为 0..11，row 为 0..2、column 为 0..3，按与 CommunicationMod 相同的显示棋盘
顺序编号。它不等于会随已翻开/移除卡牌而变化的 choice_list 下标。翻牌动作额外保存
board_position；原 option_index 和动作 ID 保持源选项语义不变。

face_up 表示当前棋盘上正面显示；revealed 表示本事件中已公开过；matched 表示配对结算后
从剩余棋盘移除。配对动画期间两张牌仍在棋盘，matched 尚为 false。配对移除后仍保留
原位置，方便复盘。card 使用原卡牌转换器的完整字段，但仅在 revealed=true 时提供。
未翻开的 card=null，盖回后的已知牌面仍保留，不提前导出隐藏卡牌 ID、名称或效果。

棋盘记忆属于当前事件对象，事件切换不会复用。按游戏原有 placeCards 时机初始化，
不每帧生成状态；每次实际消息转换时读取计数与棋盘。翻牌动作的前状态先保存，随后才
标记该牌已揭示，避免答案泄漏到 observation_before。无法取得棋盘/位置/牌面时为 null。

## 保存与缺失约定

1. 完整消息保存到本地 JSONL，TCP 传输事件副本。
2. 非 MAP 场景 map=null；MAP 保存源提供的完整地图。
3. seed 不重复放入状态；内部种子保存在 run_started 元数据和文件名。
4. 发布不可用时状态引用为 null，不提供空对象或旧状态冒充有效观察。
5. 源缺失信息保持未知/null/未提供；不能把未知当作 false、0 或已确认空列表。
6. available_commands 和 choice_list 原样保留；提取时 available_actions 只是这两者的包装。
7. 原始数据不注入静态知识、经验、模型提示词或训练标签，不做字符截断。

Mod 0.1.3 起，源序列化器启用 serializeNulls，保留嵌套对象/Map 的显式 null。
例如游戏 NeutralStance 本身 name=null，记录为 `{"id":"Neutral","name":null,"description":""}`，
不补造名称。未揭示的翻牌 card=null、不可读取的运行时字段也不再被序列化器省略。
旧记录中的省略字段仍按未知处理，更新不回写历史日志。

完整原始状态可能包含抽牌堆等引擎信息。下游给模型构造输入时，应自行处理公开信息、
隐藏顺序和上下文范围；不能把原始记录完整保存等同于全部字段都应进入模型输入。

## 动作后状态

动作后状态保留为可追溯的运行时快照，**不能默认视为该动作完整结算后的结果**。
动画期间玩家可以继续操作；钥匙到账、卡牌效果、奖励入栏等逻辑更新可能延迟，多个动作的
效果也可能交错。因此后状态中仍可能保留动作前的数值，或包含其他操作的影响。这是后状态
使用上的已知局限，不应仅凭字段存在推断动作失败、奖励未获得或结算已经完成。

执行跟踪的 action_effects_settled 可以显式引用逻辑结算后的状态。普通动作没有统一的
显式后状态事件；通用提取器在没有明确引用时保持 observation_after=null。

Mod 0.1.3 起，结算跟踪跨过双重打击等引起的队首自动重放及其后续连锁；只有队列空，
或到达下一项已跟踪的人类动作，且普通 action/preTurn 队列已处理完，才发布结算状态。
自动重放不生成新的人类动作。结束回合还必须等待回合推进；战斗结束/真实终局仍可
作为终止边界。不等待纯视觉动画。

消费者可以依据后续事件、ready_for_command 和具体效果建立自己的后状态关联，但
ready 本身不保证所有效果已完成，下一次点击也可能发生在动画期间。实际使用的后状态
应记录来源，不复制前状态或猜测补齐。

## JSON Schema 与版本

[schema/state.schema.json](schema/state.schema.json) 验证的是
`state_published.message.game_state`，不是事件 envelope、整个 message 或提取后的轨迹行。
非游戏消息没有 game_state 时不应用此 Schema。

事件协议、Mod 版本和代码 commit 一同记录便于复现。新增源字段允许保留；版本更新后
按 [采集验收](COLLECTION_AUDIT.md) 检查状态和动作关联。

Mod 0.1.2/0.1.3/0.1.4 在协议 0.5 中增补上述字段。Schema 定义其类型但允许旧记录缺失；
audit_trace 按事件的 mod_version 检查对应版本字段的存在性、类型和翻牌隐藏信息边界。
