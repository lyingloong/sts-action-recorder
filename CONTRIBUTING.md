# Contributing

## Build and checks

The Mod targets Java 8. Building it requires a locally installed Slay the Spire 1, BaseMod and ModTheSpire.
These proprietary/third-party JARs are not downloaded by CI or redistributed by this repository.
See [Development](docs/DEVELOPMENT.md) for dependency locations, build arguments and Java smoke checks.

```sh
python -m unittest discover -s tests -v
git diff --check
```

The Python tools require only Python 3.10+ and its standard library. GitHub Actions runs these portable checks on Windows and Linux;
it does not compile the Java Mod or start the game. A local `mvn test` runs the configured Java smoke checks when game dependencies are present.
`mvn package` also installs the built JAR into the configured game directory, so close the game first.
For a validation-only build use `-Dactionrecorder.install.skip=true`; this skips installation but keeps all configured tests.

## Recording changes

- Record accepted semantic game operations at their actual execution boundaries, preserving the game's return value and behavior.
- Link states and actions with explicit identities, not timestamps or retrospective guesses from state changes.
- Keep complete raw fields. Prompt rendering, training labels and policy constraints belong to downstream consumers.
- Preserve `null` for unavailable information, source action granularity and save/load segment boundaries.
- Observation code must not mutate the original card or execute an option merely to discover its effect.
- Add regression checks and update the event/state documentation and JSON Schema when their contract changes.
- Never initialize game assets in an asset-free smoke check. Test class-loading and bytecode verification where relevant.

Passing offline checks does not replace live validation. Before reporting a recording fix as verified, test the affected game flow
and inspect the resulting JSONL. Include relevant game/Mod versions and distinguish simulated tests from in-game tests.

## Bug reports and sensitive material

Use GitHub Issues with the Mod/dependency versions, exact operation sequence, error and a minimal redacted trace excerpt.
Please do not upload game JARs, full recordings, credentials, machine-local paths or unrelated private data by default.
Use private contact with the maintainer for security-sensitive details before opening a public report.

Code is licensed under MIT; game assets, dependencies and datasets have separate rights and distribution requirements.
