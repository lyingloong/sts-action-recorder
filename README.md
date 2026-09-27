# StS Action Recorder

ActionRecorder 是一个独立的 Slay the Spire 1 Mod，用于记录人类在游戏内产生的游戏轨迹动作，并通过本地 TCP JSONL 通道提供给任意外部消费者。

它与 CommunicationMod 分工不同：

- CommunicationMod 可以负责把游戏状态提供给外部程序；
- ActionRecorder 负责从游戏内部捕获游戏动作和可识别的语义事件；
- 外部消费者可以把状态、动作和动作后的状态合并为训练轨迹。

## 当前版本

第一版基础设施已经包含：

- ModTheSpire/BaseMod 可加载入口；
- 本地 TCP 客户端，默认连接 127.0.0.1:8766；
- 按每局保存独立 JSONL 文件；
- 通过 BaseMod 存档字段保存 run identity，退出后重进继续追加原文件；
- 可选的键盘按下/释放/输入、鼠标移动/按下/释放/拖拽、滚轮等原始输入事件；
- 可选的手柄按钮按下/释放原始输入事件；
- run_started、run_ended、room_changed 生命周期事件；
- 覆盖 sts-agent 动作空间的语义事件：出牌（手牌位置、卡牌 ID、目标）、结束回合、药水使用/丢弃、地图节点、Neow 选项、事件选项、营火动作、宝箱开启、继续/返回/离开界面；
- 覆盖战斗外奖励动作：金币、遗物、药水、卡牌奖励、钥匙拾取，卡牌奖励跳过，Boss 遗物选择/跳过；
- 覆盖商店购买卡牌/遗物/药水、删牌，以及星盘、空鸟笼等触发的多选牌界面选择变化；
- 事件序号、时间戳和 Mod 版本字段；
- 断线重连和 stderr 诊断。
- 即使 TCP 接收端未启动，也会追加写入游戏目录下的 data/actionrecorder-events.jsonl。
- 文件写入和 TCP 发送均在后台线程执行，不阻塞游戏更新线程。

默认模式只写游戏轨迹动作，不写键盘鼠标事件。原始输入层不依赖角色，也不依赖界面类型；所有角色使用同一套代码。原始输入模式只用于调试和补充开发，语义事件只作为附加信息。

## 构建

项目配置沿用本机 nine-sword-techniques-sts-mod：Java 8、Maven、Steam 路径 D:/Steam/steamapps。如果路径不同，构建时覆盖 Steam.path 属性：

    mvn package -DSteam.path=D:/Steam/steamapps

package 会将 target/action-recorder.jar 复制到游戏的 mods 目录。

## 运行参数

默认值：

    actionrecorder.host=127.0.0.1
    actionrecorder.port=8766
    actionrecorder.connect_timeout_ms=250
    actionrecorder.reconnect_interval_ms=1000
    actionrecorder.events_dir=data/actionrecorder
    actionrecorder.capture_mode=game_actions

capture_mode 可选值：

- game_actions：默认，只记录游戏轨迹动作；
- raw_input：记录游戏动作，并额外记录所有键盘、鼠标和手柄输入；
- off：关闭记录。

可以通过 ModTheSpire 启动 JVM 参数覆盖，例如 -Dactionrecorder.capture_mode=raw_input。

连接建立后，Java 端发送一行 hello。之后每条消息都是一个 JSON 对象并以换行结束。

## 游戏动作覆盖

默认 `game_actions` 模式记录“游戏语义动作”，不记录鼠标位置和键盘按键。当前 `action_observed.action` 的主要 `kind` 如下：

| 场景 | kind | 说明 |
| --- | --- | --- |
| 战斗 | `play_card` | 牌的 ID、UUID、手牌位置、目标敌人和能量参数 |
| 战斗 | `potion_use_requested` / `potion_discarded` | 药水槽位和药水 ID；目标选择仍由后续游戏状态体现 |
| 战斗 | `end_turn` | 结束当前回合 |
| 地图 | `map_node_selected` | 地图节点 x/y |
| Neow/事件 | `neow_option_selected` / `event_option_selected` | 选项下标和事件类型 |
| 营火 | `campfire_action` | 休息、锻造、举重、吃药、挖掘、回忆 |
| 奖励 | `reward_gold_claimed`、`reward_relic_claimed`、`reward_potion_claimed`、`reward_card_claimed`、`reward_*_claimed` | 奖励类型及实体字段；钥匙也保留为独立奖励类型 |
| 奖励 | `card_reward_opened` / `card_reward_selected` / `card_reward_skipped` | 打开奖励、选牌、跳过 |
| Boss 奖励 | `boss_relic_selected` / `boss_relic_skipped` | Boss 遗物选择或跳过 |
| 商店 | `shop_card_purchased`、`shop_relic_purchased`、`shop_potion_purchased`、`shop_card_purged` | 商店购买和删牌 |
| 其他界面 | `chest_opened`、`continue_button`、`return_button`、`leave_button` | 宝箱、继续、返回、离开 |
| 多选牌 | `card_selection_changed` | 当前选择集合及用途标记（升级、转化、删除、净化等） |

语义补丁只在动作被游戏接受的边界记录；例如多选牌会在集合变化时记录，最终确认/取消可以结合紧随其后的界面状态判断。无法稳定映射的低层输入不会被默认模式伪装成游戏动作，调试时可切换 `raw_input` 模式获取原始输入。

## 事件格式

示例：

    {\"type\":\"action_observed\",\"event_seq\":17,\"action\":{\"id\":\"PLAY:card=2\",\"kind\":\"play_card\",\"card_id\":\"Strike_R\",\"card_uuid\":\"...\"}}

所有事件都包含 schema_version、mod_version、capture_mode、event_seq、timestamp_ms、type，以及可用时的 run_id。每局文件名包含 run ID、角色、进阶和种子。TCP 接口是可选的；本地 JSONL 始终是主记录。

## 后续实现边界

新增语义事件时，应在“玩家选择被确认”的方法处发送附加事件；原始输入不得被语义过滤。无法可靠映射时应发送 unresolved_input，不能静默丢弃原始输入。
