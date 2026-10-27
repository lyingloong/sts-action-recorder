# AGENTS.md

Short guide for coding agents. Humans: [CONTRIBUTING.md](CONTRIBUTING.md). Details: [docs/](docs/).

## Repo

ModTheSpire/BaseMod mod that records Slay the Spire 1 player decisions at their execution
boundaries, with the state CommunicationMod published before them. It records; it does not decide
what a model sees or what a training label is.

## Commands

```powershell
$steamApps = "C:/path/to/Steam/steamapps"
mvn clean package "-DSteam.path=$steamApps"                              # also installs into the game
mvn clean package "-DSteam.path=$steamApps" "-Dactionrecorder.install.skip=true"  # checks only
python -m unittest discover -s tests -v
git diff --check
```

Java 8 and Maven; game, BaseMod and ModTheSpire jars must exist locally and are never committed.
Close the game before a build that installs. Do not use `maven.antrun.skip`; it also skips the
patch and bytecode checks.

## Rules

- Record before the game mutates state, keep the original return value, and record acceptance only
  on the branch that actually accepted the action.
- Link actions to states by explicit id. Never match by timestamp, arrival order or screen diff.
- Keep raw fields complete; `null` means unavailable. Do not pre-render for a consumer.
- Preview or inspect on copies. Never execute a game effect just to learn what it does.
- Fine granularity: entry, selection, confirmation and cancel are separate events.
- Event or state schema changes update the schema files, the protocol docs and the smoke checks
  together.

## Data

Never commit `target/`, `datasets/`, game or dependency jars, recordings, logs, credentials or
machine-local paths. Keep the event protocol version in sync with the docs.

## Workflow

Update [CHANGELOG.md](CHANGELOG.md) under `[Unreleased]` for every user-visible change, then commit
and push when the change is verified and the tree is clean. Prefer small, focused commits; do not
leave finished work sitting only on a local branch.
