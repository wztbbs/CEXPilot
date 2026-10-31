"""重放脚本的离线测试：构造 trace/模型响应，不调用任何线上 API。"""
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
import replay_trace as rp

TRACE_ID = "12c735ec-2ff9-43d7-9e81-77a4ba91f01b"
OLD = "old rules\n目录：{{metrics}}\n<CTX>{{conversation_context}}</CTX>\nref={{m1.binance.value}}\n"
NEW = OLD.replace("old rules", "defaults are not explicit")
VALUES = {"metrics": "币安/OKX catalog", "conversation_context": "已填 BTC；request_time=2026-10-29；literal={{metrics}}"}


def fixture():
    inputs = {"messages": [{"role": "system", "content": rp.render_new(OLD, VALUES)},
                           {"role": "user", "content": "那 OKX 呢？"}],
              "model": "recorded-model", "temperature": 0, "seed": 42, "enable_thinking": False,
              "thinking": {"type": "disabled"}, "response_format": {"type": "json_object"}}
    return {"trace": {"trace_id": TRACE_ID, "question": "那 OKX 呢？", "build_commit": "abcdef0"},
            "events": [{"seq": 4, "event_type": "LLM_CALL", "name": "dag_planner", "input_json": json.dumps(inputs),
                        "output_json": json.dumps({"content": '{"explicit_conditions":{"m1":{"time":"现在"}}}'})}]}


