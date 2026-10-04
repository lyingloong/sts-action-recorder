# 事件格式（协议 0.5）

本页定义本地 JSONL 与 TCP 事件。状态内容见 [状态契约](STATE_SCHEMA.md)，
提取后的逐步轨迹格式见 [数据集提取](DATASET_EXTRACTION.md#输出契约)。

## 通用字段

普通事件使用以下 envelope，具体事件增加各自字段：

```json
{"schema_version":"0.5","mod_version":"0.1.1","recorder_session":"session-uuid","capture_mode":"game_actions","event_seq":12,"timestamp_ms":1780000000000,"run_id":"run-uuid","type":"action_begin"}
```

| 字段 | 含义 |
| --- | --- |
| schema_version | 事件协议版本；与 Mod 版本独立 |
| mod_version | 生成记录的 Mod 版本 |
| recorder_session | 当前游戏进程中的记录会话身份 |
| capture_mode | game_actions / raw_input / off |
| event_seq | 普通事件在 session 内递增的序号 |
| timestamp_ms | 生成事件时的 Unix 时间，毫秒 |
| run_id | 对局身份；尚未进入对局时字段可缺省 |
| type | 事件类型 |

去重使用 `(recorder_session,event_seq)`；事件顺序和动作关联分别由序号和显式 ID 确定。
TCP hello 的 event_seq=0 是握手，见 [TCP 接口](TCP_PROTOCOL.md)。

run_id 通过 BaseMod 保存字段跨保存/继续恢复。一次 run_started 不一定意味着新局：
继续存档可以再次发布相同 run_id。原始种子在对局元数据中是有符号 long 的字符串。

## 状态事件

`state_published` 包含 state_id、state_seq 和完整 message：

```json
{"type":"state_published","state_id":"session:state-12","state_seq":12,"message":{"recorder_state_id":"session:state-12","recorder_state_seq":12,"recorder_session":"session","in_game":true,"ready_for_command":true,"available_commands":["play","end"],"game_state":{"screen_type":"NONE"}}}
```

示例省略通用字段和状态明细。message 内外状态 ID/序号对应同一个实际发布结果。
状态编号不同于事件序号：state_seq 数状态，event_seq 数所有普通事件。

## 动作事务

一次细粒度动作使用一个 transaction_id：

```json
{"type":"action_begin","transaction_id":"session:tx-uuid","before_state_id":"session:state-12","expected_screen":"NONE","context":{"act":1,"floor":2,"turn":1}}
{"type":"action_accepted","transaction_id":"session:tx-uuid","execution_tracking":true,"action":{"id":"PLAY:card=1:target=0","kind":"play_card","card_id":"Strike_R","card_uuid":"uuid","hand_index":0,"target_index":0}}
```

示例省略通用字段；玩家出牌的执行跟踪见下一节。

- action_begin 在动作入口发布，引用提交前状态，附原始场景 expected_screen 和 context。
- action_accepted 表示游戏接受提交；action 是完整动作对象，chosen_action 是其兼容别名。
- action_rejected 表示本次尝试被拒绝或取消，附 reason。
- 状态无法发布时 before_state_id 为 null，动作仍保留。
- action.id 是动作描述，可能重复；transaction_id 才是一次动作的身份。
- 只引用明确状态 ID。时间戳、接收顺序或相同场景不足以证明状态属于某个动作。

营火锻造、商店删牌按入口、选牌、确认/返回分别记录。选牌集合的变化是独立动作，
不是直接合并成“升级某牌”或“删除某牌”。明确的返回可以是被接受的导航动作；
action_rejected 则是入口未产生已接受操作的尝试，两者不同。

## 排队动作的提交与执行

Mod 0.1.1 在协议 0.5 中增加执行事件。出牌和结束回合的
`action_accepted.execution_tracking=true`，其提交观察保留在 before_state_id。

```json
{"type":"action_execution_begin","transaction_id":"session:tx-uuid","execution_before_state_id":"session:state-15","logical_boundary":true,"action":{"id":"PLAY:card=1:target=0","kind":"play_card","card_uuid":"uuid","target_index":0},"context":{"act":1,"floor":2,"turn":1}}
{"type":"action_execution_result","transaction_id":"session:tx-uuid","status":"executed"}
{"type":"action_effects_settled","transaction_id":"session:tx-uuid","after_state_id":"session:state-16","logical_boundary":true}
```

| 事件 | 含义 |
| --- | --- |
| action_execution_begin | 队首动作实际处理前的观察和动作参数；尚未进行本动作校验、触发器或状态修改 |
| action_execution_result | executed：进入实际执行分支；skipped：校验失败；cancelled：未执行即从队列移除/离开地牢 |
| action_effects_settled | 已执行动作的逻辑效果结算边界和明确后状态引用 |

执行时卡牌位置可能与提交时不同，应分别保存；卡牌 UUID 用于检查是否同一张卡。
Mod 内部以队列对象身份关联提交和执行，不按时间或同名卡猜测。

executed 证明动作进入执行分支，不表示所有效果和视觉动画已经结束。结算边界也不要求
视觉特效全部结束；结束回合的结算包含敌方行动及下一回合准备，战斗结束可作为终止边界。

执行事件与状态可能不完整，引用不可用为 null。消费者保留提交/执行两套观察，
并按自己的任务选择使用。skipped/cancelled 不能解释为已成功执行的动作。

## 生命周期与辅助事件

| type | 主要字段与含义 |
| --- | --- |
| run_started | character、ascension、seed、act、run_id；包含对局元数据 |
| run_finished | reason=death/victory、terminal=true、context；进入整局终局 |
| run_ended | reason、terminal；离开地牢，保存退出可能 terminal=false |
| room_changed | 房间类、幕/层，用于时间线 |
| combat_turn_changed | turn，用于时间线 |
| raw_input | raw_input 模式中的键鼠/手柄输入，不属于默认语义动作 |

跨幕不结束对局，普通幕 Boss 被击败不表示整局胜利。没有明确终局时保留为未完成；
胜负筛选规则见 [数据集提取](DATASET_EXTRACTION.md)。

## 动作 ID 与参数

| ID / kind | 结构化参数 |
| --- | --- |
| PLAY / play_card | card_id/name/uuid、手牌下标、目标下标/ID、energy_on_use、autoplay |
| END_TURN / end_turn | turn |
| POTION_USE、POTION_DISCARD / potion_use_requested、potion_discarded | slot、potion_id/name；目标型使用附 target_index/id |
| MAP:x=..:y=.. / map_node_selected | 节点坐标 |
| CHOOSE:index=0 / map_boss_selected | 本幕 Boss 入口 |
| CHOOSE:index=.. / neow_option_selected、event_option_selected | 选项下标、事件类 |
| CHOOSE:index=.. / event_card_flipped | 棋盘位置下标、card_uuid；自动翻回不计为玩家操作 |
| CHOOSE:index=0 / event_wheel_spun | 启动转盘 |
| CAMPFIRE:REST/SMITH/LIFT/TOKE/DIG/RECALL / campfire_action | 营火入口选项 |
| OPEN:SHOP / shop_opened | 点击商人；地图进入商店房间另有自己的地图动作 |
| SHOP:BUY_CARD/BUY_RELIC/BUY_POTION / shop_*_purchased | 实体 ID/名称、卡牌 UUID、价格（源提供时） |
| SHOP:PURGE / shop_purge_opened | 点击删牌入口 |
| CHEST:OPEN:.. / chest_opened | 宝箱类型、是否 Boss 宝箱、奖励属性 |
| REWARD:TAKE:.. / reward_*_claimed | 奖励类型、金币、遗物/药水 ID；CARD 表示打开卡牌奖励 |
| CHOOSE:index=.. / card_reward_selected、card_option_selected、boss_relic_selected | 选择下标、实体 ID；局内选择包含 selection_mode |
| SKIP / card_reward_skipped、boss_relic_skipped | 跳过选择 |
| REWARD:BOWL / singing_bowl_chosen | 颂钵换取最大生命 |
| SELECT_CARDS:grid:..、SELECT_CARDS:hand:.. / card_selection_changed | selected_cards 完整集合、选择目的、本次 card_uuid（源提供时） |
| SELECT_CARDS:CONFIRM、SELECT_CARDS:HAND_CONFIRM / card_selection_confirmed | 确认网格/手牌选择，允许游戏接受的零张选择 |
| PROCEED、RETURN、LEAVE / continue_button、return_button、leave_button | 导航动作、原始界面名 |

PLAY 描述中的手牌序号从 1 起；hand_index、target_index、slot、CHOOSE index 从 0 起。
实体身份和目标应读取结构化参数，不只解析本地化标签。特殊选择无法用某个通用 CHOOSE
重放时，原始记录仍保留；可重放动作空间由消费者构建。

## 版本与消费者约定

新增未知字段原样保留。处理旧记录时同时检查事件协议和 Mod 版本；同为协议 0.5 的早期
Mod 也可能没有执行事件，不能补造执行前状态。解析事件、判断动作是否执行、构造训练
标签是不同步骤。提取工具输出完整细粒度事务；具体训练步骤筛选由下游决定。
