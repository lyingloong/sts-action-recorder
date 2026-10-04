# StS Action Recorder

用于 Slay the Spire 1 的玩家动作记录 Mod。正常在游戏内游玩即可收集细粒度动作，
并通过每局 JSONL 文件和可选的 TCP 流提供数据。推荐与
[CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod) 一起使用，记录完整游戏状态。

当前 Mod 版本 **0.1.1**，事件协议 **0.5**。Java 8、ModTheSpire 和 BaseMod 为运行依赖；
离线工具使用 Python 3.10+ 标准库。

## 快速开始

1. 将 `action-recorder.jar` 放入游戏的 `mods/` 目录。
2. 在 ModTheSpire 中启用 **StS Action Recorder**、BaseMod 和推荐的 CommunicationMod。
3. 启动游戏并正常游玩；默认 `game_actions` 模式记录游戏动作。
4. 在游戏工作目录的 `data/actionrecorder/` 查看记录。

每局游戏一个文件；保存退出再继续会追加到原文件。TCP 接收端未启动时照常本地保存。
首次采集建议打开 Mod 设置中的动作提示，并按 [验收清单](docs/COLLECTION_AUDIT.md) 检查记录。
完整安装与配置见 [使用指南](docs/USER_GUIDE.md)；自行构建见 [开发指南](docs/DEVELOPMENT.md)。

## 记录内容

- 战斗：出牌、结束回合、使用/丢弃药水、战斗内选牌和确认。
- 路线与房间：地图选点、涅奥/事件选项、小游戏、商店购买/删牌、营火、宝箱。
- 奖励与导航：金币、遗物、药水、卡牌、钥匙、Boss 遗物，以及跳过、返回、前进、离开。
- 状态：CommunicationMod 完整消息，补充三色钥匙、卡牌运行时数值和选牌上下文。
- 关联：明确的对局、动作事务、状态 ID；排队的出牌/结束回合分别记录提交与实际执行。

锻造和删牌按入口、选牌、确认分别保存，取消和返回也保留。动作列表和字段定义集中在
[事件格式](docs/EVENT_SCHEMA.md)，状态明细见 [状态契约](docs/STATE_SCHEMA.md)。

## 离线提取数据集

在本仓库根目录运行；任意 Python 3.10+ 环境均可：

```powershell
python tools/extract_dataset.py `
  --input "C:/path/to/SlayTheSpire/data/actionrecorder" `
  --output datasets/a0-ironclad-v1 `
  --character IRONCLAD --ascension 0 --outcome victory
```

提取工具按对局筛选，输出完整事件、细粒度轨迹和 manifest。所选对局中的取消、拒绝和
缺状态事务均保留并标记，供下游选择使用。过滤选项与输出格式见
[数据集提取](docs/DATASET_EXTRACTION.md)。

## TCP 接入

ActionRecorder 是 TCP 客户端，默认连接 `127.0.0.1:8766`。接收端按 UTF-8 JSONL
逐行读取。该接口单向发送事件，断线不补发历史；完整记录以本地文件为准。
接收端示例、握手和重连语义见 [TCP 接口](docs/TCP_PROTOCOL.md)。

## 文档导航

| 需求 | 文档 |
| --- | --- |
| 安装、配置、文件位置、排查 | [使用指南](docs/USER_GUIDE.md) |
| 检查一局记录是否完整 | [采集验收](docs/COLLECTION_AUDIT.md) |
| 筛选与导出通用轨迹 | [数据集提取](docs/DATASET_EXTRACTION.md) |
| 解析事件和细粒度动作 | [事件格式](docs/EVENT_SCHEMA.md) |
| 解析状态、字段和公开信息边界 | [状态契约](docs/STATE_SCHEMA.md) / [JSON Schema](docs/schema/state.schema.json) |
| 实时接收事件 | [TCP 接口](docs/TCP_PROTOCOL.md) |
| 构建、测试、扩展动作 | [开发指南](docs/DEVELOPMENT.md) |

数据集使用与原始格式由本仓库定义。提示词、静态知识注入、模型动作编码和训练样本筛选
由下游应用实现。

## 许可证

本仓库代码采用 [MIT License](LICENSE)。游戏与第三方组件遵循各自许可证。
