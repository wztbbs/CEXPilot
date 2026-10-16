"""Offline evaluator checks: fixtures and fake judges only, no API requests."""

import copy
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import planner_eval as ev


def case():
    return {"id": "TEST", "title": "Fixture", "question": "币安 BTC 现价？",
            "timezone": "Asia/Shanghai", "setup_questions": [],
            "criteria": [{"id": "metric", "dimension": "metric_shape", "expectation": "成交价快照"}],
            "expected_fields": {"in_domain": True}}


def plan():
    return {"in_domain": True, "reply": None,
            "plan": {"metrics": [{"id": "m1", "metric": "price.last", "exchanges": ["binance"],
                                    "instrument": {"market_type": "perpetual", "base": "BTC", "quote": "USDT"},
                                    "query_shape": "snapshot"}], "calculations": []}}


def event(seq=1, content=None, attempt=1, messages=True):
    inputs = {"stage": "dag_planner", "attempt": attempt}
    if messages:
        inputs["messages"] = [{"role": "system", "content": "fixture catalog"},
                              {"role": "user", "content": case()["question"]}]
    return {"seq": seq, "event_type": "LLM_CALL", "name": "dag_planner",
            "input_json": json.dumps(inputs),
            "output_json": json.dumps({"content": content if content is not None else json.dumps(plan())}),
            "error": None}


def trace(events=None):
    return {"trace": {"trace_id": "trace-fixture", "question": case()["question"], "model": "qwen-fixture"},
            "events": events if events is not None else [event()]}


def judgment(verdict="PASS"):
    return {"criteria": [{"id": "metric", "verdict": verdict, "reason": "fixture reason",
                          "evidence": ["planner_output.plan.metrics[0].metric=price.last"]}]}


class FakeJudge:
    model = "judge-fixture"
    api_key = "secret-fixture-key"

    def __init__(self, reply=None):
        self.reply = reply if reply is not None else judgment()
        self.calls = []

    def public(self):
        return {"model": self.model, "base_url": "https://judge.invalid/v1"}

    def call(self, messages):
        self.calls.append(messages)
        return json.dumps(self.reply), {"usage": {"completion_tokens": 10}}


