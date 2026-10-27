# Changelog

All notable changes to StS Action Recorder are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions match
`src/main/resources/ModTheSpire.json` and `pom.xml`; the event protocol version is tracked
separately in [docs/EVENT_SCHEMA.md](docs/EVENT_SCHEMA.md).

Older versions are summarised by date range because their exact version boundaries were not kept.
No git tags have been cut yet.

## [Unreleased]

### Added

- `AGENTS.md`, a short agent guide covering build commands, recording invariants and the
  changelog/commit/push workflow, and this changelog.

## [0.1.6] - 2026-10-20

Event protocol 0.5. Focused on command attribution and card previews.

### Added

- `CommandLedger` and `CommunicationCommandPatches`: optional script commands are linked to the
  state they answered, with before-state validation, duplicate/expired rejection and execution
  receipts. Outgoing plays and potions issued through the script interface are recorded.
- `CampfireOptions` plus `CampfireOptionsSmoke`: campfire options are captured as a snapshot with
  inherited fields, localised text and stable indices; unknown mod options stay `null` instead of
  being reconstructed.
- `DecisionState` plus `DecisionStateSmoke`: an independent projection used to validate execution,
  so non-hand temporary value resets do not reject valid decisions while the full recorded state
  stays complete.
- `GridCardPreviews` plus `GridCardPreviewsSmoke`: upgrade previews for grid candidates, built on
  duplicated cards so the original and the existing UI preview are untouched.
- Java smoke checks for the above, wired into the Maven `test` phase; GitHub Actions runs the
  Python tool tests on Windows and Linux.

### Changed

- A validation-only build (`-Dactionrecorder.install.skip=true`) now skips only the install copy
  and still runs the Java smoke and bytecode checks.
- `CONTRIBUTING.md` added; README, collection audit, development guide, event/state contracts and
  the TCP protocol document updated to match.

### Fixed

- Original cards were misclassified as unsupported during upgrade-preview synthesis under
  ModTheSpire's class loading, because the check depended on `ProtectionDomain`/`CodeSource`,
  which patch class redefinition does not preserve. Original cards are now identified by the JAR
  that owns their class resource, cached per class, with regression coverage for both plain
  classpath and null-domain loading. Mod cards, modified cards and unknown resources are still
  refused.
- Command recording is no longer confused with normal state publication; queued and failed
  transactions keep their real status instead of being labelled `not_executed`.

## [0.1.5] - 2026-10-08

### Added

- `RunIdentity` and `RewardIdentity`: run/character/ascension/seed fingerprints stored in the
  BaseMod save field, and reward ids assigned by object identity, so same-name rewards and resumed
  saves stay distinguishable.
- `SaveLoadPatches`: a load barrier that waits for BaseMod's raw load and game initialisation
  before restoring identity, instead of treating a temporary `act=0`/`seed=null` as a new run.
- Snapshot marking (`recorder_snapshot_only`) with boundary types, so consumers can tell a
  recorder-triggered publication from a real CommunicationMod one.
- `JournalIdentitySmoke` and `CommunicationSnapshotSmoke`.
- Run-level dataset extraction and audit tooling documented in
  [docs/DATASET_EXTRACTION.md](docs/DATASET_EXTRACTION.md).

### Fixed

- A resumed run appends to the original file with `run_resumed` and a new `segment_id` rather than
  creating a second run.
- Reward ids no longer collide across multiple rewards shown on one screen.

## Earlier history

### 2026-10-04 to 2026-10-05

- Execution-boundary capture for queued plays and end-turn, with `action_begin`/`action_accepted`/
  `action_rejected` transactions.
- `QueuedDecisionTracker`, `MatchGameState`, `RuntimeStateFields` and patch binding/bytecode smoke
  checks.
- `tools/audit_trace.py` and `tools/extract_dataset.py`; user guide, collection audit and dataset
  extraction docs split out.
- Runtime entity descriptions (cards, relics, potions, powers) recorded from live game objects, and
  post-state settlement limitations documented.

### 2026-09-27 to 2026-09-30

- Initial mod: semantic decision patches, raw input capture modes, asynchronous JSONL/TCP writers
  that survive a missing receiver, and in-game action/available-action overlays.
- Transaction protocol refinement and configurable display settings.
- Canonical state contract with `docs/schema/state.schema.json` and the CommunicationMod state
  bridge; portable build and MIT licence.
