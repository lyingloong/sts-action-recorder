# StS Action Recorder

一个独立的 **Slay the Spire 1** Mod，用于记录游戏内产生的玩家游戏动作，并通过本地 JSONL 文件和可选的 TCP 流提供给外部程序。

ActionRecorder 是独立的游戏轨迹记录 Mod，负责保存原始语义动作和动作前后的状态快照。推荐同时启用 CommunicationMod，以提供完整的游戏状态序列化能力。所有下游程序必须遵循 [canonical state schema](docs/STATE_SCHEMA.md)，不能各自重新定义动作前状态。当前 `available_actions` 仍是 CommunicationMod 的命令和选项线索；需要完整展开的合法动作集合时，消费者应新增明确的派生字段而不是改变该字段语义。

## 设计定位

ActionRecorder 和 CommunicationMod 的职责不同：

- **ActionRecorder**：从游戏内部捕获已经被游戏接受的语义动作，例如出牌、地图选点、事件选择、商店购买和结束回合。
- **CommunicationMod**：向外部程序提供当前游戏状态，并接收外部控制命令。
- **外部消费者**：按 `step_id` 将动作前状态、动作、动作后状态组合成训练轨迹。

默认模式记录游戏语义动作，不记录鼠标坐标和键盘按键，因此适合人类游玩轨迹收集。调试时可以切换到 `raw_input`，同时保留低层输入事件。

## 功能特性

- 支持 Slay the Spire 1、Java 8、ModTheSpire 和 BaseMod。
- 每局游戏单独保存 JSONL 文件。
- 保存、退出并重新进入同一局时，继续追加到原来的 run 文件。
- 文件记录包含角色、进阶、种子、run ID、事件序号和时间戳。
- 游戏更新线程只负责生成事件；磁盘写入和 TCP 发送由后台线程完成，不阻塞游戏操作。
- TCP 接收端不可用时仍然持续保存本地文件。
- TCP 连接断开后自动重连。
- 默认覆盖 sts-agent 常用的游戏动作空间。
- 同进程启用 CommunicationMod 时记录动作前游戏状态、可用命令和界面选项，并在界面稳定后记录关联的动作后状态；状态采集在游戏进程内完成。
- 状态快照会补充三色心脏钥匙：`game_state.keys.ruby`（红）、`emerald`（绿）、`sapphire`（蓝）；这是从游戏 `Settings` 读取的，因为当前 CommunicationMod 转换器没有导出这三个字段。
- 当 CommunicationMod 同时启用时，ActionRecorder 还会对其 `getCommunicationState()` 做可选的 ModTheSpire 后置补丁，因此通过 CommunicationMod TCP 端口读取的状态也包含同样的 `keys` 字段。
- 支持可选的键盘、鼠标和手柄原始输入采集。

状态字段、可见性规则、动作前后关联和版本策略见 [docs/STATE_SCHEMA.md](docs/STATE_SCHEMA.md)。机器可读的状态草案见 [docs/schema/state.schema.json](docs/schema/state.schema.json)。

## 动作覆盖

`game_actions` 模式主要记录以下语义动作：

| 场景 | 事件 `kind` | 主要内容 |
| --- | --- | --- |
| 战斗 | `play_card` | 卡牌 ID、UUID、手牌位置、能量参数、目标敌人 |
| 战斗 | `potion_use_requested`、`potion_discarded` | 药水槽位、药水 ID；目标型药水另有目标事件 |
| 战斗 | `end_turn` | 结束回合 |
| 地图 | `map_node_selected` | 地图节点坐标 |
| Neow | `neow_option_selected` | 开局选项下标 |
| 事件 | `event_option_selected` | 事件选项下标和事件类型 |
| 营火 | `campfire_action` | 休息、锻造、举重、吃药、挖掘、回忆 |
| 宝箱 | `chest_opened` | 宝箱类型、是否首领宝箱、奖励属性 |
| 战斗奖励 | `reward_*_claimed` | 金币、遗物、药水、卡牌和钥匙奖励 |
| 卡牌奖励 | `card_reward_opened`、`card_reward_selected`、`card_reward_skipped` | 打开奖励、选牌、跳过 |
| Boss 奖励 | `boss_relic_selected`、`boss_relic_skipped` | Boss 遗物选择或跳过 |
| 商店 | `shop_opened`、`shop_*_purchased`、`shop_card_purged` | 进入商店、购买、删牌 |
| 界面 | `continue_button`、`return_button`、`leave_button` | 战斗奖励/营火等界面的前进、锻造选牌返回、离开 |
| 多选牌 | `card_selection_changed` | 当前选择集合及升级、转化、删除等用途标记 |

