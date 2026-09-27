# Development Guide

## Repository layout

```text
src/main/java/actionrecorder/
  ActionRecorder.java                 Mod entry point and BaseMod subscription
  runtime/ActionRecorderRuntime.java  Run identity, queue, JSONL and TCP output
  patches/DecisionPatches.java        Semantic game-action patches
  patches/RawInputPatches.java        Optional keyboard/mouse input patches
  patches/ControllerInputPatches.java Optional controller input patches
src/main/resources/ModTheSpire.json   Mod metadata
docs/                                 Protocol and schema documentation
```

The repository deliberately does not contain the Python agent, state adapter or model code.

## Build and install

```powershell
$env:JAVA_HOME = "D:\UserPrograms\Java\corretto-1.8.0_462"
& "D:\UserPrograms\maven\apache-maven-3.9.16\bin\mvn.cmd" clean package
```

The Maven build uses system-scoped jars from the Steam installation and copies the resulting jar into the game's `mods` directory. Override `Steam.path` when needed:

```powershell
mvn clean package -DSteam.path=D:/Steam/steamapps
```

There are currently no automated unit tests because the patches depend on the game runtime. Every change should at least pass `mvn clean package` and be exercised in a short game session.

## Adding a semantic action

1. Inspect the installed StS jar with `javap` and identify the method where the game accepts the decision.
2. Add a small ModTheSpire patch in `DecisionPatches` or a dedicated patch class.
3. Call `ActionRecorderRuntime.getInstance().recordAction(...)` after the action succeeds. Use a stable, machine-readable `id` and a descriptive `kind`.
4. Put entity IDs, indices and targets in structured JSON fields. Do not encode information only in localized display text.
5. Avoid disk and socket I/O in patches. The runtime queues events and performs I/O on its writer thread.
6. Update `docs/EVENT_SCHEMA.md` and the action table in `README.md`.
7. Build the jar and test both with and without a TCP consumer.

Prefer semantic boundaries over low-level input hooks. For example, patch card queue insertion for playing a card, reward claiming for collecting a reward, and campfire option `useOption` for a campfire action. A semantic patch should not fire for hover, rendering, or an unaccepted click.

## Run identity and files

The runtime stores a small BaseMod save field containing the run ID and fingerprint. The fingerprint includes character, ascension and seed. On reopening the same save, the recorder restores the identity and appends to the same JSONL file. A changed fingerprint starts a new run file.

Do not use the TCP connection as the source of truth. The writer always appends to the local file first. Consumers can use the local file to recover from a crashed or unavailable receiver.

## Compatibility notes

Patches are compiled against a specific StS1 desktop jar and ModTheSpire/BaseMod versions. Game updates or workshop updates can rename methods and change signatures. After any dependency update:

- run a clean build;
- inspect ModTheSpire startup logs for patch failures;
- test map, combat, reward, event, shop and campfire flows;
- verify that the local JSONL file continues to grow.

Keep the default `game_actions` path free of raw input unless a consumer explicitly needs it. Raw input is useful for diagnosing missed UI paths, but it is noisy and can make training data harder to interpret.

## Troubleshooting

### No TCP connection

Start a listener on `127.0.0.1:8766` before launching the game. TCP is optional, so this does not prevent local recording. Check the game's stderr/log for connection messages and inspect `data/actionrecorder`.

### No local files

Check the game's working directory, the `events_dir` JVM property, and write permissions. The directory is created by the background writer at startup.

### Missing or duplicated semantic events

Check the installed game jar version first. Then compare the action with the corresponding game method and inspect the raw-input mode only as a diagnostic. Do not fix duplicates by filtering downstream until the patch boundary is understood.
