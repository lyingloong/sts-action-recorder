# TCP Protocol

ActionRecorder 的 TCP 连接现在只传输**语义动作事务标记**。完整的
`observation_before`/`observation_after` 不由 Mod 自己复制或推断，而由
CommunicationMod 启动的 `sts_agent.recording.communication_bridge` 从稳定
状态流中匹配生成。这样可以避免 Java Mod 的游戏线程和 CommunicationMod
外部进程之间发生状态时序错配。

ActionRecorder exposes an optional, one-way TCP event stream. The recorder runs
inside the game and acts as a TCP client. The recommended consumer is the
CommunicationMod bridge, which listens on the configured address and also owns
the authoritative state stream. A standalone consumer may still read the
markers, but must not treat them as a complete trajectory without matching
CommunicationMod states.

## Transport

- Transport: TCP.
- Direction: ActionRecorder -> consumer.
- Encoding: UTF-8.
- Framing: newline-delimited JSON (NDJSON/JSONL); one JSON object per line.
- Default endpoint: `127.0.0.1:8766`.
- No TLS, authentication, acknowledgement, request, or response protocol is implemented.
- `action_begin` and `action_accepted` form a transaction. The bridge refuses
  to emit a trajectory step if its before-state cannot be matched uniquely.

The consumer should read the stream incrementally. It must not assume that one `recv()` call contains exactly one event; use a buffered line reader and handle partial TCP packets.

## Connection lifecycle

1. The recorder starts its background writer when the Mod is initialized.
2. It attempts to connect when the first queued event is ready.
3. On success, it sends one `hello` object followed by a newline.
4. It sends queued event objects in order.
5. On connection failure or write failure, it closes the socket and retries after `reconnect_interval_ms`.
6. A new connection receives a new `hello` object.

The recorder does not block the game update thread while connecting. It also writes every queued event to the local JSONL file regardless of TCP availability.

### Delivery guarantees

The TCP stream is best effort:

- Events are ordered by `event_seq` within one recorder process.
- Events generated while disconnected are not replayed after reconnect.
- The local JSONL file is the authoritative complete record.
- A consumer that needs exactly-once processing should deduplicate using `recorder_session` and `event_seq`.
- A consumer may receive a `hello` more than once because reconnecting creates a new TCP connection.

## Hello message

Example:

```json
{
  "schema_version": "0.1",
  "mod_version": "0.1.0",
  "recorder_session": "9bd3...",
  "event_seq": 0,
  "timestamp_ms": 1780000000000,
  "type": "hello",
  "host": "127.0.0.1",
  "port": 8766
}
```

`host` and `port` identify the configured consumer endpoint, not the ephemeral local client port.

## Event messages

After `hello`, messages use the common event envelope. The important
transaction messages are:

```json
{
  "schema_version": "0.4",
  "type": "action_begin",
  "recorder_session": "9bd3...",
  "event_seq": 17,
  "transaction_id": "9bd3...:tx-...",
  "run_id": "run-...",
  "expected_screen": "MAP",
  "context": {"in_game": true, "screen": "MAP", "floor": 2}
}
```

```json
{
  "schema_version": "0.4",
  "type": "action_accepted",
  "recorder_session": "9bd3...",
  "event_seq": 18,
  "transaction_id": "9bd3...:tx-...",
  "run_id": "run-...",
  "action": {"id": "MAP:x=1:y=0", "kind": "map_node_selected", "x": 1, "y": 0}
}
```

If the game rejects or cancels a pending semantic choice, the Mod emits
`action_rejected` with the same `transaction_id`. A transaction is never
reconstructed from an unrelated later action.

Envelope fields:

| Field | Meaning |
| --- | --- |
| `schema_version` | Event schema version, currently `0.4`. |
| `mod_version` | ActionRecorder version that emitted the event. |
| `recorder_session` | Identifier for one running game process/recorder instance. |
| `capture_mode` | `game_actions`, `raw_input`, or `off`; absent on `hello`. |
| `event_seq` | Monotonically increasing sequence number for ordinary events. |
| `timestamp_ms` | Wall-clock Unix timestamp in milliseconds. |
| `type` | Envelope event type, such as `run_started` or `action_accepted`. |
| `run_id` | Current game run identifier when a run is active. |
| `payload` | Event-specific fields, such as `action` or `context`. |

There is no schema negotiation. Consumers should ignore fields they do not need and tolerate additional fields in future schema versions. Complete observations and legal actions come from the CommunicationMod bridge, not from this TCP stream.

## Recommended CommunicationMod bridge

Configure CommunicationMod to start the bridge itself, while ActionRecorder
points its marker TCP client at the same port:

```properties
command=<sts-agent-root>/.venv/Scripts/pythonw.exe -m sts_agent.recording.communication_bridge --config <sts-agent-root>/config/agent.json
runAtGameStart=true
```

Replace `<sts-agent-root>` with the absolute path to the local sts-agent clone.

The bridge sends `WAIT` after each stable state because it is passive: the
player performs actions directly in the game. It keeps a bounded state cache,
assigns monotonic `state_seq` values, validates the expected screen and legal
action context, and writes `matched`, `ambiguous`, or `unmatched` decisions to
its `communication-bridge-*.jsonl` diagnostic file. Only matched transactions
are exported as sts-agent trajectories.

## Minimal server examples

### Python

```python
import json
import socket

server = socket.create_server(("127.0.0.1", 8766))
while True:
    connection, address = server.accept()
    with connection:
        with connection.makefile("r", encoding="utf-8", newline="\n") as stream:
            for line in stream:
                message = json.loads(line)
                if message.get("type") == "hello":
                    print("connected:", address, message["recorder_session"])
                else:
                    print(message)
```

### PowerShell diagnostic listener

```powershell
$listener = [System.Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 8766)
$listener.Start()
$client = $listener.AcceptTcpClient()
$reader = [IO.StreamReader]::new($client.GetStream())
while ($line = $reader.ReadLine()) { $line }
```

The PowerShell example is intended for diagnostics, not high-throughput collection.

## Configuration properties

All properties use the `actionrecorder.` prefix:

```text
-Dactionrecorder.host=127.0.0.1
-Dactionrecorder.port=8766
-Dactionrecorder.connect_timeout_ms=250
-Dactionrecorder.reconnect_interval_ms=1000
```

The TCP endpoint is intentionally separate from the local event directory and does not change where the complete trace is stored.