语义动作在游戏接受动作的方法边界记录，而不是根据鼠标位置推断。玩家出牌和结束回合从各自的玩家入口采集，不把卡牌自动入队误记为人类操作。多选牌界面会在选择集合发生变化时记录；最终确认或取消可结合后续状态事件判断。

## 安装

1. 确保已经安装 Slay the Spire、ModTheSpire 和 BaseMod。
2. 将构建出的 `target/action-recorder.jar` 放入游戏的 `mods` 目录。
3. 在 ModTheSpire 启动器中启用 `StS Action Recorder`。要采集状态，推荐同时启用 CommunicationMod。若 CommunicationMod 的 `runAtGameStart=true` 且 `command` 指向自动控制器，请先关闭自动启动，以免干扰人工游玩；这不影响 ActionRecorder 在游戏进程内读取状态。

构建时 Maven 会自动复制 jar 到默认 Steam 安装目录下的 `mods` 目录。也可以手动复制，避免覆盖正在运行的游戏实例。

## 构建

开发环境要求：

- Java 8；
- Maven 3.x；
- 本机可访问 Slay the Spire、BaseMod 和 ModTheSpire 的 jar 文件。

默认路径是 `D:/Steam/steamapps`。在 Windows PowerShell 中：

```powershell
$env:JAVA_HOME = "D:\UserPrograms\Java\corretto-1.8.0_462"
& "D:\UserPrograms\maven\apache-maven-3.9.16\bin\mvn.cmd" clean package
```

如果 Steam 路径不同：

```powershell
mvn clean package -DSteam.path=D:/Steam/steamapps
```

构建产物为 `target/action-recorder.jar`。项目当前针对本机使用的 StS1 版本和 ModTheSpire 版本编译，换版本后应重新检查补丁方法签名。

## 配置

配置通过 JVM 系统属性传入，属性前缀为 `actionrecorder.`：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `host` | `127.0.0.1` | TCP 接收端地址 |
| `port` | `8766` | TCP 接收端端口 |
| `connect_timeout_ms` | `250` | 单次 TCP 连接超时 |
| `reconnect_interval_ms` | `1000` | TCP 连接失败后的重试间隔 |
| `events_dir` | `data/actionrecorder` | 本地 JSONL 目录，相对路径相对于游戏工作目录 |
| `capture_mode` | `game_actions` | 采集模式，见下表 |

采集模式：

- `game_actions`：只记录语义游戏动作，推荐用于训练数据。
- `raw_input`：记录语义动作，并额外记录键盘、鼠标、滚轮和手柄输入。
- `off`：关闭记录。

例如，使用 ModTheSpire 的 JVM 参数设置原始输入模式：

```text
-Dactionrecorder.capture_mode=raw_input
```

代码中的系统属性名称实际为 `actionrecorder.host`、`actionrecorder.port` 等；上表省略了公共前缀以便阅读。

## 本地文件

本地 JSONL 是主记录，TCP 只是实时副本。默认目录为：

```text
<SlayTheSpire 工作目录>/data/actionrecorder/
```

一局游戏的文件名类似：

```text
run-<run-id>-IRONCLAD-A0-seed-<seed>.jsonl
```

每行是一个完整 JSON 对象，使用 UTF-8 编码。游戏尚未建立 run identity 时，可能先写入 `session-<recorder-session>.jsonl`；进入游戏后会切换到对应的 run 文件。

## TCP 接口

完整协议说明见 [docs/TCP_PROTOCOL.md](docs/TCP_PROTOCOL.md)。这里给出最小使用说明：

