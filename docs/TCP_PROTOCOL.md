# TCP 接口（事件协议 0.5）

ActionRecorder 是单向 TCP 客户端，默认连接接收端 `127.0.0.1:8766`。
配置方法见 [使用指南](USER_GUIDE.md#采集与输出配置)，事件字段见 [事件格式](EVENT_SCHEMA.md)。

## 连接与分帧

接收程序先启动监听，游戏再连接。数据采用 UTF-8，每行一个 JSON 对象，以换行分帧。
一次 `recv()` 可能只有半行，也可能包含多行，应持续按行读取。

这是 ActionRecorder 的事件流；CommunicationMod 的外部脚本 stdin/stdout 是另一条接口。
本通道没有反向游戏命令、ACK 或历史查询。

每次连接成功，首先发送 `hello`：

```json
{"schema_version":"0.5","mod_version":"0.1.4","recorder_session":"session-uuid","event_seq":0,"timestamp_ms":1780000000000,"type":"hello","host":"127.0.0.1","port":8766}
```

`hello` 是连接握手，不是新对局，也不写入本地事件日志。随后发送完整状态与动作事件，
格式与本地 JSONL 一致。接收端应接受未知附加字段，处理所需的已知事件类型。

## 最小接收端

Python 3.10+ 标准库即可运行：

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

该示例适合检查连接。持久消费端还应保存接收事件、检测序号缺口，并在断线后继续 accept。

## 顺序、身份与状态关联

- 普通事件按 `(recorder_session, event_seq)` 去重；序号在同一游戏进程内递增。
- 重连仍使用同一 session，`hello.event_seq=0` 不能参与普通事件的去重和顺序检查。
- 保存退出再继续依靠 `run_id` 判断是否同一局，不能依靠 TCP 连接身份。
- `action_begin.before_state_id` 精确引用提交前状态；执行前和结算后的引用见
  [动作事务](EVENT_SCHEMA.md#动作事务)。
- 状态 ID 同时出现在 `state_published` 和 CommunicationMod 发出的同一消息中。
  两条通道延迟不同，消费者应等待引用的 ID，缺失时保留为未知。
- `action_accepted` 只证明提交被接受；带 `execution_tracking=true` 的动作要另外
  检查执行事件，才能判断是否实际执行。

## 重连与可靠性

连接失败后按照 `reconnect_interval_ms` 限制下一次尝试；重连会再次发送 hello。
离线事件不会通过 TCP 补发。若网络写入失败，当前消息也不会要求接收端确认或重发。

本地写盘和 TCP 发送使用不同线程。事件先尝试写盘，再加入 TCP 队列；TCP 队列满或
发送阻塞不会直接阻塞本地写盘。磁盘错误应检查游戏日志。

完整轨迹从本地 JSONL 恢复；实时流可能有缺口。当前接口没有认证和 TLS，默认用于
本机接收；跨机器部署需要由接入方管理网络访问。
