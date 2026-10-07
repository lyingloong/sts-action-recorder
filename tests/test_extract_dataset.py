import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("extract_dataset", Path(__file__).parents[1] / "tools" / "extract_dataset.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def journal(*, outcome="death", act=1, character="IRONCLAD", ascension=0):
    rows = [
        {"type": "run_started", "character": character, "ascension": ascension, "seed": "-123"},
        {"type": "state_published", "state_id": "s:1", "message": {
            "recorder_state_id": "s:1", "available_commands": ["choose"],
            "game_state": {"act": 1, "floor": 0, "custom_field": "长" * 16000}}},
        {"type": "action_begin", "transaction_id": "s:t1", "before_state_id": "s:1", "context": {"act": 1, "floor": 0}},
        {"type": "action_accepted", "transaction_id": "s:t1", "action": {"kind": "neow_option_selected", "id": "CHOOSE:index=0"}},
        {"type": "action_begin", "transaction_id": "s:t2", "before_state_id": "s:1"},
        {"type": "action_rejected", "transaction_id": "s:t2", "reason": "cancelled"},
        {"type": "run_finished", "reason": outcome, "terminal": True, "context": {"act": act}},
    ]
    return [dict(row, run_id="run", recorder_session="s", event_seq=i + 1,
                 timestamp_ms=i + 1, schema_version="0.5", mod_version="0.1.1") for i, row in enumerate(rows)]


class DatasetExtractionTests(unittest.TestCase):
    def test_full_fields_and_rejected_attempt_are_retained(self):
        steps = module.extract_steps(journal())
        self.assertEqual(len(steps), 2)
        self.assertEqual(steps[1]["status"], "rejected")
        self.assertEqual(len(steps[0]["observation_before"]["game_state"]["custom_field"]), 16000)
        self.assertIsNone(steps[0]["observation_after"])
        self.assertIsNone(steps[0]["available_actions"]["choice_list"])
        self.assertNotIn("usable_for_sft", steps[0])

    def test_run_filter_and_unknown_victory(self):
        meta = module.run_metadata(journal(outcome="victory", act=4))
        self.assertTrue(module.matches(meta, characters=["ironclad"], ascensions=[0], victory_types=["heart"]))
        self.assertFalse(module.matches(meta, outcomes=["death"]))
        self.assertEqual(module.run_metadata(journal(outcome="victory", act=1))["outcome"], "unknown")
        self.assertEqual(module.run_metadata(journal(outcome="victory", act=3))["victory_type"], "normal")

    def test_save_exit_not_terminal(self):
        rows = journal()[:-1] + [{"type": "run_ended", "terminal": False, "reason": "save_and_quit"}]
        self.assertEqual(module.run_metadata(rows)["outcome"], "incomplete")

    def test_resume_keeps_metadata_and_explicit_segments(self):
        rows = journal()
        rows[0]["segment_id"] = "a"
        rows[1]["message"]["recorder_segment_id"] = "a"
        rows[2]["segment_id"] = "a"
        rows += [rows[0] | {"type": "run_resumed", "segment_id": "b", "checkpoint_id": "cp"}]
        meta = module.run_metadata(rows)
        self.assertEqual(meta["seed"], "-123")
        self.assertEqual(meta["segments"][1]["checkpoint_id"], "cp")
        self.assertEqual(module.extract_steps(rows)[0]["segment_id"], "a")
        rows[2]["segment_id"] = "b"
        self.assertIn("before_segment_mismatch", module.extract_steps(rows)[0]["quality_flags"])

    def test_recording_started_from_resume_has_real_metadata(self):
        rows = journal()
        rows[0]["type"] = "run_resumed"
        self.assertEqual(module.run_metadata(rows)["character"], "IRONCLAD")

    def test_missing_state_retains_action(self):
        steps = module.extract_steps([e for e in journal() if e["type"] != "state_published"])
        self.assertEqual(len(steps), 2)
        self.assertIsNone(steps[0]["observation_before"])
        self.assertIn("before_missing", steps[0]["quality_flags"])

    def test_execution_and_after_use_only_explicit_ids(self):
        rows = journal()[:-1]
        rows[3]["execution_tracking"] = True
        rows += [dict(rows[1], state_id="s:2", message={"recorder_state_id": "s:2", "game_state": {"energy": 1}}),
                 {"type": "action_execution_begin", "transaction_id": "s:t1", "execution_before_state_id": "s:2", "recorder_session": "s"},
                 {"type": "action_execution_result", "transaction_id": "s:t1", "status": "executed"},
                 {"type": "action_effects_settled", "transaction_id": "s:t1", "after_state_id": "s:missing"}]
        step = module.extract_steps(rows)[0]
        self.assertEqual(step["execution_observation_before"]["game_state"]["energy"], 1)
        self.assertIsNone(step["observation_after"])
        self.assertEqual(step["execution_status"], "executed")

    def test_dedup_resume_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            first, second = root / "a.jsonl", root / "b.jsonl"
            rows = journal()
            module.write_jsonl(first, rows[:4])
            resumed = dict(rows[-1], recorder_session="s2", event_seq=1, timestamp_ms=999)
            module.write_jsonl(second, rows[2:-1] + [resumed])
            result = module.extract([second, first], root / "out", characters=["IRONCLAD"])
            self.assertEqual(result["duplicate_events_removed"], 2)
            self.assertEqual(len(result["runs"]), 1)
            self.assertEqual(result["runs"][0]["metadata"]["seed"], "-123")
            self.assertEqual(result["runs"][0]["trajectory"]["count"], 2)
            with self.assertRaises(ValueError):
                module.extract([first], root / "out")

    def test_conflicting_duplicate_and_broken_json_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "a.jsonl"
            rows = journal()
            module.write_jsonl(path, [rows[0], dict(rows[0], seed="456")])
            with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
                module.load_runs([path])
            path.write_text('{"broken":', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "invalid JSON"):
                module.load_runs([path])


if __name__ == "__main__":
    unittest.main()