- ActionRecorder 是 **TCP 客户端**，外部程序需要先监听 `host:port`。
- 连接方向是“游戏进程 -> 外部消费者”；当前协议没有反向命令通道。
- 连接建立后先发送一行 `hello` JSON。
- 随后每个事件占一行，使用 UTF-8 和 `\\n` 分隔。
- 接收端应按行读取并逐行解析 JSON，不要等待整个连接关闭。
- ActionRecorder 不要求 ACK；消费端只需要持续读取即可。
- 连接失败、断开或接收端未启动不会影响本地文件写入。
- 断线期间产生的事件不会在重连后通过 TCP 补发；需要完整数据时读取本地 JSONL。
- 每次重新建立 TCP 连接都会重新发送一条 `hello`，`recorder_session` 不变，`event_seq` 为 `0` 的消息表示 hello。

最小 Python 接收端示例：

```python
import json
import socket

with socket.create_server(("127.0.0.1", 8766)) as server:
    while True:
        connection, address = server.accept()
        with connection:
            with connection.makefile("r", encoding="utf-8", newline="\n") as stream:
                for line in stream:
                    event = json.loads(line)
                    print(address, event["type"], event.get("event_seq"))
```

TCP 接口只负责传输 ActionRecorder 的事件，不提供游戏控制命令。启用 CommunicationMod 后，事件中已包含采集到的状态；需要控制游戏时可另外使用 CommunicationMod 的控制接口。

## 事件格式

每个普通事件至少包含：

```json
{
  "schema_version": "0.1",
  "mod_version": "0.1.0",
  "recorder_session": "...",
  "capture_mode": "game_actions",
  "event_seq": 17,
  "timestamp_ms": 1780000000000,
  "type": "action_observed",
  "run_id": "...",
  "step_id": "...:12",
  "observation_before": {"floor": 2, "combat_state": {}},
  "available_actions": {"commands": ["play", "end"], "choices": []},
  "action": {
    "id": "PLAY:card=2:target=0",
    "kind": "play_card",
    "card_id": "Strike_R",
    "card_uuid": "...",
    "hand_index": 1,
    "target_index": 0
  }
}
```

生命周期和决策事件包括：`hello`、`run_started`、`room_changed`、`combat_turn_changed`、`action_observed`、`step_resolved`、`raw_input` 和 `run_ended`。`step_resolved` 用相同 `step_id` 补全动作后状态；`available_actions` 是 CommunicationMod 提供的命令/选项线索，并非展开到卡牌目标的完整合法动作列表。字段定义和异常状态见 [docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md)。

## 性能与可靠性

游戏线程采集当前状态并把构造好的 JSON 放入内存队列，不做磁盘或网络 I/O。状态转换自身仍占用游戏线程时间，但只在动作边界和结算检查时执行，不会每帧调用。后台线程按“本地文件写入 -> 尝试 TCP 发送”的顺序处理事件，因此：

- 外部 TCP 服务变慢不会直接阻塞游戏更新线程；
- 本地文件是训练数据的可靠来源；
- TCP 适合实时消费，不应作为唯一存储；
- 进程异常终止时，后台队列中尚未落盘的少量事件仍可能丢失。

## 开发与扩展

新增语义动作时，应优先寻找游戏中“动作已被接受”的方法边界，例如奖励领取、卡牌入队或营火选项的 `useOption`。不要仅通过鼠标点击位置猜测动作。

扩展前请阅读 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。如果某类动作无法可靠映射，应记录一个明确的未解析事件或在文档中说明边界，不能静默丢弃必要的训练信息。

## 相关项目

- [Slay the Spire](https://store.steampowered.com/app/646570/Slay_the_Spire/)
- [ModTheSpire](https://github.com/kiooeht/ModTheSpire)
- [BaseMod](https://github.com/daviscook477/BaseMod)

本仓库专注于 ActionRecorder Mod、事件协议和状态规范；`sts-agent` 等外部程序按这些规范消费记录数据。

## 许可证

本仓库目前尚未声明独立许可证。使用、再分发或发布修改版前，请确认 Slay the Spire、ModTheSpire、BaseMod 及本项目代码各自适用的许可和分发要求。
