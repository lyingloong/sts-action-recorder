# AGENTS.md

Instructions for coding agents working in this repository. Humans should read
[CONTRIBUTING.md](CONTRIBUTING.md); the rules overlap by design. User-facing and protocol
documentation is mostly Chinese; code, identifiers and schema keys are English.

## What this repository is

`StS Action Recorder` is a ModTheSpire/BaseMod mod for Slay the Spire 1 that records player
decisions at their real execution boundaries, together with the state CommunicationMod published
before them. It writes one JSONL file per run and can stream a copy over TCP.

It is a recorder, not a policy. Prompt rendering, action filtering, training labels and anything
that decides what a model should see belong to downstream consumers such as
[sts-agent](https://github.com/lyingloong/sts-agent). Keep complete raw fields here.

## Environment

- Java 8 for the mod; Python 3.10+ (standard library only) for `tools/` and `tests/`.
- A local Slay the Spire 1 install plus BaseMod and ModTheSpire jars. These are proprietary or
  third-party and are never downloaded by CI or committed.
- Maven 3.x. Dependency paths are passed at build time, not hardcoded.

## Build and checks

```powershell
$env:JAVA_HOME = "C:/path/to/jdk8"
$steamApps = "C:/path/to/Steam/steamapps"

# Full build; the package phase also copies the jar into the game's mods directory
mvn clean package "-DSteam.path=$steamApps"

# Validation only: build, run smoke checks, do not install
mvn clean package "-DSteam.path=$steamApps" "-Dactionrecorder.install.skip=true"

# Python tools
python -m unittest discover -s tests -v
git diff --check
```

Close the game before a build that installs the jar. Do not replace
`-Dactionrecorder.install.skip=true` with `maven.antrun.skip`; that skips the verification tasks
as well.

The Maven `test` phase runs the Java smoke checks (patch binding, instrumentation, bytecode
verification, queue identity, state fields, save/reward identity, snapshots, decision state,
campfire options, grid previews, command ledger). They are `main()` programs, not JUnit tests.
When CommunicationMod is installed, serializer and real-`DataReader` checks also run; otherwise
they print `SKIP` explicitly. A JVM verification failure must block the build, never be silenced
with `-Xverify:none`.

Manual classpath and `-Xverify:all` commands are listed in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Layout

| Path | Contents |
| --- | --- |
| `src/main/java/actionrecorder/ActionRecorder.java` | BaseMod entry, save fields, update/render hooks. |
| `src/main/java/actionrecorder/patches/` | Decision, state, command, raw/controller input patches. |
| `src/main/java/actionrecorder/runtime/` | Run identity, transactions, queued decisions, previews, snapshots, JSONL/TCP writers. |
| `src/main/java/actionrecorder/ui/` | In-game action toast and available-action overlay. |
| `src/main/resources/ModTheSpire.json` | Mod id, version and dependency metadata. |
| `src/test/java/actionrecorder/` | Java smoke checks. |
| `tools/` | `audit_trace.py` (read-only diagnosis) and `extract_dataset.py` (filtered export). |
| `tests/` | Python tests for the tools. |
| `docs/` | User guide, event/state contracts, TCP protocol, collection audit, dataset extraction. |

## Invariants

1. **Record at the execution boundary.** Call `beginDecision()` before the game mutates state and
   keep the game's original return value and behaviour. Record acceptance only on the branch that
   actually accepted the action; otherwise discard the pending decision.
2. **Explicit identity, never timing.** States are numbered when published and actions reference
   `state_published` by id. Do not match before/after states by timestamps, arrival order or
   diffing screens.
3. **Keep raw fields complete.** Do not trim, normalise or pre-render entity data for a consumer.
   `null` means the information was unavailable; never substitute a cached value from another
   scene.
4. **Do not mutate the game to observe it.** Previews and text extraction work on copies (for
   example `GridCardPreviews` upgrades a duplicate card). Never execute an effect or call
   `useOption` just to learn what it does.
5. **Fine granularity.** Entry, selection, confirmation and back/cancel are separate events.
   Automatic effects are not new player actions. Do not collapse them for convenience.
6. **Save/load is a boundary.** Appending to an existing run keeps `run_id` and opens a new
   `segment_id`; consumers must not splice across it. A temporary `act=0`/`seed=null` during a load
   is not a new run.
7. **Schema changes are contract changes.** Update [docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md),
   [docs/STATE_SCHEMA.md](docs/STATE_SCHEMA.md), `docs/schema/state.schema.json`, the protocol
   version where applicable, and the smoke checks in the same change.
8. **No game assets in smoke checks.** Asset-free checks must not initialise game assets. Bytecode
   verification has to pass without disabling verification.

## Versioning

Mod version lives in three places and must stay in sync: `pom.xml`, `src/main/resources/ModTheSpire.json`,
and the version line in `README.md`. The event protocol version is documented in
[docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md) and [docs/TCP_PROTOCOL.md](docs/TCP_PROTOCOL.md);
bump it when consumers must change, not for additive fields.

## Data and secrets

Never commit: `target/`, `datasets/`, game or dependency jars, recordings, logs, credentials,
machine-local paths. `datasets/` is ignored and should stay that way. Do not upload a full
recording to an issue; use a minimal redacted excerpt.

## Live-game validation

Offline checks and bytecode verification do not prove an in-game fix. Before reporting a recording
change as verified, play the affected flow and inspect the produced JSONL. State the game and mod
versions, the exact steps and what you observed; distinguish simulated from in-game tests.

## Changelog

Add a line to `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) for every user-visible change
(recorded fields, protocol, configuration, tooling). Group under Added / Changed / Fixed /
Removed / Docs.

## Where to read more

- [docs/USER_GUIDE.md](docs/USER_GUIDE.md) — install, configure, locate files, troubleshoot
- [docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md) — event kinds and fields
- [docs/STATE_SCHEMA.md](docs/STATE_SCHEMA.md) — state contract and null conventions
- [docs/TCP_PROTOCOL.md](docs/TCP_PROTOCOL.md) — streaming interface
- [docs/COLLECTION_AUDIT.md](docs/COLLECTION_AUDIT.md) — accept a recording before using it
- [docs/DATASET_EXTRACTION.md](docs/DATASET_EXTRACTION.md) — run-level filtering and export
