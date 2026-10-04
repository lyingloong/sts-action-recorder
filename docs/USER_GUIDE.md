# 使用指南

本页说明安装、采集设置、本地记录和常见问题。数据字段见 [事件格式](EVENT_SCHEMA.md)
和 [状态契约](STATE_SCHEMA.md)；离线筛选见 [数据集提取](DATASET_EXTRACTION.md)。

## 安装与首次采集

1. 安装 Slay the Spire 1、ModTheSpire 和 BaseMod。
2. 将 `action-recorder.jar` 放入游戏工作目录的 `mods/`，在 ModTheSpire 启动器中
   勾选 **StS Action Recorder**。自行构建的方法见 [开发指南](DEVELOPMENT.md#构建)。
3. 推荐同时启用 [CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod)，
   以记录完整游戏状态。其外部控制脚本设置遵循 CommunicationMod 或接收应用的文档。
4. 启动游戏，在游戏内正常游玩。默认采集模式为 `game_actions`。
5. 检查游戏工作目录下 `data/actionrecorder/` 的 JSONL 文件。首次使用或更新 Mod 后，
   按 [采集验收清单](COLLECTION_AUDIT.md) 检查一局实际记录。

本地保存始终开启。TCP 接收端是否启动不影响本地写盘；完整状态是否可用取决于
CommunicationMod 发布接口。发布不可用时仍记录动作，状态引用为 `null`。

## 游戏内设置

在 Mod 设置页面打开 **StS Action Recorder**：

| 开关 | 默认 | 用途 |
| --- | --- | --- |
| `Show Action Record Toasts` | 关闭 | 每个已接受动作显示短暂的淡入淡出提示，包含 kind 和 ID |
| `Show Current Available Actions` | 关闭 | 显示当前 CommunicationMod commands/choices，方便排查动作覆盖 |

两个开关由 `SpireConfig` 保存，仅改变显示。动作提示框中的 commands 包含源接口的
调试/控制命令；它们不是模型的完整合法动作。显示行数受界面空间限制，JSONL 保存完整数据。

## 采集与输出配置

通过启动游戏进程时的 JVM 参数设置；下表列出完整属性名。修改后重启游戏。

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `actionrecorder.capture_mode` | `game_actions` | 采集模式 |
| `actionrecorder.events_dir` | `data/actionrecorder` | JSONL 目录；相对路径以游戏工作目录为基准 |
| `actionrecorder.host` | `127.0.0.1` | TCP 接收端地址 |
| `actionrecorder.port` | `8766` | TCP 接收端端口 |
| `actionrecorder.connect_timeout_ms` | `250` | 单次连接超时，毫秒 |
| `actionrecorder.reconnect_interval_ms` | `1000` | 下一次连接尝试的最小间隔，毫秒 |

采集模式：

- `game_actions`：记录细粒度游戏动作、状态和生命周期，适合常规轨迹采集。
- `raw_input`：在上述记录之外增加键鼠、滚轮和手柄输入，适合调试。
- `off`：关闭采集。

例如将以下参数传给游戏 JVM：

```text
-Dactionrecorder.capture_mode=raw_input
-Dactionrecorder.events_dir=C:/path/to/recordings
```

游戏内开关控制显示；JVM 参数控制采集与输出；离线提取参数控制筛选对局，三者独立。
记录时保存完整轨迹，角色、进阶、胜负筛选在 [离线提取](DATASET_EXTRACTION.md) 时设置。

## 本地文件

```text
<游戏工作目录>/data/actionrecorder/
  run-<run-id>-<character>-A<ascension>-seed-<seed>.jsonl
  session-<recorder-session>.jsonl
```

每行是一个 UTF-8 JSON 对象。一局使用一个 run 文件；保存退出后继续该局，恢复同一个
run ID 并追加到原文件。尚未建立对局身份的事件保存到 session 文件。
同一游戏进程开新局会产生新的 run 文件。跨幕仍为同一局，击败普通幕 Boss 不表示整局胜利。

文件名和 `run_started.seed` 使用游戏内部有符号 long 种子，可能为负数；它与游戏中显示的
字母数字编码不同。种子只在对局元数据保存，状态不重复保存。

一局包含多个事件，不是每行一个玩家动作。状态、动作提交、接受/拒绝及执行结果通过
明确 ID 关联，定义见 [事件格式](EVENT_SCHEMA.md)。

## 常见问题

### 没有记录文件

检查游戏实际工作目录、`actionrecorder.events_dir` 和目录写权限。确认启用了 Mod，
`capture_mode` 不是 `off`。日志中的 `local event file write failed` 表示写盘失败。

### 有动作，但没有完整状态

检查 CommunicationMod 是否启用、版本是否匹配，游戏日志是否出现
`state publisher unavailable`。对 JSONL 执行 [审计工具](COLLECTION_AUDIT.md#原始日志审计)，
核查是否存在 `state_published`，以及动作引用是否能匹配。

### 没有 TCP 连接

ActionRecorder 是客户端，接收端需要监听配置中的地址和端口。没有接收端时可以继续
本地采集。实时接入的方向、重连行为和示例见 [TCP 接口](TCP_PROTOCOL.md)。

### 某个动作没记录，或重复记录

打开动作提示和可用动作提示框，记下实际操作顺序、当前界面及 Mod 版本，保存对应日志。
检查原始事务后再判断是入口漏采还是消费端解析问题。自定义 Mod 的 UI 和动作入口需要
单独验收；`raw_input` 可帮助定位，但不会自动补齐缺失的语义动作。

### 游玩时有短暂卡顿

磁盘和 TCP 输出在后台线程执行，状态转换仍占用游戏线程时间。记录较大状态时可能
增加动作边界的开销。排查时提供游戏/模组版本、日志及触发操作，避免仅按文件大小判断。

### 启动出现补丁异常

检查加载的 jar 是否为预期版本、是否重复放置，确认游戏和依赖版本。补丁参数绑定、
插桩检查及构建排查见 [开发指南](DEVELOPMENT.md#补丁兼容性与常见错误)。
