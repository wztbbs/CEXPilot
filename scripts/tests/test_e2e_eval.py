"""Offline conversation eval tests: fake traces/judges, all real HTTP forbidden."""
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
import e2e_eval as ev


def case(cid="E008"):
    return copy.deepcopy(next(c for c in ev.validate_cases(ev.pe.read_json(ev.CASES)) if c["id"] == cid))


def turn(c, index=1, reply=None):
    spec = c["turns"][index - 1]
    answer_call = spec["outcome"] in {"answer", "partial"}
    text = reply or ("本轮价格为100 USDT。" if answer_call else "请补充币种。")
    context = {"timezone": c["timezone"], "request_time_local": "2026-10-13T12:00:00+08:00"}
    facts = [{"node_id": "metric_0", "ok": True, "identity": {"metric": "price.last"},
              "data": {"value": 100, "as_of": "2026-10-13 12:00:00"}}]
    envelope = {"in_domain": spec.get("expected_fields", {}).get("in_domain", True), "plan": None, "reply": text}
    if answer_call:
        envelope["plan"] = {"metrics": [{"id": "m1", "metric": "price.last"}], "calculations": []}
    if spec["outcome"] == "clarify":
        envelope.update(needs_clarification=True, clarify=text)
    events = [
        {"seq": 1, "event_type": "LLM_CALL", "name": "dag_planner", "input_json": {
            "messages": [{"role": "system", "content": "fixture catalog; 缺信息追问; 只支持行情"},
                         {"role": "user", "content": spec["question"]}]},
         "output_json": {"content": json.dumps(envelope)}, "error": None},
        {"seq": 2, "event_type": "PLAN", "name": "dag_planner", "output_json": envelope, "error": None},
    ]
    counts = {k: int(spec.get("execution", {}).get(k) == "required") for k in ("fetch", "calculation", "reuse")}
    for kind, event_type, name in (("fetch", "METRIC_RESULT", "price.last"),
                                    ("calculation", "TOOL_CALL", "avg"), ("reuse", "FACT_REUSED", "price.last")):
        if counts[kind]:
            events.append({"seq": len(events) + 1, "event_type": event_type, "name": name,
                           "input_json": {}, "output_json": {"ok": True, "data": {"value": 100}}})
    if answer_call:
        system = "<TIME_CONTEXT>\n" + json.dumps(context) + "\n</TIME_CONTEXT>\n<CONVERSATION_CONTEXT>\n</CONVERSATION_CONTEXT>"
        user = spec["question"] + "\n\n<FACTS>\n" + json.dumps(facts) + "\n</FACTS>\n<QUERY_STATUS>\n{}\n</QUERY_STATUS>"
        events.append({"seq": len(events) + 1, "event_type": "LLM_CALL", "name": "answer", "error": None,
                       "input_json": {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                                      "time_context": context},
                       "output_json": {"content": text}})
    metadata = {"trace_id": f"trace-{index}", "conversation_id": "conversation-1", "question": spec["question"],
                "status": "SUCCESS", "metric_fetch_count": counts["fetch"], "calculation_count": counts["calculation"],
                "reused_facts": counts["reuse"]}
    return {"index": index, "question": spec["question"], "wall_seconds": 1,
            "response": {"traceId": metadata["trace_id"], "conversationId": metadata["conversation_id"], "answer": text},
            "trace": {"trace": metadata, "events": events}}


def record(c=None):
    c = c or case()
    return {"format_version": 1, "eval_kind": "e2e", "case": c, "case_hash": ev.pe.digest(c),
            "turns": [turn(c, i) for i in range(1, len(c["turns"]) + 1)]}


class Judge:
    model = "fake-judge"
    api_key = "fake-api-secret"

    def __init__(self, statuses=None, invalid=0, fail_call=False):
        self.calls = []
        self.statuses = statuses or []
        self.invalid = invalid
        self.fail_call = fail_call

    def public(self):
        return {"model": self.model, "base_url": "https://fixture.invalid/v1"}

    def call(self, messages, schema):
        self.calls.append((messages, schema))
        if self.fail_call:
            raise ev.EvalError("HTTP 503")
        payload = json.loads(messages[1]["content"].split("\n", 1)[1].split("\n上一次输出", 1)[0])
        index = len(payload["turns"]) - 1
        current = payload["turns"][-1]
        status = self.statuses[len(self.calls) - 1] if len(self.calls) <= len(self.statuses) else "PASS"
        criteria = [{"id": c["id"], "verdict": status, "reason": "fixture reasoning",
                     "evidence": [f"/turns/{index}/question"]} for c in payload["criteria"]]
        claim = {"quote": current["reply"], "verdict": "SUPPORTED", "reason": "fixture evidence",
                 "source_refs": [f"/turns/{index}/question"]}
        if len(self.calls) <= self.invalid:
            claim["quote"] = "原文不存在的断言"
        return json.dumps({"criteria": criteria, "claims": [claim]}), {"usage": {"completion_tokens": 12}}