class ReplayTraceTest(unittest.TestCase):
    def test_router_order_matches_production_without_changing_values_or_repair_feedback(self):
        original = {"question": "现在呢？", "context": {"request_time": "精确时间", "recent_dialogue": "历史", "timezone": "Asia/Shanghai", "query_task": {}, "current_agent": "query", "monitor_context": {}, "extra": "保留"}}
        messages = [{"role": "user", "content": json.dumps(original, ensure_ascii=False)},
                    {"role": "user", "content": "只返回合法 JSON"}]
        rp.order_router_messages(messages)
        result = json.loads(messages[0]["content"])
        self.assertEqual(original, result)
        self.assertEqual(["context", "question"], list(result))
        self.assertEqual(["current_agent", "timezone", "query_task", "monitor_context", "extra", "recent_dialogue", "request_time"], list(result["context"]))
        self.assertEqual("只返回合法 JSON", messages[1]["content"])

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.prompt = self.root / "new.txt"
        self.old = self.root / "old.txt"
        self.prompt.write_text(NEW, encoding="utf-8")
        self.old.write_text(OLD, encoding="utf-8")
        self.trace = self.root / "trace.json"
        self.trace.write_text(json.dumps(fixture(), ensure_ascii=False), encoding="utf-8")
        self.out = self.root / "output"
        self.argv = [TRACE_ID, "--trace-file", str(self.trace), "--prompt", str(self.prompt),
                     "--old-template", str(self.old), "--output-dir", str(self.out)]

    def run_main(self, args, response=None, error=None, env=None):
        output, errors = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, env or {}, clear=True), patch.object(rp, "request_json", side_effect=error,
                return_value=(response, 1.25)) as http, contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            status = rp.main(args)
        return status, output.getvalue(), errors.getvalue(), http

    def artifact(self):
        return json.loads(next(self.out.glob("*.json")).read_text(encoding="utf-8"))

    def test_only_rules_change_with_frozen_context_and_dag_references(self):
        data = fixture()
        metadata, event, inputs = rp.select_call(data, "planner", 1)
        original = copy.deepcopy(inputs)
        with patch.object(rp, "git_template", return_value=OLD):
            messages, info = rp.replace_prompt(metadata, inputs, "planner", prompt=self.prompt)
        self.assertEqual(original, inputs)
        self.assertEqual(original["messages"][1:], messages[1:])
        self.assertEqual(rp.render_new(NEW, VALUES), messages[0]["content"])
        self.assertIn("{{m1.binance.value}}", messages[0]["content"])
        self.assertIn("literal={{metrics}}", messages[0]["content"])
        self.assertTrue(info["system_changed"])
        self.assertEqual(["conversation_context", "metrics"], info["preserved_variables"])

    def test_request_copies_flat_and_nested_parameters_without_credentials_or_metadata(self):
        inputs = {"request_parameters": {"model": "nested", "enable_thinking": False, "temperature": 0.4},
                  "model": "actual", "seed": 42, "response_format": None, "authorization": "secret", "stage": "router"}
        body = rp.build_request({}, inputs, [{"role": "user", "content": "q"}])
        self.assertEqual("actual", body["model"])
        self.assertFalse(body["enable_thinking"])
        self.assertEqual(0.4, body["temperature"])
        self.assertEqual(42, body["seed"])
        self.assertFalse(body["stream"])
        self.assertNotIn("authorization", body)
        self.assertNotIn("stage", body)
        self.assertNotIn("response_format", body)

    def test_call_selection_uses_first_attempt_in_sequence_not_final_repaired_output(self):
        data = fixture()
        second = copy.deepcopy(data["events"][0])
        second["seq"] = 9
        second["output_json"] = {"content": "repaired"}
        data["events"] = [second, data["events"][0]]
        self.assertEqual(4, rp.select_call(data, "planner", 1)[1]["seq"])
        self.assertEqual(9, rp.select_call(data, "planner", 2)[1]["seq"])
        with self.assertRaises(rp.EvalError):
            rp.select_call(data, "router", 1)

    def test_missing_truncated_or_unmatched_input_does_not_guess_context(self):
        data = fixture()
        for invalid in ({"stage": "dag_planner"}, '{"messages":['):
            data["events"][0]["input_json"] = invalid
            with self.assertRaises(rp.EvalError):
                rp.select_call(data, "planner", 1)
        metadata, _, inputs = rp.select_call(fixture(), "planner", 1)
        self.old.write_text("wrong deployment", encoding="utf-8")
        with self.assertRaises(rp.EvalError):
            rp.replace_prompt(metadata, inputs, "planner", prompt=self.prompt, old_template=self.old)
        with self.assertRaises(rp.EvalError):
            rp.render_new("new var {{unsupported}}", {})

    def test_unchanged_local_template_is_allowed_when_commit_is_unavailable(self):
        self.prompt.write_text(OLD, encoding="utf-8")
        metadata, _, inputs = rp.select_call(fixture(), "planner", 1)
        with patch.object(rp, "git_template", return_value=None):
            _, info = rp.replace_prompt(metadata, inputs, "planner", prompt=self.prompt)
        self.assertFalse(info["system_changed"])

    def test_router_answer_monitor_and_clarification_templates(self):
        for stage, name in (("router", "intent_router"), ("answer", "agent_system"),
                            ("monitor", "monitor_planner"), ("planner", "clarification_repair")):
            with self.subTest(stage=stage, name=name):
                template = name + " rules\n" + "\n".join("{{" + k + "}}" for k in rp.VARIABLES[name])
                values = {k: "frozen " + k for k in rp.VARIABLES[name]}
                path = self.root / rp.PROMPTS / (name + ".txt")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(template.replace(" rules", " new rules"), encoding="utf-8")
                for candidate in rp.TEMPLATES[stage]:
                    other = self.root / rp.PROMPTS / (candidate + ".txt")
                    if not other.exists():
                        other.write_text("another template", encoding="utf-8")
                inputs = {"messages": [{"role": "system", "content": rp.render_new(template, values)}]}
                with patch.object(rp, "git_template", side_effect=lambda root, commit, n: template if n == name else "another template"):
                    messages, info = rp.replace_prompt({"build_commit": "abcdef0"}, inputs, stage, root=self.root)
                self.assertEqual(name, info["template"])
                self.assertIn("new rules", messages[0]["content"])

    def test_dry_run_without_key_makes_no_network_call_and_saves_request(self):
        status, output, errors, http = self.run_main(self.argv + ["--dry-run"])
        self.assertEqual(0, status, errors)
        http.assert_not_called()
        record = self.artifact()
        self.assertEqual("dry_run", record["mode"])
        self.assertEqual("NOT_RUN", record["business_validation"])
        self.assertEqual(0o600, next(self.out.glob("*.json")).stat().st_mode & 0o777)
        self.assertNotIn("response", record)

    def test_one_llm_call_prints_old_and_new_and_redacts_echoed_secret(self):
        key = "fake-secret-for-test"
        response = {"choices": [{"message": {"content": '{"explicit_conditions":{}}', "echo": key}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 100, "completion_tokens": 10}}
        status, output, errors, http = self.run_main(self.argv, response=response, env={"LLM_NORMAL_API_KEY": key})
        self.assertEqual(0, status, errors)
        self.assertEqual(1, http.call_count)
        self.assertEqual(key, http.call_args.kwargs["api_key"])
        self.assertEqual("recorded-model", http.call_args.args[1]["model"])
        self.assertNotIn(key, output + errors + next(self.out.glob("*.json")).read_text())
        self.assertIn("旧响应", output)
        self.assertIn("新响应", output)
        self.assertEqual("VALID_JSON", self.artifact()["json_parse"])
        self.assertEqual(1250, self.artifact()["duration_ms"])

    def test_http_failure_has_no_retry_and_saves_failed_call(self):
        status, _, errors, http = self.run_main(self.argv, error=rp.EvalError("HTTP 401"), env={"LLM_NORMAL_API_KEY": "fake"})
        self.assertEqual(1, status)
        self.assertEqual(1, http.call_count)
        self.assertEqual("HTTP 401", self.artifact()["call_error"])

    def test_environment_endpoint_and_model_overrides_with_cli_model_taking_precedence(self):
        env = {"LLM_NORMAL_API_KEY": "fake", "LLM_NORMAL_BASE_URL": "https://test.example/v1", "LLM_NORMAL_MODEL": "environment-model"}
        response = {"choices": [{"message": {"content": "{}"}, "finish_reason": "stop"}]}
        status, _, errors, http = self.run_main(self.argv, response=response, env=env)
        self.assertEqual(0, status, errors)
        self.assertEqual("https://test.example/v1/chat/completions", http.call_args.args[0])
        self.assertEqual("environment-model", http.call_args.args[1]["model"])
        status, _, errors, http = self.run_main(self.argv + ["--model", "cli-model"], response=response, env=env)
        self.assertEqual(0, status, errors)
        self.assertEqual("cli-model", http.call_args.args[1]["model"])

    def test_secret_redaction_keeps_json_valid_even_with_escaped_characters(self):
        key = 'fake-"secret-测试"'
        path = rp.write_artifact(self.out, {"response": {"echo": key}, "request": {"model": "test"}}, key)
        self.assertEqual("[REDACTED]", json.loads(path.read_text())["response"]["echo"])
        self.assertNotIn(key, path.read_text())

    def test_current_parameter_protocol_replaces_legacy_schema_without_changing_plan_capabilities(self):
        plan_schema = {"type": "object", "properties": {"metric": {"enum": ["price.last"]}}}
        body = {"response_format": {"type": "json_schema", "json_schema": {"strict": True, "schema": {
            "properties": {"plan": plan_schema, "explicit_conditions": {"type": "object"}},
            "required": ["plan", "explicit_conditions"]}}}}
        untouched = copy.deepcopy(body)
        self.assertFalse(rp.refresh_parameter_protocol(body, "planner", keep_trace_schema=True))
        self.assertEqual(untouched, body)
        self.assertTrue(rp.refresh_parameter_protocol(body, "planner"))
        schema = body["response_format"]["json_schema"]["schema"]
        self.assertEqual(plan_schema, schema["properties"]["plan"])
        self.assertNotIn("explicit_conditions", schema["properties"])
        self.assertNotIn("explicit_conditions", schema["required"])
        self.assertEqual(["set", "reset"], schema["properties"]["condition_updates"]["additionalProperties"]["properties"]["interval"]["enum"])
        self.assertFalse(rp.refresh_parameter_protocol(body, "planner"))

    def test_missing_key_or_wrong_trace_id_stops_before_llm(self):
        status, _, _, http = self.run_main(self.argv)
        self.assertEqual(1, status)
        http.assert_not_called()
        data = fixture()
        data["trace"]["trace_id"] = str(__import__("uuid").uuid4())
        self.trace.write_text(json.dumps(data), encoding="utf-8")
        status, _, _, http = self.run_main(self.argv + ["--dry-run"])
        self.assertEqual(1, status)
        http.assert_not_called()


if __name__ == "__main__":
    unittest.main()
