import json
import tempfile
import unittest
from pathlib import Path

from tools.audit_trace import audit


def events():
    common = {"schema_version": "0.5", "recorder_session": "s", "run_id": "r"}
    game = {"screen_type": "NONE", "map": None, "room_phase": "COMBAT",
            "class": "IRONCLAD", "act": 1, "floor": 1, "current_hp": 80, "max_hp": 80,
            "gold": 99, "deck": [], "relics": [], "potions": [], "keys": {}, "act_boss": "Slime Boss",
            "combat_state": {"hand": [], "player": {}, "monsters": []}}
    return [
        common | {"event_seq": 1, "type": "state_published", "state_id": "s:1",
                  "message": {"recorder_state_id": "s:1", "in_game": True, "game_state": game}},
        common | {"event_seq": 2, "type": "action_begin", "transaction_id": "tx", "before_state_id": "s:1", "context": {"floor": 1}},
        common | {"event_seq": 3, "type": "action_accepted", "transaction_id": "tx", "action": {"id": "END_TURN", "kind": "end_turn"}},
        common | {"event_seq": 4, "type": "run_finished", "reason": "death", "terminal": True},
    ]


class AuditTraceTests(unittest.TestCase):
    def check_events(self, values):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "run.jsonl"
            path.write_text("\n".join(json.dumps(value) for value in values), encoding="utf-8")
            return audit(path)

    def test_complete_structural_trace(self):
        result = self.check_events(events())
        self.assertEqual(result["errors"], [])
        self.assertEqual(result["accepted_actions"], 1)

    def test_missing_pre_state_is_error(self):
        values = events()
        values[1]["before_state_id"] = "missing"
        self.assertTrue(any("pre-state reference" in error for error in self.check_events(values)["errors"]))

    def test_map_and_seed_outside_contract_are_errors(self):
        values = events()
        values[0]["message"]["game_state"].update(map=[{"x": 0}], seed=10)
        self.assertEqual(len(self.check_events(values)["errors"]), 2)

    def test_duplicate_acceptance_is_error(self):
        values = events()
        values.append(values[2] | {"event_seq": 5})
        self.assertTrue(any("unique begin" in error for error in self.check_events(values)["errors"]))

    def test_two_sessions_can_append_one_run(self):
        values = events()
        values.append({"recorder_session": "other", "run_id": "r", "event_seq": 1, "type": "run_started"})
        self.assertEqual(self.check_events(values)["errors"], [])

    def test_act_transition_cannot_be_claimed_as_victory(self):
        values = events()
        values[-1].update(type="run_ended", reason="victory")
        self.assertTrue(any("victory label lacks" in error for error in self.check_events(values)["errors"]))
        values.insert(-1, values[-1] | {"type": "run_finished", "event_seq": 4})
        values[-1]["event_seq"] = 5
        self.assertEqual(self.check_events(values)["errors"], [])


    def test_queued_action_requires_explicit_execution_evidence(self):
        values = events()
        values[2]["execution_tracking"] = True
        self.assertTrue(any("without execution result" in w for w in self.check_events(values)["warnings"]))
        base = {"recorder_session": "s", "run_id": "r", "transaction_id": "tx"}
        values.insert(3, base | {"event_seq": 4, "type": "action_execution_begin",
                                 "execution_before_state_id": "s:1", "logical_boundary": True})
        values.insert(4, base | {"event_seq": 5, "type": "action_execution_result", "status": "executed"})
        values[-1]["event_seq"] = 6
        result = self.check_events(values)
        self.assertEqual(result["errors"], [])
        self.assertEqual(result["execution_statuses"], {"executed": 1})
        values[3]["execution_before_state_id"] = "missing"
        self.assertTrue(any("execution pre-state reference" in e for e in self.check_events(values)["errors"]))

    def test_cancelled_queue_item_needs_no_execution_pre(self):
        values = events()
        values[2]["execution_tracking"] = True
        values.insert(3, {"recorder_session": "s", "run_id": "r", "transaction_id": "tx",
                           "event_seq": 4, "type": "action_execution_result", "status": "cancelled"})
        values[-1]["event_seq"] = 5
        self.assertEqual(self.check_events(values)["errors"], [])


if __name__ == "__main__":
    unittest.main()