class E2EEvalTests(unittest.TestCase):
    def setUp(self):
        blocker = patch.object(ev.pe.urllib.request, "build_opener", side_effect=AssertionError("real HTTP forbidden"))
        blocker.start()
        self.addCleanup(blocker.stop)

    def test_suite_has_25_scenarios_45_turns_and_agreed_categories(self):
        cases = ev.validate_cases(ev.pe.read_json(ev.CASES))
        summary = ev.coverage(cases)
        self.assertEqual((25, 45), (summary["scenarios"], summary["turns"]))
        self.assertEqual({"answer": 7, "clarification": 4, "unsupported": 3, "partial": 2,
                          "out_of_domain": 2, "help": 1, "multiturn": 6}, summary["categories"])
        self.assertEqual(3, len(case("E025")["turns"]))

    def test_invalid_cases_and_unknown_selection_fail_closed(self):
        for edit in (lambda c: c["turns"][0].update(criteria=[]),
                     lambda c: c["turns"][0].update(domain="unknown"),
                     lambda c: c["turns"][0].update(expected_fields={"in_domain": False}),
                     lambda c: c["turns"][0]["criteria"].append(c["turns"][0]["criteria"][0]),
                     lambda c: c.update(timezone="Not/AZone")):
            c = case()
            edit(c)
            with self.assertRaises(ev.EvalError):
                ev.validate_cases({"format_version": 1, "cases": [c]})
        with self.assertRaises(ev.EvalError):
            ev.select([case()], "E999")

    def test_list_and_validate_are_offline(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, ev.main(["validate", "--only", "E025"]))

    def test_collect_preserves_conversation_and_each_turn_checkpoint(self):
        c = case()
        data = record(c)["turns"]
        checkpoints = []
        calls = []
        def request(url, payload=None, **kwargs):
            calls.append((url, payload))
            if payload is not None:
                return data[(len(calls) - 1) // 2]["response"], 1
            return data[(len(calls) - 1) // 2]["trace"], 1
        with patch.object(ev.pe, "request_json", side_effect=request):
            r = ev.collect_case(c, "https://fixture.invalid", 17, checkpoints.append)
        self.assertNotIn("conversationId", calls[0][1])
        self.assertEqual("conversation-1", calls[2][1]["conversationId"])
        self.assertEqual(2, len(r["turns"]))
        self.assertEqual(2, len(checkpoints))
        r["turns"][0]["response"]["answer"] = "changed"
        self.assertNotEqual("changed", checkpoints[0]["turn"]["response"]["answer"])

    def test_collection_failure_keeps_response_and_stops_without_retry(self):
        c = case()
        response = turn(c)["response"]
        with patch.object(ev.pe, "request_json", side_effect=[(response, 1), ev.EvalError("HTTP 500")]) as call:
            r = ev.collect_case(c, "https://fixture.invalid", 1)
        self.assertEqual(2, call.call_count)
        self.assertEqual(response, r["turns"][0]["response"])
        judge = Judge()
        scored = ev.grade_record(r, judge, "rubric")
        self.assertEqual(["COLLECTION_ERROR", "NOT_COLLECTED"], [t["verdict"] for t in scored["turns"]])
        self.assertFalse(judge.calls)

    def test_malformed_api_response_is_retained_as_collection_error(self):
        with patch.object(ev.pe, "request_json", return_value=({"answer": "no ids"}, 1)):
            r = ev.collect_case(case(), "https://fixture.invalid", 1)
        self.assertEqual("COLLECTION_ERROR", ev.grade_record(r, Judge(), "rubric")["verdict"])

    def test_wrong_conversation_trace_or_question_cannot_be_stitched(self):
        for edit in (lambda r: r["turns"][1]["response"].update(conversationId="other"),
                     lambda r: r["turns"][1]["trace"]["trace"].update(trace_id="other"),
                     lambda r: r["turns"][1].update(question="foreign question"),
                     lambda r: r["turns"][1]["response"].update(traceId="trace-1")):
            r = record()
            edit(r)
            self.assertEqual("NOT_EVALUABLE", ev.grade_record(r, Judge(), "rubric")["verdict"])

    def test_interruption_persists_partial_raw_and_returns_130(self):
        c = case()
        response = turn(c)["response"]
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            with patch.object(ev.pe, "request_json", side_effect=[(response, 1), KeyboardInterrupt()]):
                self.assertEqual(130, ev.main(["collect", "--only", "E008", "--target-url", "https://fixture.invalid",
                                              "--output-root", tmp]))
            directory = next(Path(tmp).glob("*/raw.jsonl")).parent
            self.assertTrue(ev.pe.read_jsonl(directory / "raw.jsonl")[0]["interrupted"])
            self.assertTrue((directory / "turns.jsonl").exists())

    def test_planner_only_clarification_out_of_domain_and_help_are_scored(self):
        for cid in ("E011", "E016", "E018"):
            c = case(cid)
            judge = Judge()
            self.assertEqual("PASS", ev.grade_record(record(c), judge, "rubric")["verdict"])
            self.assertEqual(1, len(judge.calls))
        c = case()
        judge = Judge()
        result = ev.grade_record(record(c), judge, "rubric")
        self.assertEqual("PASS", result["turns"][0]["verdict"])
        self.assertEqual(2, len(judge.calls))

    def test_first_failure_not_erased_by_last_success_and_not_retried(self):
        judge = Judge(["FAIL", "PASS"])
        result = ev.grade_record(record(), judge, "rubric", 3)
        self.assertEqual(["FAIL", "PASS"], [t["verdict"] for t in result["turns"]])
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual(2, len(judge.calls))

    def test_hard_fetch_and_domain_failures_cannot_be_overridden(self):
        for mutate in (lambda r: r["turns"][0]["trace"]["trace"].update(metric_fetch_count=1),
                       lambda r: r["turns"][0]["trace"]["events"][1]["output_json"].update(in_domain=False)):
            r = record()
            mutate(r)
            result = ev.grade_record(r, Judge(), "rubric")
            self.assertEqual("FAIL", result["turns"][0]["verdict"])

    def test_missing_prompt_blocks_unverifiable_followups(self):
        r = record()
        r["turns"][0]["trace"]["events"][0]["input_json"] = {"message_count": 2}
        judge = Judge()
        result = ev.grade_record(r, judge, "rubric")
        self.assertEqual(["NOT_EVALUABLE", "NOT_EVALUABLE"], [t["verdict"] for t in result["turns"]])
        self.assertFalse(judge.calls)

    def test_actual_answer_facts_and_current_api_reply_are_judge_inputs(self):
        r = record(case("E001"))
        r["turns"][0]["response"]["answer"] = "实际交付的价格100 USDT。"
        r["turns"][0]["response"]["evidence"] = [{"value": 999999}]
        judge = Judge()
        ev.grade_record(r, judge, "rubric")
        payload = json.loads(judge.calls[0][0][1]["content"].split("\n", 1)[1])
        self.assertEqual(100, payload["turns"][0]["answer_input"]["facts"][0]["data"]["value"])
        self.assertNotIn("999999", json.dumps(payload))
        self.assertEqual("实际交付的价格100 USDT。", payload["turns"][0]["reply"])

    def test_future_questions_and_labels_are_not_sent_to_earlier_judgment(self):
        judge = Judge()
        ev.grade_record(record(), judge, "rubric")
        first = json.loads(judge.calls[0][0][1]["content"].split("\n", 1)[1])
        second = json.loads(judge.calls[1][0][1]["content"].split("\n", 1)[1])
        self.assertEqual(1, len(first["turns"]))
        self.assertTrue(all(".T1." in c["id"] for c in first["criteria"]))
        self.assertNotIn("attempts", second["turns"][0]["planner"])
        self.assertIn("attempts", second["turns"][1]["planner"])

    def test_pointer_rejects_answer_self_future_missing_and_bad_escape(self):
        sample = ev.extract_turn(turn(case("E001")))
        payload = ev.judge_payload(case("E001"), case("E001")["turns"][0], [sample])
        self.assertEqual(100, ev.source_pointer(payload, "/turns/0/answer_input/facts/0/data/value"))
        for path in ("/turns/0/reply", "/turns/1/question", "/turns/0/answer_input/facts/0/data/missing",
                     "/turns/0/question~2", "/turns/00/question", "/answer"):
            with self.assertRaises(ev.EvalError):
                ev.source_pointer(payload, path)

    def test_invalid_output_retried_with_feedback_but_not_transport(self):
        judge = Judge(invalid=1)
        r = ev.grade_record(record(case("E001")), judge, "rubric")
        self.assertEqual("PASS", r["verdict"])
        self.assertEqual(2, len(judge.calls))
        self.assertIn("上一次输出不合法", judge.calls[1][0][1]["content"])
        judge = Judge(fail_call=True)
        r = ev.grade_record(record(case("E001")), judge, "rubric", 4)
        self.assertEqual("JUDGE_ERROR", r["verdict"])
        self.assertEqual(1, len(judge.calls))

    def test_invalid_output_exhaustion_is_not_pass(self):
        r = ev.grade_record(record(case("E001")), Judge(invalid=5), "rubric", 1)
        self.assertEqual("JUDGE_ERROR", r["verdict"])
        self.assertEqual(2, len(r["turns"][0]["judge_attempts"]))

    def test_partial_conversation_is_incomplete_not_pass(self):
        r = record()
        r["turns"] = r["turns"][:1]
        result = ev.grade_record(r, Judge(), "rubric")
        self.assertEqual("INCOMPLETE", result["verdict"])
        self.assertEqual("NOT_COLLECTED", result["turns"][1]["verdict"])
        self.assertIsNone(result["input_hash"])

    def test_answer_error_maps_to_system_error_and_unknown_state_fails_closed(self):
        r = record(case("E001"))
        r["turns"][0]["trace"]["events"][-1]["error"] = "answer failed"
        self.assertEqual("SYSTEM_ERROR", ev.grade_record(r, Judge(), "rubric")["verdict"])
        with self.assertRaises(ev.EvalError):
            ev.combine(["ANSWER_ERROR"])

    def test_relabel_keeps_questions_timezone_and_frozen_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, labels = Path(tmp) / "raw.jsonl", Path(tmp) / "cases.json"
            r = record()
            ev.pe.append_jsonl(source, r)
            changed = copy.deepcopy(r["case"])
            changed["turns"][0]["criteria"][0]["expectation"] = "reviewed expectation"
            labels.write_text(json.dumps({"format_version": 1, "cases": [changed]}))
            revised = ev.load_records(source, replacements=labels)
            self.assertEqual(r["case_hash"], revised[0]["previous_case_hash"])
            self.assertNotEqual(r["case_hash"], revised[0]["case_hash"])
            changed["turns"][0]["question"] = "changed query"
            labels.write_text(json.dumps({"format_version": 1, "cases": [changed]}))
            with self.assertRaises(ev.EvalError):
                ev.load_records(source, replacements=labels)
            self.assertEqual(r, ev.pe.read_jsonl(source)[0])

    def test_run_backup_secrets_and_per_turn_reporting_with_fake_network(self):
        c = case()
        turns = record(c)["turns"]
        def request(url, payload=None, **kwargs):
            if payload is not None:
                index = 0 if "conversationId" not in payload else 1
                return copy.deepcopy(turns[index]["response"]), 1
            index = int(url.rsplit("-", 1)[1]) - 1
            return copy.deepcopy(turns[index]["trace"]), 1
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            for _ in range(2):
                with patch.object(ev.pe, "request_json", side_effect=request), patch.object(ev, "judge_config", return_value=Judge()):
                    self.assertEqual(0, ev.main(["run", "--only", "E008", "--target-url", "https://fixture.invalid",
                                                 "--output-root", tmp]))
            scores = list(Path(tmp).glob("*/scores.jsonl"))
            self.assertEqual(2, len(scores))
            self.assertEqual(1, len(list((Path(tmp) / "_backups").glob("*/raw.jsonl"))))
            for score in scores:
                rows = ev.validate_scores(ev.pe.read_jsonl(score))
                self.assertEqual(2, len(rows[0]["turns"]))
                manifest = (score.parent / "manifest.json").read_text()
                self.assertNotIn(Judge.api_key, manifest)
                report = (score.parent / "report.md").read_text()
                self.assertIn("E008/1", report)
                self.assertIn("E008/2", report)

    def test_judge_config_fallback_and_partial_credentials(self):
        base = {"PLANNER_EVAL_JUDGE_BASE_URL": "https://fixture.invalid/v1",
                "PLANNER_EVAL_JUDGE_API_KEY": "fake-secret", "PLANNER_EVAL_JUDGE_MODEL": "fake-model"}
        with patch.dict(os.environ, base, clear=True):
            self.assertEqual("fake-model", ev.judge_config().model)
        with patch.dict(os.environ, {**base, "E2E_EVAL_JUDGE_MODEL": "other"}, clear=True):
            with self.assertRaises(ev.EvalError):
                ev.judge_config()
        own = {"E2E_EVAL_JUDGE_" + k: v for k, v in
               (("BASE_URL", "https://fixture.invalid/v1"), ("API_KEY", "fake-secret"), ("MODEL", "own"))}
        with patch.dict(os.environ, own, clear=True):
            self.assertEqual("own", ev.judge_config().model)
            self.assertNotIn("fake-secret", repr(ev.judge_config()))

    def test_configuration_invalid_before_online_collection(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(ev, "judge_config", side_effect=ev.EvalError("missing config")):
            with self.assertRaises(ev.EvalError):
                ev.main(["run", "--only", "E001", "--target-url", "https://fixture.invalid", "--output-root", tmp])
            self.assertEqual([], list(Path(tmp).iterdir()))

    def test_import_session_and_report_are_offline(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            session = Path(tmp) / "session.json"
            session.write_text(json.dumps({"turns": record()["turns"]}))
            output = Path(tmp) / "output"
            self.assertEqual(0, ev.main(["import-session", str(session), "--only", "E008", "--output-root", str(output)]))
            raw = next(output.glob("*/raw.jsonl"))
            self.assertEqual("imported_session", ev.pe.read_jsonl(raw)[0]["source_kind"])

    def test_comparison_separates_grader_changes_from_live_input_changes(self):
        a = ev.grade_record(record(), Judge(), "rubric")
        b = copy.deepcopy(a)
        self.assertEqual({"same_config": True, "same_sample": True}, ev.comparison(a, b))
        b["reply_hash"] = "changed"
        self.assertEqual({"same_config": True, "same_sample": False}, ev.comparison(a, b))
        b["judge_prompt_hash"] = "changed"
        self.assertFalse(ev.comparison(a, b)["same_config"])

    def test_missing_duplicate_criteria_empty_claims_and_bad_sources_are_rejected(self):
        c = case("E001")
        spec = c["turns"][0]
        payload = ev.judge_payload(c, spec, [ev.extract_turn(turn(c))])
        good = {"criteria": [{"id": x["id"], "verdict": "PASS", "reason": "fixture",
                              "evidence": ["/turns/0/question"]} for x in spec["criteria"]],
                "claims": [{"quote": payload["turns"][0]["reply"], "verdict": "SUPPORTED",
                            "reason": "fixture", "source_refs": ["/turns/0/answer_input/facts/0/data/value"]}]}
        edits = [lambda j: j["criteria"].pop(),
                 lambda j: j["criteria"].append(copy.deepcopy(j["criteria"][0])),
                 lambda j: j.update(claims=[]),
                 lambda j: j["claims"][0].update(source_refs=[]),
                 lambda j: j["claims"][0].update(source_refs=["/turns/0/reply"]),
                 lambda j: j["claims"][0].update(quote="历史轮中的一句话"),
                 lambda j: j["criteria"][0].update(evidence="not an array")]
        for edit in edits:
            bad = copy.deepcopy(good)
            edit(bad)
            with self.assertRaises(ev.EvalError):
                ev.validate_judgment(bad, spec, payload)

    def test_judging_interruption_keeps_completed_turn_checkpoint(self):
        judge = Judge()
        original = judge.call
        def call(messages, schema):
            if judge.calls:
                raise KeyboardInterrupt()
            return original(messages, schema)
        checkpoints = []
        with patch.object(judge, "call", side_effect=call), self.assertRaises(KeyboardInterrupt):
            ev.grade_record(record(), judge, "rubric", checkpoint=checkpoints.append)
        self.assertEqual(1, len(checkpoints))
        self.assertEqual("PASS", checkpoints[0]["turn"]["verdict"])
        self.assertEqual(1, checkpoints[0]["turn"]["index"])

    def test_report_rejects_inconsistent_aggregate_or_other_suite(self):
        row = ev.grade_record(record(), Judge(["FAIL", "PASS"]), "rubric")
        bad = copy.deepcopy(row)
        bad["verdict"] = "PASS"
        with self.assertRaises(ev.EvalError):
            ev.validate_scores([bad])
        bad = copy.deepcopy(row)
        bad["eval_kind"] = "planner"
        with self.assertRaises(ev.EvalError):
            ev.validate_scores([bad])
        ev.validate_scores([row])

    def test_valid_unsupported_claim_causes_failure_without_output_retry(self):
        class Unsupported(Judge):
            def call(self, messages, schema):
                content, meta = super().call(messages, schema)
                obj = json.loads(content)
                obj["claims"][0].update(verdict="UNSUPPORTED", source_refs=[])
                return json.dumps(obj), meta
        judge = Unsupported()
        result = ev.grade_record(record(case("E001")), judge, "rubric", 3)
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual(1, len(judge.calls))


if __name__ == "__main__":
    unittest.main()
