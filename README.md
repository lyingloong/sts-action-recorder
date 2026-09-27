# StS Action Recorder

ActionRecorder 是一个独立的 Slay the Spire 1 Mod，用于记录人类在游戏内做出的高层决策动作，并通过本地 TCP JSONL 通道提供给任意外部消费者。

它与 CommunicationMod 分工不同：

- CommunicationMod 可以负责把游戏状态提供给外部程序；
- ActionRecorder 负责从游戏内部捕获玩家决策入口；
- 外部消费者可以把状态、动作和动作后的状态合并为训练轨迹。

## 当前版本

第一版基础设施已经包含：

- ModTheSpire/BaseMod 可加载入口；
- 本地 TCP 客户端，默认连接 127.0.0.1:8766；
- run_started、run_ended、room_changed 生命周期事件；
- 从 GameActionManager.cardsPlayedThisTurn 读取已确认的出牌事件；
- 回合编号变化事件；
- 地图节点选择、卡牌奖励选牌/跳过、商店购买卡牌的显式决策 patch；
- 事件序号、时间戳和 Mod 版本字段；
- 断线重连和 stderr 诊断。
- 即使 TCP 接收端未启动，也会追加写入游戏目录下的 data/actionrecorder-events.jsonl。
- 文件写入和 TCP 发送均在后台线程执行，不阻塞游戏更新线程。

这不是最终的“所有界面动作”实现。事件选项、药水、遗物奖励、营火和复杂多选界面仍需要在对应的 ModTheSpire patch 增加专门的事件发送，不能靠底层 GameAction 推断。

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
    actionrecorder.events_file=data/actionrecorder-events.jsonl

可以通过 ModTheSpire 启动 JVM 参数覆盖，例如 -Dactionrecorder.port=8767。

连接建立后，Java 端发送一行 hello。之后每条消息都是一个 JSON 对象并以换行结束。

## 事件格式

示例：

    {\"type\":\"action_observed\",\"event_seq\":17,\"action\":{\"id\":\"PLAY:card=2\",\"kind\":\"play_card\",\"card_id\":\"Strike_R\",\"card_uuid\":\"...\"}}

所有事件都包含 schema_version、mod_version、event_seq、timestamp_ms 和 type。动作事件目前只表示已从游戏内部观察到的高层结果，不包含完整的 pre_state / post_state。TCP 接口是可选的；本地 JSONL 始终是主记录。

## 后续实现边界

新增界面动作时，应在“玩家选择被确认”的方法处发送事件，而不是在伤害、抽牌、获得格挡等底层效果处发送事件。每个动作都要能映射到本项目定义的稳定语义动作 ID；无法可靠映射时应发送 unresolved_input，不能静默生成训练标签。
