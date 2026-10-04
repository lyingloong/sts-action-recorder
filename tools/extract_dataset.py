"""Extract portable, lossless, fine-grained run datasets (Python 3.10+, stdlib).

No model prompts, knowledge lookup, inferred legal actions or SFT step filtering.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

SCHEMA = "actionrecorder-dataset-1"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def load_runs(paths: list[Path]) -> tuple[dict, list, int]:
    runs, seen, sources = defaultdict(list), {}, []
    duplicates = 0
    for path in paths:
        sources.append({"path": str(path.resolve()), "sha256": digest(path)})
        with path.open(encoding="utf-8-sig") as source:
            for line_number, line in enumerate(source, 1):
                if not line.strip():
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise ValueError(f"{path}:{line_number}: invalid JSON: {exc.msg}") from exc
                if not isinstance(event, dict):
                    raise ValueError(f"{path}:{line_number}: expected event object")
                session, seq = event.get("recorder_session"), event.get("event_seq")
                if session is not None and isinstance(seq, int):
                    key = (session, seq)
                    if key in seen:
                        if seen[key] != event:
                            raise ValueError(f"conflicting duplicate event: {key}")
                        duplicates += 1
                        continue
                    seen[key] = event
                run_id = event.get("run_id")
                if run_id:
                    runs[str(run_id)].append(event)
    # A session's event sequence is authoritative, even if files were copied
    # in a different order. Across process restarts use session start time.
    for events in runs.values():
        sessions = {}
        for event in events:
            session = event.get("recorder_session")
            stamp = event.get("timestamp_ms", 0)
            sessions[session] = min(sessions.get(session, stamp), stamp)
        events.sort(key=lambda e: (sessions[e.get("recorder_session")],
                                   str(e.get("recorder_session") or ""),
                                   e.get("event_seq", e.get("timestamp_ms", 0))))
    return dict(runs), sources, duplicates


def run_metadata(events: list[dict]) -> dict:
    metadata = {"character": None, "ascension": None, "seed": None}
    warnings = []
    for event in events:
        if event.get("type") == "run_started":
            for key in metadata:
                value = event.get(key)
                if value is not None:
                    if metadata[key] is not None and metadata[key] != value:
                        warnings.append(f"conflicting_{key}")
                    else:
                        metadata[key] = value
    terminals = [e for e in events if e.get("type") in {"run_finished", "run_ended"}
                 and e.get("terminal") is True and e.get("reason") in {"victory", "death"}]
    outcome, victory_type = "incomplete", None
    if terminals:
        reasons = {e["reason"] for e in terminals}
        outcome = terminals[-1]["reason"] if len(reasons) == 1 else "unknown"
        if len(reasons) != 1:
            warnings.append("conflicting_terminal_results")
        if outcome == "victory":
            acts = [(e.get("context") or {}).get("act") for e in terminals]
            act = next((a for a in reversed(acts) if isinstance(a, int)), None)
            # Old recordings could mislabel an act-one boss as run victory.
            if act is not None and act < 3:
                outcome = "unknown"
                warnings.append("victory_before_act_three")
            else:
                victory_type = "heart" if act == 4 else "normal" if act == 3 else "unknown"
    metadata.update(outcome=outcome, victory_type=victory_type,
                    warnings=sorted(set(warnings)),
                    mod_versions=sorted({str(e["mod_version"]) for e in events if e.get("mod_version")}),
                    event_schema_versions=sorted({str(e["schema_version"]) for e in events if e.get("schema_version")}))
    return metadata


def extract_steps(events: list[dict]) -> list[dict]:
    states, transactions = {}, {}
    for index, event in enumerate(events):
        kind = event.get("type")
        if kind == "state_published" and event.get("state_id"):
            sid = event["state_id"]
            if sid in states and states[sid][1] != event:
                raise ValueError(f"conflicting state ID: {sid}")
            states[sid] = (index, event)
        tx = event.get("transaction_id")
        if tx and kind in {"action_begin", "action_accepted", "action_rejected",
                           "action_execution_begin", "action_execution_result", "action_effects_settled"}:
            group = transactions.setdefault(tx, {})
            if kind in group and group[kind][1] != event:
                raise ValueError(f"conflicting {kind}: {tx}")
            group[kind] = (index, event)
    rows = []
    for tx, group in transactions.items():
        marker = lambda kind: group.get(kind, (-1, {}))[1]
        begin, accepted, rejected = (marker(k) for k in ("action_begin", "action_accepted", "action_rejected"))
        execution, result, settled = (marker(k) for k in ("action_execution_begin", "action_execution_result", "action_effects_settled"))
        flags = []

        def resolve(event, field, label):
            sid = event.get(field)
            item = states.get(sid)
            if item is None:
                flags.append(f"{label}_missing")
                return None
            index, published = item
            message = published.get("message")
            if not isinstance(message, dict) or message.get("error") is not None:
                flags.append(f"{label}_invalid_message")
                return None
            if (message.get("recorder_state_id") != sid
                    or published.get("recorder_session") != event.get("recorder_session")):
                flags.append(f"{label}_identity_mismatch")
            marker_index = group.get(event.get("type"), (-1, {}))[0]
            if index > marker_index:
                flags.append(f"{label}_published_after_marker")
            state = message.get("game_state") or {}
            if any(state.get(k) != v for k, v in (event.get("context") or {}).items()
                   if k in {"act", "floor"} and v is not None):
                flags.append(f"{label}_context_mismatch")
            expected = str(event.get("expected_screen") or "").upper()
            if expected not in {"", "NULL"} and expected not in {
                    str(state.get("screen_type") or "").upper(), str(state.get("screen_name") or "").upper()}:
                flags.append(f"{label}_screen_mismatch")
            return message  # Preserve the actual referenced data even when flagged.

        before = resolve(begin, "before_state_id", "before")
        execution_before = resolve(execution, "execution_before_state_id", "execution_before") if execution else None
        after = resolve(settled, "after_state_id", "after") if settled else None
        if not begin:
            flags.append("missing_action_begin")
        if accepted and rejected:
            flags.append("conflicting_acceptance")
        if accepted.get("execution_tracking") and not result:
            flags.append("missing_execution_result")
        if result.get("status") == "executed" and not execution:
            flags.append("missing_execution_begin")
        if execution and execution.get("logical_boundary") is not True:
            flags.append("execution_boundary_unconfirmed")
        rows.append({
            "schema_version": "actionrecorder-step-1", "run_id": events[0].get("run_id"),
            "transaction_id": tx, "order": len(rows),
            "status": "rejected" if rejected else "accepted" if accepted else "pending",
            "before_state_id": begin.get("before_state_id"), "observation_before": before,
            "available_actions": None if before is None else {
                "available_commands": before.get("available_commands"),
                "choice_list": (before.get("game_state") or {}).get("choice_list")},
            "chosen_action": accepted.get("action", accepted.get("chosen_action")),
            "execution_tracking": accepted.get("execution_tracking", False),
            "execution_before_state_id": execution.get("execution_before_state_id"),
            "execution_observation_before": execution_before,
            "execution_action": execution.get("action"), "execution_status": result.get("status"),
            "after_state_id": settled.get("after_state_id"), "observation_after": after,
            "after_source": "action_effects_settled" if after is not None else None,
            "quality_flags": sorted(set(flags)),
            "markers": {kind: item[1] for kind, item in group.items()},
        })
    return rows


def matches(meta: dict, *, characters=(), ascensions=(), outcomes=(), victory_types=(), mod_versions=()) -> bool:
    return (not characters or str(meta["character"]).upper() in {c.upper() for c in characters}) and (
        not ascensions or meta["ascension"] in ascensions) and (
        not outcomes or meta["outcome"] in outcomes) and (
        not victory_types or meta["victory_type"] in victory_types) and (
        not mod_versions or set(meta["mod_versions"]).issubset(mod_versions) and bool(meta["mod_versions"]))


def extract(paths: list[Path], output: Path, *, require_complete_states=False, **filters) -> dict:
    if output.exists():
        raise ValueError(f"output must be a new directory: {output}")
    runs, sources, duplicates = load_runs(paths)
    prepared, excluded = [], []
    for run_id, events in runs.items():
        metadata = run_metadata(events)
        if not matches(metadata, **filters):
            excluded.append({"run_id": run_id, "reason": "run_filter", "metadata": metadata})
            continue
        steps = extract_steps(events)
        if require_complete_states and (not steps or any(
                row["quality_flags"] or row["execution_tracking"] and row["execution_observation_before"] is None
                for row in steps)):
            excluded.append({"run_id": run_id, "reason": "incomplete_states", "metadata": metadata})
            continue
        prepared.append((run_id, events, metadata, steps))
    output.mkdir(parents=True)
    (output / "events").mkdir()
    (output / "trajectories").mkdir()
    manifest = {"schema_version": SCHEMA, "sources": sources,
                "extractor_sha256": digest(Path(__file__)),
                "filters": {**filters, "require_complete_states": require_complete_states},
                "duplicate_events_removed": duplicates, "excluded_runs": excluded, "runs": []}
    for run_id, events, metadata, steps in prepared:
        stem = re.sub(r"[^A-Za-z0-9_-]", "_", run_id)[:80] + "-" + hashlib.sha256(run_id.encode()).hexdigest()[:12]
        journal, trajectory = output / "events" / f"{stem}.jsonl", output / "trajectories" / f"{stem}.jsonl"
        write_jsonl(journal, events)
        write_jsonl(trajectory, steps)
        manifest["runs"].append({"run_id": run_id, "metadata": metadata,
            "events": {"path": journal.relative_to(output).as_posix(), "sha256": digest(journal), "count": len(events)},
            "trajectory": {"path": trajectory.relative_to(output).as_posix(), "sha256": digest(trajectory), "count": len(steps)},
            "quality_flags": dict(Counter(flag for row in steps for flag in row["quality_flags"]))})
    write_json(output / "manifest.json", manifest)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, nargs="+", required=True, help="raw JSONL files or directories (run-*.jsonl)")
    parser.add_argument("--output", type=Path, required=True, help="new dataset directory; never overwrites")
    parser.add_argument("--character", nargs="+", default=[])
    parser.add_argument("--ascension", type=int, nargs="+", default=[])
    parser.add_argument("--ascension-range", type=int, nargs=2, metavar=("MIN", "MAX"))
    parser.add_argument("--outcome", nargs="+", choices=["victory", "death", "incomplete", "unknown"], default=[])
    parser.add_argument("--victory-type", nargs="+", choices=["normal", "heart", "unknown"], default=[])
    parser.add_argument("--mod-version", nargs="+", default=[])
    parser.add_argument("--require-complete-states", action="store_true", help="exclude whole runs with unresolved decision states")
    args = parser.parse_args()
    try:
        paths = sorted({p.resolve() for source in args.input for p in
                        (source.glob("run-*.jsonl") if source.is_dir() else [source])})
        if not paths:
            raise ValueError("no raw run journals found")
        ascensions = set(args.ascension)
        if args.ascension_range:
            low, high = args.ascension_range
            if low > high:
                raise ValueError("ascension range MIN must not exceed MAX")
            ascensions.update(range(low, high + 1))
        result = extract(paths, args.output, characters=args.character, ascensions=sorted(ascensions),
                         outcomes=args.outcome, victory_types=args.victory_type, mod_versions=args.mod_version,
                         require_complete_states=args.require_complete_states)
        print(json.dumps({"selected_runs": len(result["runs"]), "excluded_runs": len(result["excluded_runs"]),
                          "output": str(args.output)}, ensure_ascii=False))
        return 0
    except (ValueError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
