# 提取通用轨迹数据集

本页说明离线对局筛选和通用轨迹输出。安装与采集配置见 [使用指南](USER_GUIDE.md)，
原始事件见 [事件格式](EVENT_SCHEMA.md)，记录质量检查见 [采集验收](COLLECTION_AUDIT.md)。

提取工具只使用 Python 3.10+ 标准库。使用任意符合版本要求的 Python 即可，
不需要虚拟环境、游戏进程或下游应用环境。

## 使用

在仓库根目录运行（路径按实际情况替换）：

```powershell
python tools/extract_dataset.py --input "C:/path/to/SlayTheSpire/data/actionrecorder" --output datasets/a0-ironclad-v1 --character IRONCLAD --ascension 0 --outcome victory
```

不传过滤选项就是全部保留；默认不限制角色、进阶或胜负。目录输入只读取 `run-*.jsonl`，
也可以 `--input file1.jsonl file2.jsonl` 合并多份来源。同一个 run_id 跨保存/继续仍为一局。

| 参数 | 含义 |
| --- | --- |
| `--character IRONCLAD THE_SILENT` | 多个角色 ID，忽略大小写；支持源记录中的模组角色 ID |
| `--ascension 0 10 20` | 指定进阶集合 |
| `--ascension-range 0 20` | 闭区间；可与指定集合合并 |
| `--outcome victory death incomplete unknown` | 允许的结果集合，不传则全部 |
| `--victory-type normal heart unknown` | 仅提取相应胜利类型，不传则不限制 |
| `--mod-version 0.1.3` | 一局内所有已知 Mod 版本均须在给定集合内 |
| `--require-complete-states` | 排除状态关联有质量标记、排队动作缺执行前状态的整局；不要求后状态 |

终局仅采用 `terminal=true` 的 `run_finished/run_ended`，不以普通 Boss 房间判胜。
胜利且终局 context.act=3 记 normal，act=4 记 heart；缺少幕信息时胜利类型为 unknown。
早期日志若明确在 act<3 标成胜利，结果记 unknown，不纳入 victory 筛选。
不同终局结果冲突也记 unknown。只有保存退出、没有明确终局的记录为 incomplete。
工具无法修复旧记录的错误胜利判定；正式采集建议同时限定已验收的 Mod 版本。

## 输出契约

```text
dataset/
  manifest.json
  events/<run>.jsonl
  trajectories/<run>.jsonl
```

- `manifest.json`：格式 `actionrecorder-dataset-1`，包含筛选配置、源文件路径/哈希、
  提取器哈希、入选/排除对局及原因、元数据、各输出文件 SHA256 和质量标记统计。
- `events`：入选对局的完整事件，不改写字段。按 `(recorder_session,event_seq)` 去重，
  同一个身份但内容冲突时报错，不静默覆盖。单进程按 event_seq 排序；跨进程按各会话
  最早 timestamp_ms 排序，因此跨机器合并时应确保来源时钟可靠。
- `trajectories`：每个动作事务一行，格式 `actionrecorder-step-1`。细粒度入口、选牌、
  确认、取消/返回分别保留；拒绝、未闭合、缺状态的事务也保留。没有 SFT 可用性标签。

每步字段：

| 字段 | 含义 |
| --- | --- |
| `run_id / transaction_id / order` | 对局、动作身份及局内顺序 |
| `status` | accepted / rejected / pending；accepted 不等于队列动作已执行 |
| `before_state_id / observation_before` | 显式引用的提交前完整 CommunicationMod **消息 envelope** |
| `available_actions` | 原始 available_commands 和 choice_list，不扩展成模型合法动作 |
| `chosen_action` | 实际动作对象，保留实体 ID、UUID、目标等所有源字段 |
| `execution_tracking` | 是否记录了队列执行生命周期 |
| `execution_before_state_id / execution_observation_before` | 显式执行前完整消息，不覆盖提交状态 |
| `execution_action / execution_status` | 执行时动作和 executed/skipped/cancelled 结果 |
| `after_state_id / observation_after / after_source` | 仅关联明确的 action_effects_settled 引用 |
| `quality_flags` | 缺失、身份/场景关联异常等诊断；不据此默认删除步骤 |
| `markers` | 本事务所有完整原始标记，保留 context、logical_boundary、拒绝原因等 |

字段缺失为 null，不把未知命令/选择写成已确认空列表。无明确后状态引用时后状态为 null，
不会把“下一行状态”猜成结果。下游可以另外按自己的就绪和效果规则分析事件流。
未知/新增源字段完整保留，不做字符截断、静态知识注入或动作合并。

同一输出目录不可重复写入，避免误覆盖或混入旧数据。JSON 损坏、事件身份冲突会报错，
原始输入始终只读。没有选中对局仍输出空 manifest，便于检查筛选条件。

## 下游使用

下游接收整个目录，可以直接使用 `trajectories`，也可以基于保留的 `events` 重建应用特定
历史/动作关联。角色、进阶、胜负等对局筛选由本工具提供；取消分支是否训练、
哪些历史可见、静态知识如何注入、提示词和动作如何编码、token 长度如何处理，
均由下游应用决定。对接方的启动与训练命令应维护在其自身文档中。