class PlannerEvalTests(unittest.TestCase):
    def setUp(self):
        # Any accidental HTTP access is a test failure, never a real request.
        blocker = patch.object(ev.urllib.request, "build_opener", side_effect=AssertionError("Network forbidden in offline tests"))
        blocker.start()
        self.addCleanup(blocker.stop)

    def test_seed_suite_has_all_dimensions_shapes_times_and_operators(self):
        _, cases = ev.load_cases()
        summary = ev.coverage(cases)
        self.assertEqual([], summary["missing_dimensions"])
        features = set(summary["features"])
        self.assertTrue({"shape:" + s for s in ("snapshot", "official_24h", "range_statistic", "time_series", "recent_n")} <= features)
        self.assertTrue({"time:" + s for s in ("calendar_period", "rolling_window", "relative_day_range", "absolute_range")} <= features)
        self.assertTrue({"operator:" + op for op in ("avg", "sum", "min", "max", "compare", "difference", "ratio", "relative_change", "annualize")} <= features)

    def test_no_empty_cases_or_criteria_and_no_duplicate_ids(self):
        for bad in ({"format_version": 1, "cases": []},
                    {"format_version": 1, "cases": [{**case(), "criteria": []}]},
                    {"format_version": 1, "cases": [case(), case()]}):
            with self.assertRaises(ev.EvalError):
                ev.validate_suite(bad)

    def test_unknown_selection_is_an_error(self):
        with self.assertRaises(ev.EvalError):
            ev.load_cases(only="P999")

    def test_complete_json_only_no_greedy_recovery(self):
        self.assertEqual(plan(), ev.parse_model_object("```json\n" + json.dumps(plan()) + "\n```"))
        for bad in ('{"in_domain":true', 'prefix ' + json.dumps(plan()), '{} {}',
                    '{"plan":null,"plan":{}}', '{"x":NaN}', '[]'):
            with self.assertRaises(ev.EvalError):
                ev.parse_model_object(bad)

    def test_legacy_nodes_is_never_a_valid_or_null_logical_plan(self):
        old = {"in_domain": True, "reply": None, "plan": {"nodes": []}}
        self.assertTrue(ev.protocol_errors(old))

    def test_envelope_flags_must_be_booleans(self):
        for key in ("in_domain", "new_frame", "needs_clarification"):
            output = plan()
            output[key] = "false"
            self.assertTrue(ev.protocol_errors(output))

    def test_null_plan_needs_reason_and_clarification_cannot_execute(self):
        self.assertTrue(ev.protocol_errors({"in_domain": True, "reply": None, "plan": None}))
        self.assertFalse(ev.protocol_errors({"in_domain": True, "reply": None, "plan": None,
                                             "needs_clarification": True, "clarify": "哪个币种？"}))
        output = plan()
        output["needs_clarification"] = True
        self.assertTrue(ev.protocol_errors(output))

    def test_duplicate_node_and_missing_reference_branch(self):
        output = plan()
        output["plan"]["metrics"].append(copy.deepcopy(output["plan"]["metrics"][0]))
        self.assertTrue(ev.protocol_errors(output))
        output = plan()
        output["plan"]["calculations"] = [{"id": "c1", "operator": "ratio",
                                             "input": {"left": "{{m1.okx.value}}", "right": 100}}]
        self.assertTrue(any("分支" in s for s in ev.protocol_errors(output)))
        output["plan"]["calculations"][0]["input"]["left"] = "{{m1.binance.value}} * 100"
        self.assertTrue(any("完整字段路径" in s for s in ev.protocol_errors(output)))

    def test_recent_n_cannot_have_null_time_or_bool_count(self):
        output = plan()
        metric = output["plan"]["metrics"][0]
        metric.update(metric="funding.rate_settled", query_shape="recent_n", count=10)
        self.assertFalse(ev.protocol_errors(output))
        metric["time"] = None
        self.assertTrue(ev.protocol_errors(output))
        del metric["time"]
        metric["count"] = True
        self.assertTrue(ev.protocol_errors(output))

    def test_defaults_are_semantically_equal_for_optional_flags(self):
        sample = case()
        sample["expected_fields"] = {"in_domain": True, "new_frame": False, "task_action": "CONTINUE"}
        self.assertTrue(all(c["verdict"] == "PASS" for c in ev.field_checks(sample, plan())))

    def test_trace_reads_both_encoded_and_object_json_and_sorts_attempts(self):
        first = event(1)
        final = event(3, attempt=2)
        final["input_json"] = json.loads(final["input_json"])
        final["output_json"] = json.loads(final["output_json"])
        observed = ev.extract_planner(trace([final, first]), case()["question"])
        self.assertEqual([1, 2], [a["attempt"] for a in observed["attempts"]])
        self.assertIsNone(observed["server_accepted"])

    def test_missing_prompt_is_not_evaluable_and_never_calls_judge(self):
        observed = ev.extract_planner(trace([event(messages=False)]), case()["question"])
        judge = FakeJudge()
        result = ev.grade_record({"case": case(), "planner": observed}, judge, "rubric")
        self.assertEqual("NOT_EVALUABLE", result["verdict"])
        self.assertEqual([], judge.calls)

    def test_wrong_trace_question_rejected(self):
        data = trace()
        data["trace"]["question"] = "other question"
        with self.assertRaises(ev.EvalError):
            ev.extract_planner(data, case()["question"])

    def test_first_and_final_are_scored_separately_after_repair(self):
        observed = ev.extract_planner(trace([event(1, "broken JSON"), event(3, attempt=2)]), case()["question"])
        judge = FakeJudge()
        result = ev.grade_record({"case": case(), "planner": observed}, judge, "rubric")
        self.assertEqual("FAIL", result["stages"]["first"]["verdict"])
        self.assertEqual("PASS", result["stages"]["final"]["verdict"])
        self.assertEqual(1, result["repair_calls"])
        self.assertEqual(1, len(judge.calls))

    def test_attempt_model_identity_distinguishes_thinking_variants(self):
        # 首排/修复轮同为 deepseek-v4.1-flash、仅思考配置不同时，标签必须可区分；
        # 旧 trace（无模型字段）标签为 None，报告照常渲染。
        first = event(1)
        repair_inputs = {"stage": "dag_planner", "attempt": 2,
                         "model": "deepseek-v4.1-flash", "model_role": "flagship",
                         "enable_thinking": True, "reasoning_effort": "low",
                         "messages": [{"role": "system", "content": "fixture catalog"},
                                      {"role": "user", "content": case()["question"]}]}
        final = {"seq": 3, "event_type": "LLM_CALL", "name": "dag_planner",
                 "input_json": json.dumps(repair_inputs),
                 "output_json": json.dumps({"content": json.dumps(plan())}), "error": None}
        observed = ev.extract_planner(trace([first, final]), case()["question"])
        self.assertIsNone(observed["attempts"][0]["model"])
        self.assertIsNone(ev.attempt_label(observed["attempts"][0]))
        self.assertEqual("flagship", observed["attempts"][1]["model_role"])
        self.assertEqual("deepseek-v4.1-flash [flagship, thinking=on, effort=low]",
                         ev.attempt_label(observed["attempts"][1]))
        result = ev.grade_record({"case": case(), "planner": observed}, FakeJudge(), "rubric")
        self.assertIsNone(result["stages"]["first"]["planner_label"])
        self.assertEqual("deepseek-v4.1-flash [flagship, thinking=on, effort=low]",
                         result["stages"]["final"]["planner_label"])
        with tempfile.TemporaryDirectory() as temp:
            ev.write_report(Path(temp), [result])
            report = (Path(temp) / "report.md").read_text()
            self.assertIn("effort=low", report)

    def test_judge_transport_error_retried_then_success(self):
        class FlakyJudge(FakeJudge):
            retries = 2

            def call(self, messages):
                self.calls.append(messages)
                if len(self.calls) == 1:
                    raise ev.TransportError("fixture timeout")
                return json.dumps(self.reply), {"usage": {}}

        attempt = ev.extract_planner(trace(), case()["question"])["attempts"][0]
        judge = FlakyJudge()
        with patch.object(ev.time, "sleep") as sleep:
            result = ev.grade_attempt(case(), attempt, judge, "rubric")
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(2, len(judge.calls))
        self.assertEqual(1, result["judge_call"]["transport_retries"])
        self.assertEqual(1, sleep.call_count)

    def test_judge_transport_retry_exhaustion_is_judge_error(self):
        class AlwaysDown(FakeJudge):
            retries = 1

            def call(self, messages):
                self.calls.append(messages)
                raise ev.TransportError("fixture down")

        attempt = ev.extract_planner(trace(), case()["question"])["attempts"][0]
        judge = AlwaysDown()
        with patch.object(ev.time, "sleep"):
            result = ev.grade_attempt(case(), attempt, judge, "rubric")
        self.assertEqual("JUDGE_ERROR", result["verdict"])
        self.assertIn("已重试 1 次仍失败", result["error"])
        self.assertEqual(2, len(judge.calls))

    def test_judge_http_error_is_never_retried(self):
        class HttpErrorJudge(FakeJudge):
            retries = 3

            def call(self, messages):
                self.calls.append(messages)
                raise ev.EvalError("HTTP 500")

        attempt = ev.extract_planner(trace(), case()["question"])["attempts"][0]
        judge = HttpErrorJudge()
        result = ev.grade_attempt(case(), attempt, judge, "rubric")
        self.assertEqual("JUDGE_ERROR", result["verdict"])
        self.assertEqual(1, len(judge.calls))

    def test_judge_retries_from_env_and_cli_override(self):
        environment = dict(zip(ev.JUDGE_ENV, ["https://judge.invalid/v1", "private-key", "fixture"]))
        with patch.dict(os.environ, {**environment, "PLANNER_EVAL_JUDGE_RETRIES": "2"}, clear=True):
            self.assertEqual(2, ev.JudgeConfig.from_env().retries)
        with patch.dict(os.environ, {**environment, "PLANNER_EVAL_JUDGE_RETRIES": "x"}, clear=True):
            with self.assertRaises(ev.EvalError):
                ev.JudgeConfig.from_env()
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temp)
            source = root / "raw.jsonl"
            ev.append_jsonl(source, {"format_version": 1, "case": case(), "case_hash": ev.digest(case()),
                                     "trace": trace(), "source_kind": "imported_trace"})
            seen = {}

            def fake_grade(record, config, prompt):
                seen["retries"] = config.retries
                return {"case_id": "TEST", "title": "fixture", "verdict": "PASS", "stages": {}}

            config = ev.JudgeConfig("https://judge.invalid/v1", "private-key", "fixture")
            with patch.object(ev.JudgeConfig, "from_env", return_value=config), \
                    patch.object(ev, "grade_record", side_effect=fake_grade):
                self.assertEqual(0, ev.main(["judge", str(source), "--output-root",
                                             str(root / "out"), "--judge-retries", "3"]))
            self.assertEqual(3, seen["retries"])

    def test_single_attempt_judge_is_not_billed_twice(self):
        judge = FakeJudge()
        result = ev.grade_record({"case": case(), "planner": ev.extract_planner(trace(), case()["question"])}, judge, "rubric")
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(1, len(judge.calls))

    def test_missing_duplicate_unknown_criterion_or_no_evidence_is_judge_error(self):
        malformed = [{"criteria": []}, {"criteria": judgment()["criteria"] * 2},
                     {"criteria": [{**judgment()["criteria"][0], "id": "other"}]},
                     {"criteria": [{**judgment()["criteria"][0], "evidence": []}]},
                     {"criteria": [{**judgment()["criteria"][0], "verdict": True}]}]
        attempt = ev.extract_planner(trace(), case()["question"])["attempts"][0]
        for reply in malformed:
            result = ev.grade_attempt(case(), attempt, FakeJudge(reply), "rubric")
            self.assertEqual("JUDGE_ERROR", result["verdict"])

    def test_uncertain_is_never_pass(self):
        attempt = ev.extract_planner(trace(), case()["question"])["attempts"][0]
        self.assertEqual("UNCERTAIN", ev.grade_attempt(case(), attempt, FakeJudge(judgment("UNCERTAIN")), "rubric")["verdict"])

    def test_exact_labels_cannot_be_overridden_by_llm_pass(self):
        sample = case()
        sample["expected_fields"]["new_frame"] = True
        attempt = ev.extract_planner(trace(), case()["question"])["attempts"][0]
        self.assertEqual("FAIL", ev.grade_attempt(sample, attempt, FakeJudge(), "rubric")["verdict"])

    def test_empty_plan_is_treated_as_missing_plan(self):
        # 与 Java evaluateReplyWithoutPlan 同口径：空计划等价于缺 plan，必须有明确话术。
        empty = {"in_domain": True, "reply": None, "plan": {"metrics": [], "calculations": []}}
        self.assertTrue(any("空计划" in e for e in ev.protocol_errors(empty)))
        empty["reply"] = "该问题缺少必要查询对象。"
        self.assertFalse(ev.protocol_errors(empty))

    def test_protocol_failure_and_semantics_are_scored_separately(self):
        # 终态分支附带真内容 plan——协议 FAIL、语义由裁判照判、总 FAIL，裁判不能覆盖程序协议判定。
        out = {"in_domain": False, "reply": "本系统只支持加密货币行情指标查询。",
               "plan": plan()["plan"]}
        sample = case()
        sample["expected_fields"] = {"in_domain": False}
        attempt = {**ev.extract_planner(trace([event(1, json.dumps(out))]), case()["question"])["attempts"][0]}
        judge = FakeJudge()
        result = ev.grade_attempt(sample, attempt, judge, "rubric")
        self.assertEqual("FAIL", result["protocol_verdict"])
        self.assertEqual("PASS", result["semantic_verdict"])
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual(1, len(judge.calls))

    def test_empty_plan_object_tolerated_where_null_required(self):
        # 空计划对象（metrics/calculations 均空）与 plan=null 等价，出域/澄清分支不算违规；
        # 有真内容的 plan 仍是协议错误。
        for branch in ({"in_domain": False, "reply": "超出范围"},
                       {"in_domain": True, "needs_clarification": True, "clarify": "哪个币种？", "reply": None}):
            tolerated = {**branch, "plan": {"metrics": [], "calculations": []}}
            self.assertFalse(ev.protocol_errors(tolerated), tolerated)
            violated = {**branch, "plan": plan()["plan"]}
            self.assertTrue(ev.protocol_errors(violated), violated)

    def test_clarify_without_flag_is_protocol_error(self):
        # P039 型：clarify 仅在 needs_clarification=true 时生效；没置旗时它是死文字，
        # 空计划又没 reply → 协议违约（与服务端同口径）。
        no_flag = {"in_domain": True, "reply": None,
                   "clarify": "币安还是OKX？", "plan": {"metrics": [], "calculations": []}}
        errors = ev.protocol_errors(no_flag)
        self.assertTrue(any("needs_clarification" in e for e in errors), errors)
        self.assertFalse(ev.protocol_errors({**no_flag, "needs_clarification": True}))
        self.assertFalse(ev.protocol_errors({**no_flag, "clarify": None, "reply": "请明确查询对象。"}))

    def test_unparseable_output_marks_protocol_fail_and_skips_judge(self):
        attempt = ev.extract_planner(trace([event(1, "broken JSON")]), case()["question"])["attempts"][0]
        judge = FakeJudge()
        result = ev.grade_attempt(case(), attempt, judge, "rubric")
        self.assertEqual("FAIL", result["protocol_verdict"])
        self.assertEqual("NOT_EVALUABLE", result["semantic_verdict"])
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual([], judge.calls)

    def test_new_task_action_is_a_valid_expected_label(self):
        sample = case()
        sample["expected_fields"] = {"in_domain": True, "task_action": "NEW"}
        ev.validate_suite({"format_version": 1, "cases": [sample]})

    def test_judge_request_carries_strict_output_schema(self):
        config = ev.JudgeConfig("https://judge.invalid/v1", "private-key", "fixture")
        response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(judgment())}}]}
        with patch.object(ev, "request_json", return_value=(response, 1.0)) as transport:
            config.call([{"role": "user", "content": "fixture"}])
        body = transport.call_args.args[1]
        self.assertEqual("json_schema", body["response_format"]["type"])
        item = body["response_format"]["json_schema"]["schema"]["properties"]["criteria"]["items"]
        self.assertEqual(["PASS", "FAIL", "UNCERTAIN"], item["properties"]["verdict"]["enum"])
        self.assertEqual("array", item["properties"]["evidence"]["type"])

    def test_judge_response_format_env_modes(self):
        environment = dict(zip(ev.JUDGE_ENV, ["https://judge.invalid/v1", "private-key", "fixture"]))
        with patch.dict(os.environ, environment, clear=True):
            self.assertEqual("json_schema", ev.JudgeConfig.from_env().response_format)
        with patch.dict(os.environ, {**environment, "PLANNER_EVAL_JUDGE_RESPONSE_FORMAT": "off"}, clear=True):
            config = ev.JudgeConfig.from_env()
            response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(judgment())}}]}
            with patch.object(ev, "request_json", return_value=(response, 1.0)) as transport:
                config.call([{"role": "user", "content": "fixture"}])
            self.assertNotIn("response_format", transport.call_args.args[1])
        for bad in ("xml", {"PLANNER_EVAL_JUDGE_EXTRA_BODY": json.dumps({"response_format": {"type": "json_object"}})}):
            env = {**environment, **({} if isinstance(bad, dict) else {"PLANNER_EVAL_JUDGE_RESPONSE_FORMAT": bad}), **(bad if isinstance(bad, dict) else {})}
            with patch.dict(os.environ, env, clear=True):
                with self.assertRaises(ev.EvalError):
                    ev.JudgeConfig.from_env()

    def test_report_shows_protocol_semantic_split_and_program_fallback(self):
        out = {"in_domain": False, "reply": "本系统只支持加密货币行情指标查询。",
               "plan": plan()["plan"]}
        sample = case()
        sample["expected_fields"] = {"in_domain": False}
        fallback = {"seq": 2, "event_type": "CLARIFY_FALLBACK", "name": "dag_planner", "output_json": out, "error": None}
        dropped = {"seq": 3, "event_type": "PLAN_DROPPED", "name": "dag_planner",
                   "input_json": json.dumps({"reason": "in_domain=false 时 plan 必须为 null，服务端已丢弃"}),
                   "output_json": json.dumps(out["plan"]), "error": None}
        observed = ev.extract_planner(trace([event(1, json.dumps(out)), fallback, dropped]), case()["question"])
        result = ev.grade_record({"case": sample, "planner": observed}, FakeJudge(), "rubric")
        self.assertEqual("CLARIFY_FALLBACK, PLAN_DROPPED", ev.fallback_events(result))
        with tempfile.TemporaryDirectory() as temp:
            ev.write_report(Path(temp), [result])
            report = (Path(temp) / "report.md").read_text()
            self.assertIn("协议FAIL/语义PASS", report)
            self.assertIn("CLARIFY_FALLBACK", report)
            csv_text = (Path(temp) / "report.csv").read_text(encoding="utf-8-sig")
            self.assertIn("first_protocol", csv_text)

    def test_decision_field_check_lands_in_semantic_layer_not_protocol(self):
        # P019 型：该澄清未澄清（needs_clarification 机检 FAIL）——协议 PASS、语义 FAIL、
        # 总 FAIL；裁判判 PASS 也不能覆盖机检。声明类字段（new_frame）仍归协议层。
        sample = case()
        sample["expected_fields"] = {"needs_clarification": True}
        attempt = ev.extract_planner(trace(), case()["question"])["attempts"][0]
        result = ev.grade_attempt(sample, attempt, FakeJudge(), "rubric")
        self.assertEqual("PASS", result["protocol_verdict"])
        self.assertEqual("FAIL", result["semantic_verdict"])
        self.assertEqual("FAIL", result["verdict"])

    def test_key_redacted_from_judge_text_and_parsed_reasons(self):
        reply = judgment()
        reply["criteria"][0]["reason"] = FakeJudge.api_key
        attempt = ev.extract_planner(trace(), case()["question"])["attempts"][0]
        result = ev.grade_attempt(case(), attempt, FakeJudge(reply), "rubric")
        self.assertNotIn(FakeJudge.api_key, json.dumps(result))

    def test_judge_does_not_receive_answer_evidence_or_server_feedback(self):
        observed = ev.extract_planner(trace(), case()["question"])
        observed["decision_events"] = [{"error": "compiler detail"}]
        judge = FakeJudge()
        ev.grade_record({"case": case(), "planner": observed, "response": {"answer": "wrong-answer-marker", "evidence": "price-marker"}}, judge, "rubric")
        payload = json.dumps(judge.calls)
        for marker in ("wrong-answer-marker", "price-marker", "compiler detail"):
            self.assertNotIn(marker, payload)

    def test_request_time_metadata_does_not_copy_answer_messages(self):
        answer = {"seq": 4, "event_type": "LLM_CALL", "name": "answer",
                  "input_json": {"time_context": {"request_time_utc": "2026-10-13T01:00:00Z"},
                                 "messages": [{"content": "do not copy facts"}]}}
        observed = ev.extract_planner(trace([event(), answer]), case()["question"])
        self.assertEqual("2026-10-13T01:00:00Z", observed["attempts"][0]["request_time_context"]["request_time_utc"])
        self.assertNotIn("do not copy facts", json.dumps(observed))

    def test_server_rejection_is_recorded_separately_from_model_score(self):
        rejected = {"seq": 2, "event_type": "PLAN", "name": "dag_planner", "error": "compile failure", "output_json": plan()}
        observed = ev.extract_planner(trace([event(), rejected]), case()["question"])
        result = ev.grade_record({"case": case(), "planner": observed}, FakeJudge(), "rubric")
        self.assertEqual("PASS", result["stages"]["final"]["verdict"])
        self.assertEqual("PLANNER_REJECTED", result["verdict"])
        self.assertIs(result["server_accepted"], False)

    def test_missing_env_detected_before_network_and_key_not_in_repr(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ev.EvalError):
                ev.JudgeConfig.from_env()
        config = ev.JudgeConfig("https://judge.invalid/v1", "private-key", "fixture")
        self.assertNotIn("private-key", repr(config))
        self.assertNotIn("private-key", json.dumps(config.public()))

    def test_env_extra_cannot_override_messages_or_stream(self):
        environment = dict(zip(ev.JUDGE_ENV, ["https://judge.invalid/v1", "private-key", "fixture"]))
        for extra in ({"messages": []}, {"stream": True}, {"n": 2}):
            with patch.dict(os.environ, {**environment, "PLANNER_EVAL_JUDGE_EXTRA_BODY": json.dumps(extra)}, clear=True):
                with self.assertRaises(ev.EvalError):
                    ev.JudgeConfig.from_env()

    def test_truncated_response_from_judge_is_not_success(self):
        config = ev.JudgeConfig("https://judge.invalid/v1", "private-key", "fixture")
        response = {"choices": [{"finish_reason": "length", "message": {"content": json.dumps(judgment())}}]}
        with patch.object(ev, "request_json", return_value=(response, 1.0)):
            with self.assertRaises(ev.EvalError):
                config.call([])

    def test_http_payload_and_url_supported_without_credentials_in_artifacts(self):
        config = ev.JudgeConfig("https://judge.invalid/v1", "private-key", "fixture", {"reasoning_effort": "high"})
        response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(judgment())}}], "model": "fixture-returned"}
        with patch.object(ev, "request_json", return_value=(response, 1.0)) as transport:
            _, metadata = config.call([{"role": "user", "content": "fixture"}])
        self.assertEqual("https://judge.invalid/v1/chat/completions", transport.call_args.args[0])
        self.assertEqual("high", transport.call_args.args[1]["reasoning_effort"])
        self.assertEqual("fixture-returned", metadata["returned_model"])
        self.assertNotIn("private-key", json.dumps(metadata))

    def test_new_runs_backup_previous_raw_and_report_without_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            first = ev.create_run(temp, "collect", [case()])
            (first / "raw.jsonl").write_text("fixture")
            second = ev.create_run(temp, "collect", [case()])
            manifest = ev.read_json(second / "manifest.json")
            backup = Path(manifest["previous_backup"])
            self.assertEqual("fixture", (backup / "raw.jsonl").read_text())
            self.assertEqual("fixture", (first / "raw.jsonl").read_text())
            self.assertNotEqual(first, second)

    def test_report_compares_each_changed_verdict_and_flags_changed_judge(self):
        record = {"case": case(), "planner": ev.extract_planner(trace(), case()["question"])}
        current = ev.grade_record(record, FakeJudge(), "rubric")
        previous = {**current, "verdict": "FAIL"}
        self.assertTrue(ev.compare_results([current], [previous])[0]["comparable"])
        previous["judge_model"] = "different-judge"
        self.assertFalse(ev.compare_results([current], [previous])[0]["comparable"])
        with tempfile.TemporaryDirectory() as temp:
            ev.write_report(Path(temp), [current], [previous])
            report = (Path(temp) / "report.md").read_text()
            self.assertIn("FAIL → PASS 独立复核清单：TEST", report)
            self.assertTrue((Path(temp) / "report.csv").exists())

    def test_collect_error_does_not_turn_into_model_failure(self):
        result = ev.grade_record({"case": case(), "collection_error": "offline fixture timeout"}, FakeJudge(), "rubric")
        self.assertEqual("COLLECTION_ERROR", result["verdict"])

    def test_collection_uses_unique_conversation_and_separate_final_trace(self):
        sample = case()
        sample["setup_questions"] = [sample["question"]]  # Repeated query is still two separate turns.
        response = {"conversationId": "new-conversation", "traceId": "trace-fixture"}
        accepted = {"seq": 2, "event_type": "PLAN", "name": "dag_planner", "output_json": plan(), "error": None}
        data = trace([event(), accepted])
        with patch.object(ev, "request_json", side_effect=[(response, 1), (data, 0), (response, 1), (data, 0)]) as transport:
            record = ev.collect_case(sample, "https://target.invalid", 1)
        self.assertNotIn("collection_error", record)
        self.assertEqual(1, len(record["setup_results"]))
        first_payload = transport.call_args_list[0].args[1]
        final_payload = transport.call_args_list[2].args[1]
        self.assertNotIn("conversationId", first_payload)
        self.assertEqual("new-conversation", final_payload["conversationId"])

    def test_collect_failed_setup_does_not_send_final_question(self):
        sample = case()
        sample["setup_questions"] = [sample["question"]]
        response = {"conversationId": "fixture", "traceId": "fixture"}
        rejected = {"seq": 2, "event_type": "PLAN", "name": "dag_planner", "error": "invalid", "output_json": plan()}
        with patch.object(ev, "request_json", side_effect=[(response, 1), (trace([event(), rejected]), 0)]) as transport:
            record = ev.collect_case(sample, "https://target.invalid", 1)
        self.assertIn("collection_error", record)
        self.assertEqual(2, transport.call_count)
        self.assertEqual(1, len(record["setup_results"]), "Keep failed setup trace for diagnosis")

    def test_collection_continues_after_server_clarification_fallback(self):
        sample = case()
        sample["setup_questions"] = [sample["question"]]
        no_text = {"in_domain": True, "needs_clarification": True, "plan": None, "reply": None}
        setup = trace([event(content=json.dumps(no_text)),
                       {"seq": 2, "event_type": "CLARIFY_FALLBACK", "name": "dag_planner"},
                       {"seq": 3, "event_type": "PLANNER_ATTEMPT", "name": "dag_planner",
                        "output_json": {"call_status": "SUCCEEDED", "validation_status": "NOT_APPLICABLE",
                                        "result": "NEEDS_CLARIFICATION"}, "error": None}])
        final = trace([event(), {"seq": 2, "event_type": "PLAN", "name": "dag_planner",
                                "output_json": plan(), "error": None}])
        response = {"conversationId": "clarifying-conversation", "traceId": "trace-fixture"}
        with patch.object(ev, "request_json", side_effect=[(response, 1), (setup, 0),
                                                           (response, 1), (final, 0)]) as transport:
            record = ev.collect_case(sample, "https://target.invalid", 1)
        self.assertNotIn("collection_error", record)
        self.assertEqual("clarifying-conversation", transport.call_args_list[2].args[1]["conversationId"])
        self.assertEqual(setup, record["setup_results"][0]["trace"])
        # The setup was accepted by the server; the model's missing clarification
        # text is still a protocol failure when that output is graded.
        observed = ev.extract_planner(setup, sample["question"])
        self.assertFalse(observed["server_accepted"] is True)
        self.assertEqual("FAIL", ev.grade_record({"case": sample, "planner": observed}, FakeJudge(), "rubric")
                         ["stages"]["final"]["protocol_verdict"])

    def test_final_setup_audit_overrides_earlier_PLAN_acceptance(self):
        data = trace([event(), {"seq": 2, "event_type": "PLAN", "name": "dag_planner",
                               "output_json": plan(), "error": None},
                      {"seq": 3, "event_type": "PLANNER_ATTEMPT", "name": "dag_planner",
                       "output_json": {"call_status": "SUCCEEDED", "validation_status": "FAILED",
                                       "result": "REPAIR_EXHAUSTED"}, "error": "binding failure"}])
        self.assertFalse(ev.setup_turn_accepted(data, ev.extract_planner(data, case()["question"])))

    def test_setup_audit_before_last_model_call_does_not_accept_repair(self):
        data = trace([event(), {"seq": 2, "event_type": "PLANNER_ATTEMPT", "name": "dag_planner",
                               "output_json": {"call_status": "SUCCEEDED", "validation_status": "PASSED",
                                               "result": "EXECUTABLE"}, "error": None},
                      event(seq=3, attempt=2)])
        self.assertFalse(ev.setup_turn_accepted(data, ev.extract_planner(data, case()["question"])))

    def test_setup_PLAN_before_last_model_call_does_not_accept_repair(self):
        data = trace([event(), {"seq": 2, "event_type": "PLAN", "name": "dag_planner",
                               "output_json": plan(), "error": None}, event(seq=3, attempt=2)])
        self.assertFalse(ev.setup_turn_accepted(data, ev.extract_planner(data, case()["question"])))

    def test_planner_call_failure_is_not_a_semantic_fail(self):
        failed = event()
        failed["error"] = "fixture timeout"
        observed = ev.extract_planner(trace([failed]), case()["question"])
        judge = FakeJudge()
        result = ev.grade_record({"case": case(), "planner": observed}, judge, "rubric")
        self.assertEqual("PLANNER_ERROR", result["verdict"])
        self.assertEqual([], judge.calls)

    def test_report_detects_first_attempt_and_criterion_changes_even_if_final_unchanged(self):
        record = {"case": case(), "planner": ev.extract_planner(trace(), case()["question"])}
        current = ev.grade_record(record, FakeJudge(), "rubric")
        previous = copy.deepcopy(current)
        previous["stages"]["first"]["verdict"] = "FAIL"
        previous["stages"]["first"]["criteria"][0]["verdict"] = "FAIL"
        change = ev.compare_results([current], [previous])[0]
        self.assertEqual("FAIL", change["first_before"])
        self.assertEqual("PASS", change["first_after"])
        self.assertTrue(change["criterion_changed"])

    def test_malformed_scores_and_broken_jsonl_are_rejected_offline(self):
        with self.assertRaises(ev.EvalError):
            ev.validate_scores([{"case_id": "fixture", "title": "fixture", "verdict": "GREEN"}])
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "bad.jsonl"
            path.write_text('{"case_id":')
            with self.assertRaises(ev.EvalError):
                ev.read_jsonl(path)

    def test_missing_judge_env_stops_run_before_collecting(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ev.EvalError):
                ev.main(["run", "--target-url", "https://target.invalid", "--only", "P001", "--output-root", temp])
            self.assertEqual([], list(Path(temp).iterdir()))

    def test_cli_import_rejudge_relabel_and_report_round_trip_is_offline(self):
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temp)
            labels = root / "labels.json"
            labels.write_text(json.dumps({"format_version": 1, "cases": [case()]}))
            source = root / "trace.json"
            source.write_text(json.dumps(trace()))
            output = root / "output"
            self.assertEqual(0, ev.main(["import-trace", str(source), "--cases", str(labels), "--only", "TEST", "--output-root", str(output)]))
            imported = next(output.glob("*/raw.jsonl"))
            # An edited cached planner must not change the original trace.
            captured = ev.read_jsonl(imported)[0]
            captured["planner"]["attempts"][0]["content"] = "broken cached output"
            imported.write_text(ev.dumps(captured) + "\n")
            new_case = case()
            new_case["criteria"][0]["expectation"] = "人工确认成交价快照"
            new_case["label_source"] = "human_reviewed_fixture"
            labels.write_text(json.dumps({"format_version": 1, "cases": [new_case]}))
            judge = FakeJudge()
            with patch.object(ev.JudgeConfig, "from_env", return_value=judge):
                self.assertEqual(0, ev.main(["judge", str(imported), "--cases", str(labels), "--output-root", str(output)]))
            self.assertEqual(1, len(judge.calls))
            scores = next(output.glob("*/scores.jsonl"))
            result = ev.read_jsonl(scores)[0]
            self.assertEqual(ev.digest(new_case), result["case_hash"])
            self.assertEqual("PASS", result["verdict"])
            self.assertEqual(0, ev.main(["report", str(scores), "--compare", str(scores), "--output-root", str(root / "reports")]))
            self.assertTrue(next((root / "reports").glob("*/report.md")).exists())

    def test_relabeling_question_requires_new_collection(self):
        with tempfile.TemporaryDirectory() as temp, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temp)
            source = root / "raw.jsonl"
            ev.append_jsonl(source, {"format_version": 1, "case": case(), "case_hash": ev.digest(case()),
                                     "trace": trace(), "source_kind": "imported_trace"})
            modified = case()
            modified["question"] = "ETH现价？"
            labels = root / "labels.json"
            labels.write_text(json.dumps({"format_version": 1, "cases": [modified]}))
            with patch.object(ev.JudgeConfig, "from_env", return_value=FakeJudge()):
                with self.assertRaises(ev.EvalError):
                    ev.main(["judge", str(source), "--cases", str(labels), "--output-root", str(root / "output")])
            self.assertFalse((root / "output").exists())


if __name__ == "__main__":
    unittest.main()
