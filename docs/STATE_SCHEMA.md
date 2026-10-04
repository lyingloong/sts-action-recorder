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
| HAND_SELECT screen_state | selection_reason、up_to、any_number、for_upgrade、for_transform |
| GRID screen_state | selection_reason、confirm_screen_up、any_number、for_clarity、confirmation_card（ID/名称/UUID） |

卡牌数据来自运行时对象，raw_description 是游戏原文。damage/block 等依赖游戏最近一次
计算，可能尚未包含所有目标相关和特殊效果修正，不能一律解释为最终生效值。
选择场景已有的源字段，例如 for_purge、num_cards，照常保留。

这些字段可能通过同一补丁出现在 CommunicationMod 自己的输出中；接收端仍按状态 ID
关联消息。

## 保存与缺失约定

1. 完整消息保存到本地 JSONL，TCP 传输事件副本。
2. 非 MAP 场景 map=null；MAP 保存源提供的完整地图。
3. seed 不重复放入状态；内部种子保存在 run_started 元数据和文件名。
4. 发布不可用时状态引用为 null，不提供空对象或旧状态冒充有效观察。
5. 源缺失信息保持未知/null/未提供；不能把未知当作 false、0 或已确认空列表。
6. available_commands 和 choice_list 原样保留；提取时 available_actions 只是这两者的包装。
7. 原始数据不注入静态知识、经验、模型提示词或训练标签，不做字符截断。

完整原始状态可能包含抽牌堆等引擎信息。下游给模型构造输入时，应自行处理公开信息、
隐藏顺序和上下文范围；不能把原始记录完整保存等同于全部字段都应进入模型输入。

## 动作后状态

执行跟踪的 action_effects_settled 可以显式引用逻辑结算后的状态。普通动作没有统一的
显式后状态事件；通用提取器在没有明确引用时保持 observation_after=null。

消费者可以依据后续事件、ready_for_command 和具体效果建立自己的后状态关联，但
ready 本身不保证所有效果已完成，下一次点击也可能发生在动画期间。实际使用的后状态
应记录来源，不复制前状态或猜测补齐。

## JSON Schema 与版本

[schema/state.schema.json](schema/state.schema.json) 验证的是
`state_published.message.game_state`，不是事件 envelope、整个 message 或提取后的轨迹行。
非游戏消息没有 game_state 时不应用此 Schema。

事件协议、Mod 版本和代码 commit 一同记录便于复现。新增源字段允许保留；版本更新后
按 [采集验收](COLLECTION_AUDIT.md) 检查状态和动作关联。
