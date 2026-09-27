# StS Action Recorder

ActionRecorder 是一个独立的 Slay the Spire 1 Mod，用于记录人类在游戏内产生的完整原始输入流，并通过本地 TCP JSONL 通道提供给任意外部消费者。

它与 CommunicationMod 分工不同：

- CommunicationMod 可以负责把游戏状态提供给外部程序；
- ActionRecorder 负责从游戏内部捕获原始输入和可识别的语义事件；
- 外部消费者可以把状态、动作和动作后的状态合并为训练轨迹。

## 当前版本

第一版基础设施已经包含：

- ModTheSpire/BaseMod 可加载入口；
- 本地 TCP 客户端，默认连接 127.0.0.1:8766；
- 按每局保存独立 JSONL 文件；
- 通过 BaseMod 存档字段保存 run identity，退出后重进继续追加原文件；
- 键盘按下/释放/输入、鼠标移动/按下/释放/拖拽、滚轮等原始输入事件；
- 手柄按钮按下/释放原始输入事件；
- run_started、run_ended、room_changed 生命周期事件；
- 可识别的地图节点、卡牌奖励、商店、出牌等附加语义事件；
- 事件序号、时间戳和 Mod 版本字段；
- 断线重连和 stderr 诊断。
- 即使 TCP 接收端未启动，也会追加写入游戏目录下的 data/actionrecorder-events.jsonl。
- 文件写入和 TCP 发送均在后台线程执行，不阻塞游戏更新线程。

原始输入层不依赖角色，也不依赖界面类型；所有角色使用同一套代码。语义事件只作为附加信息，缺少语义 patch 时仍保留原始输入。

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

可以通过 ModTheSpire 启动 JVM 参数覆盖，例如 -Dactionrecorder.port=8767。

连接建立后，Java 端发送一行 hello。之后每条消息都是一个 JSON 对象并以换行结束。

## 事件格式

示例：

    {\"type\":\"action_observed\",\"event_seq\":17,\"action\":{\"id\":\"PLAY:card=2\",\"kind\":\"play_card\",\"card_id\":\"Strike_R\",\"card_uuid\":\"...\"}}

所有事件都包含 schema_version、mod_version、event_seq、timestamp_ms、type，以及可用时的 run_id。每局文件名包含 run ID、角色、进阶和种子。TCP 接口是可选的；本地 JSONL 始终是主记录。

## 后续实现边界

新增语义事件时，应在“玩家选择被确认”的方法处发送附加事件；原始输入不得被语义过滤。无法可靠映射时应发送 unresolved_input，不能静默丢弃原始输入。
