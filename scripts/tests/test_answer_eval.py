"""Offline Answer evaluation tests. All HTTP calls are blocked or replaced."""
import contextlib
import copy
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import answer_eval as ev


def fixture():
    return next(c for c in ev.validate_cases(ev.pe.read_json(ev.CASES)) if c["id"] == "AF010")


def trace(case=None, content="币安BTC最新成交价120 USDT。"):
    c = case or fixture()
    return {"trace": {"question": c["question"]}, "events": [{
        "seq": 1, "event_type": "LLM_CALL", "name": "answer", "error": None,
        "input_json": {"messages": ev.fixture_messages(c), "time_context": c["fixture"]["time_context"]},
        "output_json": {"content": content, "model": "fixture-answer"}}]}


def record():
    c = fixture()
    return {"format_version": 1, "eval_kind": "answer", "case": c, "case_hash": ev.pe.digest(c), "trace": trace(c)}


def judgment(case=None, claim_verdict="SUPPORTED"):
    c = case or fixture()
    return {"criteria": [{"id": x["id"], "verdict": "PASS", "reason": "fixture reason",
                          "evidence": ["/facts/0/data/value=120"]} for x in c["criteria"]],
            "claims": [{"quote": "最新成交价120 USDT", "verdict": claim_verdict,
                        "reason": "fixture grounding reason", "source_refs": ["/facts/0/data/value"]}]}


class FakeJudge:
    model = "fixture-judge"
    api_key = "fixture-secret-key"

    def __init__(self, obj=None):
        self.obj = obj if obj is not None else judgment()
        self.calls = []

    def public(self):
        return {"model": self.model, "base_url": "https://fixture.invalid/v1"}

    def call(self, messages, schema=None):
        self.calls.append((messages, schema))
        return json.dumps(self.obj, ensure_ascii=False), {"duration_seconds": 1, "usage": {"completion_tokens": 10}}


