# TCP Protocol

TCP 只传输 ActionRecorder 事件；事件中的状态对象必须遵循
[STATE_SCHEMA.md](STATE_SCHEMA.md)。TCP 不定义另一套状态格式。

ActionRecorder exposes an optional, one-way TCP event stream. The recorder runs inside the game and acts as a TCP client. A consumer must run a TCP server and listen on the configured address.

## Transport

- Transport: TCP.
- Direction: ActionRecorder -> consumer.
- Encoding: UTF-8.
- Framing: newline-delimited JSON (NDJSON/JSONL); one JSON object per line.
- Default endpoint: `127.0.0.1:8766`.
- No TLS, authentication, acknowledgement, request, or response protocol is implemented.

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

After `hello`, messages use the common event envelope:

```json
{
  "schema_version": "0.1",
  "mod_version": "0.1.0",
  "recorder_session": "9bd3...",
  "capture_mode": "game_actions",
  "event_seq": 17,
  "timestamp_ms": 1780000000500,
  "type": "action_observed",
  "run_id": "2c0f...",
  "step_id": "2c0f...:12",
  "observation_before": {"floor": 2},
  "available_actions": {"commands": ["play", "end"], "choices": []},
  "action": {
    "id": "END_TURN",
    "kind": "end_turn"
  },
  "chosen_action": {
    "id": "END_TURN",
    "kind": "end_turn"
  }
}
```

Envelope fields:

| Field | Meaning |
| --- | --- |
| `schema_version` | Event schema version, currently `0.1`. |
| `mod_version` | ActionRecorder version that emitted the event. |
| `recorder_session` | Identifier for one running game process/recorder instance. |
| `capture_mode` | `game_actions`, `raw_input`, or `off`; absent on `hello`. |
| `event_seq` | Monotonically increasing sequence number for ordinary events. |
| `timestamp_ms` | Wall-clock Unix timestamp in milliseconds. |
| `type` | Envelope event type, such as `run_started` or `action_observed`. |
| `run_id` | Current game run identifier when a run is active. |
| `payload` | Event-specific fields, such as `action` or `context`. |

There is no schema negotiation. Consumers should ignore fields they do not need and tolerate additional fields in future schema versions.
`step_resolved` messages have the same `step_id` as their `action_observed` message and supply `observation_after` plus a `resolution` reason. See [EVENT_SCHEMA.md](EVENT_SCHEMA.md) for null-state handling and the limits of `available_actions`.

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
