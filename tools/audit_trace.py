"""Read-only audit of an ActionRecorder schema 0.5 per-run raw journal."""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


def audit(path: Path) -> dict:
    states, begins, accepted, rejected = {}, {}, {}, set()
    execution_begins, execution_results = {}, {}
    kinds, screens = Counter(), Counter()
    errors, warnings, outcomes = [], [], []
    run_ids = set()
    latest_seq = {}
    with path.open(encoding="utf-8-sig") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError as exc:
                errors.append(f"line {line_number}: invalid JSON ({exc.msg})")
                continue
            if not isinstance(event, dict):
                errors.append(f"line {line_number}: event must be an object")
                continue
            if event.get("run_id"):
                run_ids.add(event["run_id"])
            session = event.get("recorder_session")
            seq = event.get("event_seq")
            if isinstance(seq, int):
                if seq <= latest_seq.get(session, -1):
                    errors.append(f"line {line_number}: non-monotonic event_seq")
                latest_seq[session] = seq
            kind = event.get("type")
            tx = event.get("transaction_id")
            if kind == "state_published":
                identifier = event.get("state_id")
                message = event.get("message")
                if not identifier or not isinstance(message, dict):
                    errors.append(f"line {line_number}: missing state ID/message")
                    continue
                if identifier in states:
                    errors.append(f"line {line_number}: duplicate state ID {identifier}")
                if message.get("recorder_state_id") != identifier:
                    errors.append(f"line {line_number}: inconsistent state ID")
                states[identifier] = message
                state = message.get("game_state") or {}
                screens[str(state.get("screen_type"))] += 1
                if state.get("screen_type") != "MAP" and state.get("map") is not None:
                    errors.append(f"line {line_number}: map present outside MAP")
                if "seed" in state:
                    errors.append(f"line {line_number}: seed repeated in state")
                if message.get("in_game"):
                    for required in ("class", "act", "floor", "current_hp", "max_hp", "gold", "deck", "relics", "potions", "keys", "act_boss"):
                        if required not in state:
                            warnings.append(f"line {line_number}: state missing {required}")
                    if state.get("room_phase") == "COMBAT" and state.get("screen_type") == "NONE":
                        combat = state.get("combat_state")
                        if not isinstance(combat, dict) or any(field not in combat for field in ("hand", "player", "monsters")):
                            errors.append(f"line {line_number}: incomplete combat state")
            elif kind == "action_begin":
                if not tx or tx in begins:
                    errors.append(f"line {line_number}: missing/duplicate transaction ID")
                begins[tx] = event
                identifier = event.get("before_state_id")
                if not identifier or identifier not in states:
                    errors.append(f"line {line_number}: pre-state reference absent: {identifier}")
                else:
                    state = states[identifier].get("game_state") or {}
                    context = event.get("context") or {}
                    for key in ("act", "floor"):
                        if context.get(key) is not None and context[key] != state.get(key):
                            errors.append(f"line {line_number}: pre-state {key} mismatch")
            elif kind == "action_accepted":
                if tx not in begins or tx in accepted or tx in rejected:
                    errors.append(f"line {line_number}: accepted transaction without unique begin")
                action = event.get("action") or {}
                if not action.get("id") or not action.get("kind"):
                    errors.append(f"line {line_number}: missing action ID/kind")
                if action.get("autoplay"):
                    errors.append(f"line {line_number}: automated card play included")
                accepted[tx] = event
                kinds[str(action.get("kind"))] += 1
            elif kind == "action_rejected":
                if tx not in begins or tx in accepted:
                    errors.append(f"line {line_number}: rejected transaction without valid begin")
                rejected.add(tx)
            elif kind in {"action_execution_begin", "action_execution_result", "action_effects_settled"}:
                if tx not in accepted or not accepted[tx].get("execution_tracking"):
                    errors.append(f"line {line_number}: execution marker without tracked acceptance")
                if kind == "action_execution_begin":
                    if tx in execution_begins:
                        errors.append(f"line {line_number}: duplicate execution begin")
                    execution_begins[tx] = event
                    identifier = event.get("execution_before_state_id")
                    if not identifier or identifier not in states:
                        errors.append(f"line {line_number}: execution pre-state reference absent")
                elif kind == "action_execution_result":
                    if tx in execution_results:
                        errors.append(f"line {line_number}: duplicate execution result")
                    execution_results[tx] = event
                    if event.get("status") not in {"executed", "skipped", "cancelled"}:
                        errors.append(f"line {line_number}: invalid execution status")
                    if event.get("status") == "executed" and tx not in execution_begins:
                        errors.append(f"line {line_number}: executed without execution begin")
                elif event.get("after_state_id") not in states:
                    errors.append(f"line {line_number}: settled state reference absent")
            elif kind in {"run_finished", "run_ended"}:
                outcomes.append({"type": kind, "reason": event.get("reason"), "terminal": event.get("terminal")})
    if len(run_ids) > 1:
        errors.append("more than one run_id in the per-run file")
    unfinished = set(begins) - set(accepted) - rejected
    if unfinished:
        warnings.append(f"{len(unfinished)} unclosed transaction(s), possibly interrupted while acting")
    missing_execution = [tx for tx, event in accepted.items()
                         if event.get("execution_tracking") and tx not in execution_results]
    if missing_execution:
        warnings.append(f"{len(missing_execution)} queued action(s) without execution result; not safe training targets")
    if not states:
        errors.append("no state_published records: old schema or CommunicationMod unavailable")
    if not any(outcome["terminal"] for outcome in outcomes):
        warnings.append("no terminal result yet; not a confirmed complete run")
    if any(outcome["reason"] == "victory" and outcome["terminal"] for outcome in outcomes):
        if not any(outcome["type"] == "run_finished" and outcome["reason"] == "victory"
                   and outcome["terminal"] for outcome in outcomes):
            errors.append("victory label lacks an explicit run_finished terminal event; possible Act transition misclassification")
    return {"path": str(path), "run_ids": sorted(run_ids), "states": len(states),
            "accepted_actions": len(accepted), "rejected_attempts": len(rejected),
            "action_kinds": dict(kinds), "state_screens": dict(screens), "outcomes": outcomes,
            "execution_statuses": dict(Counter(event.get("status") for event in execution_results.values())),
            "errors": errors, "warnings": warnings,
            "note": "Structural checks cannot prove that every human action was captured; compare with the acceptance checklist."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    args = parser.parse_args()
    try:
        report = audit(args.trace)
    except OSError as exc:
        parser.error(str(exc))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