class AnswerEvalTests(unittest.TestCase):
    def setUp(self):
        blocker = patch.object(ev.pe.urllib.request, "build_opener", side_effect=AssertionError("Network forbidden"))
        blocker.start()
        self.addCleanup(blocker.stop)

    def test_cases_cover_live_fixture_and_all_answer_dimensions(self):
        cases = ev.validate_cases(ev.pe.read_json(ev.CASES))
        self.assertEqual({"live", "fixture"}, {c["mode"] for c in cases})
        self.assertEqual(set(ev.DIMENSIONS), set(ev.coverage(cases)["dimensions"]))
        for c in cases:
            if c["mode"] == "fixture":
                decoded = ev.decode_answer_messages(ev.fixture_messages(c), c["question"])
                self.assertEqual(c["fixture"]["facts"], decoded["facts"])

    def test_empty_criteria_missing_grounding_and_duplicate_ids_rejected(self):
        for edit in (lambda c: c.update(criteria=[]), lambda c: c["criteria"].pop(0),
                     lambda c: c["criteria"].append(copy.deepcopy(c["criteria"][0]))):
            c = copy.deepcopy(fixture())
            edit(c)
            with self.assertRaises(ev.EvalError):
                ev.validate_cases({"format_version": 1, "cases": [c]})

    def test_trace_reads_encoded_messages_and_actual_FACTS_only(self):
        data = trace()
        e = data["events"][0]
        e["input_json"] = json.dumps(e["input_json"])
        e["output_json"] = json.dumps(e["output_json"])
        data["events"].append({"seq": 2, "event_type": "TOOL_CALL", "name": "other", "output_json": {"value": 123456}})
        r = record()
        r.update(trace=data, response={"answer": "cached wrong answer", "evidence": [{"value": 123456}]},
                 answer={"answer": "cached extraction must be ignored"})
        judge = FakeJudge()
        result = ev.grade_record(r, judge, "rubric")
        self.assertEqual("PASS", result["verdict"])
        payload = json.loads(judge.calls[0][0][1]["content"].split("\n", 1)[1])
        self.assertEqual(120, payload["facts"][0]["data"]["value"])
        self.assertNotIn("123456", json.dumps(payload))
        self.assertEqual("币安BTC最新成交价120 USDT。", payload["answer"])

    def test_historical_prompt_metadata_is_not_evaluable(self):
        data = trace()
        data["events"][0]["input_json"] = {"stage": "answer", "message_count": 2}
        judge = FakeJudge()
        result = ev.grade_record({**record(), "trace": data}, judge, "rubric")
        self.assertEqual("NOT_EVALUABLE", result["verdict"])
        self.assertEqual([], judge.calls)

    def test_time_metadata_does_not_override_visible_system_anchor(self):
        data = trace()
        data["events"][0]["input_json"]["time_context"] = {"timezone": "UTC"}
        self.assertEqual("NOT_EVALUABLE", ev.extract_answer(data, fixture()["question"])["verdict"])
        data = trace()
        m = data["events"][0]["input_json"]["messages"][0]
        m["content"] = "old Answer contract without time anchor"
        self.assertEqual({}, ev.extract_answer(data, fixture()["question"])["time_context"])

    def test_no_answer_call_is_not_applicable_not_PASS(self):
        data = {"trace": {}, "events": [{"seq": 1, "event_type": "PLAN", "name": "dag_planner"}]}
        judge = FakeJudge()
        self.assertEqual("NOT_APPLICABLE", ev.grade_record({**record(), "trace": data}, judge, "rubric")["verdict"])
        self.assertFalse(judge.calls)

    def test_answer_failure_and_empty_content_are_answer_errors(self):
        for data in (trace(content=""), trace()):
            if data["events"][0]["output_json"]["content"]:
                data["events"][0]["error"] = "simulated network failure"
            self.assertEqual("ANSWER_ERROR", ev.grade_record({**record(), "trace": data}, FakeJudge(), "rubric")["verdict"])

    def test_collection_error_and_missing_trace_do_not_call_judge(self):
        judge = FakeJudge()
        self.assertEqual("COLLECTION_ERROR", ev.grade_record({**record(), "collection_error": "offline"}, judge, "rubric")["verdict"])
        r = record(); r.pop("trace")
        self.assertEqual("NOT_EVALUABLE", ev.grade_record(r, judge, "rubric")["verdict"])
        self.assertEqual([], judge.calls)

    def test_mismatched_question_and_duplicate_json_keys_rejected(self):
        c = fixture()
        messages = ev.fixture_messages(c)
        with self.assertRaises(ev.EvalError):
            ev.decode_answer_messages(messages, "different query")
        messages[-1]["content"] = c["question"] + '\n\n<FACTS>\n[{"value":1,"value":2}]\n</FACTS>\n<QUERY_STATUS>\n{}\n</QUERY_STATUS>'
        with self.assertRaises(ev.EvalError):
            ev.decode_answer_messages(messages, c["question"])

    def test_final_answer_call_is_used_and_final_error_not_hidden(self):
        data = trace()
        other = copy.deepcopy(data["events"][0]); other["seq"] = 3
        other["output_json"]["content"] = "final answer"
        data["events"] = [other, data["events"][0]]
        self.assertEqual("final answer", ev.extract_answer(data, fixture()["question"])["answer"])
        other["error"] = "failed retry"
        self.assertEqual("ANSWER_ERROR", ev.extract_answer(data, fixture()["question"])["verdict"])

    def test_unsupported_claim_forces_FAIL_even_when_all_criteria_PASS(self):
        result = ev.grade_record(record(), FakeJudge(judgment(claim_verdict="UNSUPPORTED")), "rubric")
        self.assertEqual("FAIL", result["verdict"])

    def test_uncertain_claim_is_not_PASS(self):
        result = ev.grade_record(record(), FakeJudge(judgment(claim_verdict="UNCERTAIN")), "rubric")
        self.assertEqual("UNCERTAIN", result["verdict"])

    def test_missing_repeated_criterion_and_string_evidence_are_judge_errors(self):
        for edit in (lambda o: o["criteria"].pop(),
                     lambda o: o["criteria"].append(copy.deepcopy(o["criteria"][0])),
                     lambda o: o["criteria"][0].update(evidence="/facts")):
            obj = judgment(); edit(obj)
            self.assertEqual("JUDGE_ERROR", ev.grade_record(record(), FakeJudge(obj), "rubric")["verdict"])

    def test_claims_must_quote_real_answer_and_use_real_sources(self):
        for edit in (lambda o: o.update(claims=[]),
                     lambda o: o["claims"][0].update(quote="not in actual answer"),
                     lambda o: o["claims"][0].update(source_refs=[]),
                     lambda o: o["claims"][0].update(source_refs=["/facts/0/data/missing"]),
                     lambda o: o["claims"][0].update(source_refs=["/answer"]),
                     lambda o: o["claims"].append(copy.deepcopy(o["claims"][0]))):
            obj = judgment(); edit(obj)
            self.assertEqual("JUDGE_ERROR", ev.grade_record(record(), FakeJudge(obj), "rubric")["verdict"])

    def test_pointer_index_and_escape_rules(self):
        sample = {"facts": [{"a/b": {"~key": 5}}]}
        self.assertEqual(5, ev.source_pointer(sample, "/facts/0/a~1b/~0key"))
        for ref in ("facts", "/facts/-1", "/facts/01", "/facts/1", "/facts/0/a~2b", "/trace/value"):
            with self.assertRaises(ev.EvalError):
                ev.source_pointer(sample, ref)

    def test_judge_schema_has_dynamic_ids_and_string_array_evidence(self):
        schema = ev.judgment_schema(fixture())["json_schema"]["schema"]
        criteria = schema["properties"]["criteria"]
        self.assertEqual([x["id"] for x in fixture()["criteria"]], criteria["items"]["properties"]["id"]["enum"])
        self.assertEqual("array", criteria["items"]["properties"]["evidence"]["type"])
        self.assertIn("claims", schema["required"])

    def test_judge_config_reuses_planner_but_generation_requires_own_keys(self):
        env = {"PLANNER_EVAL_JUDGE_BASE_URL": "https://fixture.invalid/v1",
               "PLANNER_EVAL_JUDGE_API_KEY": "private-key", "PLANNER_EVAL_JUDGE_MODEL": "fixture"}
        with patch.dict(os.environ, env, clear=True):
            config = ev.ClientConfig.from_env()
            self.assertEqual("fixture", config.model)
            self.assertNotIn("private-key", repr(config))
            self.assertNotIn("private-key", json.dumps(config.public()))
            with self.assertRaises(ev.EvalError):
                ev.ClientConfig.from_env("MODEL")
        with patch.dict(os.environ, {**env, "ANSWER_EVAL_JUDGE_MODEL": "partial-config"}, clear=True):
            with self.assertRaises(ev.EvalError):
                ev.ClientConfig.from_env()

    def test_config_disallows_credentials_in_url_and_payload_overrides(self):
        env = {"ANSWER_EVAL_JUDGE_BASE_URL": "https://fixture.invalid/v1",
               "ANSWER_EVAL_JUDGE_API_KEY": "private-key", "ANSWER_EVAL_JUDGE_MODEL": "fixture"}
        for extra in ({"messages": []}, {"response_format": {}}, {"tools": []}):
            with patch.dict(os.environ, {**env, "ANSWER_EVAL_JUDGE_EXTRA_BODY": json.dumps(extra)}, clear=True):
                with self.assertRaises(ev.EvalError):
                    ev.ClientConfig.from_env()
        with patch.dict(os.environ, {**env, "ANSWER_EVAL_JUDGE_BASE_URL": "https://user:secret@fixture.invalid"}, clear=True):
            with self.assertRaises(ev.EvalError):
                ev.ClientConfig.from_env()

    def test_truncation_refusal_and_tool_calls_not_success(self):
        config = ev.ClientConfig("https://fixture.invalid/v1", "private-key", "fixture")
        for choice in ({"finish_reason": "length", "message": {"content": "{}"}},
                       {"finish_reason": "stop", "message": {"refusal": "no", "content": "{}"}},
                       {"finish_reason": "stop", "message": {"content": "{}", "tool_calls": [{}]}}):
            with patch.object(ev.pe, "request_json", return_value=({"choices": [choice]}, 1)):
                with self.assertRaises(ev.EvalError):
                    config.call([], ev.judgment_schema(fixture()))

    def test_transport_retry_preserves_schema_and_usage(self):
        config = ev.ClientConfig("https://fixture.invalid/v1", "private-key", "fixture", retries=1)
        response = {"model": "returned-fixture", "choices": [{"finish_reason": "stop", "message": {"content": "{}"}}]}
        with patch.object(ev.pe, "request_json", side_effect=[ev.pe.TransportError("offline"), (response, 1)]) as request, patch.object(ev.time, "sleep"):
            content, metadata = config.call([], ev.judgment_schema(fixture()))
        self.assertEqual(1, metadata["transport_retries"])
        self.assertEqual("returned-fixture", metadata["returned_model"])
        self.assertTrue(request.call_args.args[1]["response_format"]["json_schema"]["strict"])

    def test_fixture_generation_uses_actual_template_and_saves_visible_facts(self):
        config = ev.ClientConfig("https://fixture.invalid/v1", "private-key", "tested-answer", response_format="off")
        response = {"model": "tested-answer", "choices": [{"finish_reason": "stop", "message": {"content": "answer"}}]}
        with patch.object(ev.pe, "request_json", return_value=(response, 1)) as request:
            r = ev.generate_case(fixture(), config)
        self.assertNotIn("response_format", request.call_args.args[1])
        sample = ev.extract_answer(r["trace"], fixture()["question"])
        self.assertEqual(fixture()["fixture"]["facts"], sample["facts"])
        self.assertEqual("tested-answer", sample["model"])
        self.assertEqual("controlled_fixture", r["source_kind"])

    def test_generation_failure_preserves_input_but_is_answer_error(self):
        config = ev.ClientConfig("https://fixture.invalid/v1", "private-key", "fixture", response_format="off")
        with patch.object(ev.pe, "request_json", side_effect=ev.pe.TransportError("offline")):
            r = ev.generate_case(fixture(), config)
        self.assertIn("generation_error", r)
        self.assertEqual("ANSWER_ERROR", ev.grade_record(r, FakeJudge(), "rubric")["verdict"])
        self.assertTrue(r["trace"]["events"][0]["input_json"]["messages"])

    def test_calibration_labels_not_leaked_into_judge_messages(self):
        cases = ev.validate_cases(ev.pe.read_json(ev.CASES))
        records = ev.calibration_records(cases, ev.CALIBRATION)
        self.assertEqual({"PASS", "FAIL"}, {r["expected_verdict"] for r in records})
        r = records[0]
        messages = ev.judge_messages(r["case"], ev.extract_answer(r["trace"], r["case"]["question"]), "rubric")
        self.assertNotIn("expected_verdict", messages[1]["content"])
        self.assertNotIn("label_reason", messages[1]["content"])

    def test_report_keeps_errors_and_flags_changed_samples_and_missing_cases(self):
        score = ev.grade_record(record(), FakeJudge(), "rubric")
        old = {**score, "verdict": "FAIL", "answer_hash": "old-answer"}
        missing = {**old, "case_id": "missing"}
        with tempfile.TemporaryDirectory() as tmp:
            directory = ev.create_run(Path(tmp), "report", [fixture()])
            ev.write_report(directory, [score], [old, missing])
            text = (directory / "report.md").read_text()
            self.assertIn("FAIL → PASS 独立复核清单：AF010", text)
            self.assertIn("MISSING", text)
            self.assertIn("True | False", text)
            manifest = ev.pe.read_json(directory / "manifest.json")
            self.assertEqual("answer", manifest["eval_kind"])
            self.assertEqual({"fixture": 1}, manifest["coverage"]["modes"])

    def test_import_planner_is_offline_and_keeps_only_matching_inputs(self):
        c = next(c for c in ev.validate_cases(ev.pe.read_json(ev.CASES)) if c["id"] == "A001")
        source = {"format_version": 1, "case": {**c, "id": "P001"}, "trace": {"trace": {"question": c["question"]}, "events": []}}
        source["case_hash"] = ev.pe.digest(source["case"])
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            path = Path(tmp) / "planner.jsonl"; path.write_text(json.dumps(source) + "\n")
            self.assertEqual(0, ev.main(["import-planner", str(path), "--only", "A001", "--output-root", str(Path(tmp) / "runs")]))
            raws = list((Path(tmp) / "runs").glob("*/raw.jsonl"))
            row = ev.pe.read_jsonl(raws[0])[0]
            self.assertEqual("A001", row["case"]["id"])
            self.assertEqual("P001", row["source_case_id"])
            self.assertEqual("NOT_APPLICABLE", ev.grade_record(row, FakeJudge(), "rubric")["verdict"])

    def test_judge_relabel_cannot_replace_fixture_facts(self):
        r = record(); changed = copy.deepcopy(r["case"]); changed["fixture"]["facts"][0]["data"]["value"] = 999
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp) / "raw.jsonl"; raw.write_text(json.dumps(r) + "\n")
            cases = Path(tmp) / "cases.json"; cases.write_text(json.dumps({"format_version": 1, "cases": [changed]}))
            with self.assertRaises(ev.EvalError):
                ev.main(["judge", str(raw), "--cases", str(cases)])

    def test_run_fixtures_writes_raw_scores_and_frozen_prompt_offline(self):
        response = {"model": "fixture-answer", "choices": [{"finish_reason": "stop",
                    "message": {"content": "币安BTC最新成交价120 USDT。"}}]}
        model = ev.ClientConfig("https://fixture.invalid/v1", "private-key", "fixture-answer", response_format="off")
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()), \
                patch.object(ev.ClientConfig, "from_env", side_effect=[FakeJudge(), model]), \
                patch.object(ev.pe, "request_json", return_value=(response, 1)):
            self.assertEqual(0, ev.main(["run-fixtures", "--only", "AF010", "--output-root", tmp]))
            run = next(p for p in Path(tmp).iterdir() if p.is_dir())
            self.assertEqual("PASS", ev.pe.read_jsonl(run / "scores.jsonl")[0]["verdict"])
            self.assertTrue((run / "answer_prompt.txt").exists())
            self.assertTrue((run / "judge_prompt.txt").exists())
            self.assertEqual("controlled_fixture", ev.pe.read_jsonl(run / "raw.jsonl")[0]["source_kind"])

    def test_calibration_catches_judge_that_marks_every_answer_PASS(self):
        class AlwaysPass(FakeJudge):
            def call(self, messages, schema=None):
                payload = json.loads(messages[1]["content"].split("\n", 1)[1])
                obj = {"criteria": [{"id": c["id"], "verdict": "PASS", "reason": "fixture",
                                     "evidence": ["/facts"]} for c in payload["criteria"]],
                       "claims": [{"quote": payload["answer"], "verdict": "SUPPORTED", "reason": "fixture",
                                   "source_refs": ["/facts"]}]}
                return json.dumps(obj), {}
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()), \
                patch.object(ev.ClientConfig, "from_env", return_value=AlwaysPass()):
            self.assertEqual(1, ev.main(["calibrate", "--output-root", tmp]))
            path = next(Path(tmp).glob("*/scores.jsonl"))
            rows = ev.pe.read_jsonl(path)
            self.assertEqual(8, sum(r["calibration_passed"] for r in rows))
            self.assertEqual(16, len(rows))

    def test_generate_config_uses_model_key_without_double_suffix(self):
        env = {"ANSWER_EVAL_MODEL_BASE_URL": "https://fixture.invalid/v1",
               "ANSWER_EVAL_MODEL_API_KEY": "private-key", "ANSWER_EVAL_MODEL": "tested-answer"}
        with patch.dict(os.environ, env, clear=True):
            config = ev.ClientConfig.from_env("MODEL")
        self.assertEqual("tested-answer", config.model)
        self.assertEqual("off", config.response_format)


if __name__ == "__main__":
    unittest.main()
