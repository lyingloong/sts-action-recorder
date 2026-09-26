# StS Action Recorder

ActionRecorder 是一个独立的 Slay the Spire 1 Mod，用于记录人类在游戏内做出的高层决策动作，并通过本地 TCP JSONL 通道发送给 sts-agent。

它与 CommunicationMod 分工不同：

- CommunicationMod 负责把游戏状态提供给 Python controller；
- ActionRecorder 负责从游戏内部捕获玩家决策入口；
- Python controller 负责把状态、动作和动作后的状态合并为 SFT 轨迹。

## 当前版本

第一版基础设施已经包含：

- ModTheSpire/BaseMod 可加载入口；
- 本地 TCP 客户端，默认连接 127.0.0.1:8766；
- run_started、run_ended、room_changed 生命周期事件；
- 从 GameActionManager.cardsPlayedThisTurn 读取已确认的出牌事件；
- 回合编号变化事件；
- 事件序号、时间戳和 Mod 版本字段；
- 断线重连和 stderr 诊断。

这不是最终的“所有界面动作”实现。地图、事件、奖励、商店、营火和多选界面需要在对应的 ModTheSpire patch 增加专门的事件发送，不能靠底层 GameAction 推断。

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

可以通过 ModTheSpire 启动 JVM 参数覆盖，例如 -Dactionrecorder.port=8767。

连接建立后，Java 端发送一行 hello。之后每条消息都是一个 JSON 对象并以换行结束。

## 事件格式

示例：

    {\"type\":\"action_observed\",\"event_seq\":17,\"action\":{\"id\":\"PLAY:card=2\",\"kind\":\"play_card\",\"card_id\":\"Strike_R\",\"card_uuid\":\"...\"}}

所有事件都包含 schema_version、mod_version、event_seq、timestamp_ms 和 type。动作事件目前只表示已从游戏内部观察到的高层结果，不包含完整的 pre_state / post_state。

## 后续实现边界

新增界面动作时，应在“玩家选择被确认”的方法处发送事件，而不是在伤害、抽牌、获得格挡等底层效果处发送事件。每个动作都要能映射到 sts-agent 的规范动作 ID；无法可靠映射时应发送 unresolved_input，不能静默生成训练标签。
