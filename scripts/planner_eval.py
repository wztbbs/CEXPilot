#!/usr/bin/env python3
"""Planner eval: collect real traces, judge first/final attempts, save file reports.

Only explicit collect/run/judge commands use the network. Importing this module,
list, validate, import-trace and report are offline. Python standard library only.
"""

from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import json
import math
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass, field, replace
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = ROOT / "scripts/evals/planner_cases.json"
DEFAULT_PROMPT = ROOT / "scripts/evals/planner_judge.txt"
DEFAULT_OUTPUT = ROOT / "importants/回归测试/planner-eval"
FORMAT_VERSION = 1
DIMENSIONS = (
    "decision", "instrument", "metric_shape", "time", "parameters",
    "calculation", "context", "minimality",
)
# expected_fields 里的决策类字段："该不该出域/该不该澄清"属语义判断，机检归语义层（不被裁判覆盖）；
# 其余声明类字段（new_frame/task_action）是对账声明义务，机检归协议层。
DECISION_FIELDS = {"in_domain", "needs_clarification"}
JUDGE_ENV = (
    "PLANNER_EVAL_JUDGE_BASE_URL", "PLANNER_EVAL_JUDGE_API_KEY",
    "PLANNER_EVAL_JUDGE_MODEL",
)
JUDGE_RESPONSE_FORMAT = {
    "type": "json_schema",
    "json_schema": {
        "name": "planner_judgment",
        "strict": True,
        "schema": {
            "type": "object",
            "required": ["criteria"],
            "additionalProperties": False,
            "properties": {
                "criteria": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "required": ["id", "verdict", "reason", "evidence"],
                        "additionalProperties": False,
                        "properties": {
                            "id": {"type": "string", "minLength": 1},
                            "verdict": {"type": "string", "enum": ["PASS", "FAIL", "UNCERTAIN"]},
                            "reason": {"type": "string", "minLength": 1},
                            "evidence": {"type": "array", "minItems": 1,
                                          "items": {"type": "string", "minLength": 1}},
                        },
                    },
                },
            },
        },
    },
}
ID_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,39}\Z")
REFERENCE_PATTERN = re.compile(r"\{\{([A-Za-z][A-Za-z0-9_]*)(?:\.([A-Za-z0-9_.]+))?}}\Z")


class EvalError(Exception):
    """Safe user-facing configuration, input, or transport error."""


class TransportError(EvalError):
    """网络传输层失败（连接中断/超时）；与 HTTP 错误码区分，供裁判有限重试判断。"""


def dumps(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def digest(value):
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def strict_json(text):
    def no_constant(value):
        raise ValueError("non-finite JSON number: " + value)

    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key: " + key)
            result[key] = value
        return result

    return json.loads(text, parse_constant=no_constant, object_pairs_hook=unique_keys)


def read_json(path):
    try:
        return strict_json(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise EvalError(f"无法读取 JSON {path}: {exc}") from exc


def json_value(value, label):
    if value is None:
        return {}
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            result = strict_json(value)
        except ValueError as exc:
            raise EvalError(f"{label} 不是完整 JSON（可能被截断）") from exc
        if isinstance(result, dict):
            return result
    raise EvalError(f"{label} 必须是 JSON 对象")


def parse_model_object(content):
    """Accept one complete object, optionally fenced; never salvage a substring."""
    if not isinstance(content, str) or not content.strip():
        raise EvalError("模型响应缺少完整 content")
    text = content.strip()
    if text.startswith("```"):
        match = re.fullmatch(r"```(?:json)?\s*\n?(.*?)\n?```", text, re.S)
        if not match:
            raise EvalError("模型 JSON 围栏不完整")
        text = match.group(1).strip()
    try:
        result = strict_json(text)
    except ValueError as exc:
        raise EvalError("模型未返回单个完整 JSON 对象") from exc
    if not isinstance(result, dict):
        raise EvalError("模型输出必须为 JSON 对象")
    return result


def load_cases(path=DEFAULT_CASES, only=None):
    suite = read_json(path)
    return validate_suite(suite, only)


def validate_suite(suite, only=None):
    if not isinstance(suite, dict) or suite.get("format_version") != FORMAT_VERSION:
        raise EvalError("用例文件 format_version 必须为 1")
    cases = suite.get("cases")
    if not isinstance(cases, list) or not cases:
        raise EvalError("cases 必须为非空数组")
    seen = set()
    for case in cases:
        if not isinstance(case, dict):
            raise EvalError("case 必须为对象")
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise EvalError(f"case id 缺失或重复: {case_id}")
        seen.add(case_id)
        for key in ("question", "timezone", "title"):
            if not isinstance(case.get(key), str) or not case[key].strip():
                raise EvalError(f"{case_id}.{key} 必须为非空字符串")
        try:
            ZoneInfo(case["timezone"])
        except (ValueError, KeyError) as exc:
            raise EvalError(f"{case_id}: 无效时区") from exc
        setup = case.get("setup_questions", [])
        if not isinstance(setup, list) or any(not isinstance(q, str) or not q.strip() for q in setup):
            raise EvalError(f"{case_id}.setup_questions 必须为问题字符串数组")
        criteria = case.get("criteria")
        if not isinstance(criteria, list) or not criteria:
            raise EvalError(f"{case_id} 必须有明确的 criteria，禁止无断言 PASS")
        criterion_ids = set()
        for criterion in criteria:
            if not isinstance(criterion, dict):
                raise EvalError(f"{case_id}: criterion 必须为对象")
            cid = criterion.get("id")
            if not isinstance(cid, str) or not cid or cid in criterion_ids:
                raise EvalError(f"{case_id}: criterion id 缺失或重复")
            criterion_ids.add(cid)
            if criterion.get("dimension") not in DIMENSIONS:
                raise EvalError(f"{case_id}.{cid}: 未知 dimension")
            if not isinstance(criterion.get("expectation"), str) or not criterion["expectation"].strip():
                raise EvalError(f"{case_id}.{cid}: expectation 必须为非空字符串")
        for key in ("tags", "coverage"):
            values = case.get(key, [])
            if not isinstance(values, list) or any(not isinstance(v, str) for v in values):
                raise EvalError(f"{case_id}.{key} 必须为字符串数组")
        expected = case.get("expected_fields", {})
        if not isinstance(expected, dict) or any(k not in
                ("in_domain", "needs_clarification", "new_frame", "task_action") for k in expected):
            raise EvalError(f"{case_id}.expected_fields 仅支持声明的信封语义字段")
        for key, value in expected.items():
            if key == "task_action":
                if value not in ("CONTINUE", "RESUME", "NEW"):
                    raise EvalError(f"{case_id}: task_action 预期非法")
            elif type(value) is not bool:
                raise EvalError(f"{case_id}: {key} 预期必须为 boolean")
    if only:
        requested = {item.strip() for item in only.split(",") if item.strip()}
        cases = [c for c in cases if c["id"] in requested]
        missing = requested - {c["id"] for c in cases}
        if missing:
            raise EvalError("未知 case id: " + ", ".join(sorted(missing)))
        if not cases:
            raise EvalError("没有选中用例")
    return suite, cases


def coverage(cases):
    dimensions = Counter()
    features = Counter()
    for case in cases:
        dimensions.update({c["dimension"] for c in case["criteria"]})
        features.update(set(case.get("coverage", [])))
    return {"dimensions": dict(dimensions), "features": dict(features),
            "missing_dimensions": [d for d in DIMENSIONS if not dimensions[d]]}


def validate_url(value, label):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password:
        raise EvalError(f"{label} 必须是 http/https URL，不能在 URL 内携带凭据")
    if parsed.query or parsed.fragment:
        raise EvalError(f"{label} 不能带 query 或 fragment")
    return value.rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Do not forward API credentials to a redirect destination.
        return None


def request_json(url, payload=None, *, timeout=240, api_key=None):
    headers = {"Accept": "application/json", "User-Agent": "CEXPilot-Planner-Eval/1"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = dumps(payload).encode("utf-8")
    if api_key:
        headers["Authorization"] = "Bearer " + api_key
    request = urllib.request.Request(url, data=data, headers=headers)
    started = time.monotonic()
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=timeout) as response:
            result = strict_json(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        # Server bodies may echo authorization/payloads; never log them.
        raise EvalError(f"HTTP {exc.code}；请检查地址、权限及服务日志（未打印响应体）") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise TransportError("网络请求失败或超时（传输层错误）") from None
    except (ValueError, UnicodeError):
        raise EvalError("接口未返回完整有效 JSON") from None
    if not isinstance(result, dict):
        raise EvalError("接口 JSON 必须为对象")
    return result, round(time.monotonic() - started, 3)


@dataclass(frozen=True)
class JudgeConfig:
    base_url: str
    api_key: str = field(repr=False)
    model: str = ""
    extra_body: dict = field(default_factory=dict)
    timeout: float = 240
    # 传输层失败（连接/超时）的最大重试次数，默认 0；HTTP 错误与裁判协议问题不重试
    retries: int = 0
    # 裁判输出结构约束：json_schema（默认，严 Schema）/ json_object / off
    response_format: str = "json_schema"

    @classmethod
    def from_env(cls):
        missing = [key for key in JUDGE_ENV if not os.environ.get(key, "").strip()]
        if missing:
            raise EvalError("缺少裁判配置: " + ", ".join(missing))
        extra = {}
        if os.environ.get("PLANNER_EVAL_JUDGE_EXTRA_BODY", "").strip():
            extra = json_value(os.environ["PLANNER_EVAL_JUDGE_EXTRA_BODY"], "JUDGE_EXTRA_BODY")
        forbidden = {"model", "messages", "stream", "tools", "tool_choice", "n", "response_format"} & extra.keys()
        if forbidden:
            raise EvalError("JUDGE_EXTRA_BODY 不允许覆盖: " + ", ".join(sorted(forbidden)))
        try:
            timeout = float(os.environ.get("PLANNER_EVAL_JUDGE_TIMEOUT", "240"))
        except ValueError as exc:
            raise EvalError("JUDGE_TIMEOUT 必须为正数秒") from exc
        if not math.isfinite(timeout) or timeout <= 0:
            raise EvalError("JUDGE_TIMEOUT 必须为正数秒")
        try:
            retries = int(os.environ.get("PLANNER_EVAL_JUDGE_RETRIES", "0").strip() or "0")
        except ValueError:
            raise EvalError("JUDGE_RETRIES 必须为非负整数") from None
        if retries < 0:
            raise EvalError("JUDGE_RETRIES 必须为非负整数")
        response_format = os.environ.get("PLANNER_EVAL_JUDGE_RESPONSE_FORMAT", "json_schema").strip() or "json_schema"
        if response_format not in ("json_schema", "json_object", "off"):
            raise EvalError("JUDGE_RESPONSE_FORMAT 只支持 json_schema / json_object / off")
        return cls(validate_url(os.environ[JUDGE_ENV[0]].strip(), JUDGE_ENV[0]),
                   os.environ[JUDGE_ENV[1]].strip(), os.environ[JUDGE_ENV[2]].strip(),
                   extra, timeout, retries, response_format)

    def public(self):
        # EXTRA_BODY values are intentionally not persisted, only their hash.
        return {"base_url": self.base_url, "model": self.model,
                "extra_body_hash": digest(self.extra_body), "timeout_seconds": self.timeout,
                "response_format": self.response_format}

    def call(self, messages):
        body = {"model": self.model, "messages": messages, "stream": False}
        # 严 Schema 约束裁判输出：evidence 必须为字符串数组、verdict 为合法枚举；
        # 通道不支持时会在首次调用以 HTTP 错误显式暴露，可换 json_object 或 off。
        if self.response_format == "json_schema":
            body["response_format"] = JUDGE_RESPONSE_FORMAT
        elif self.response_format == "json_object":
            body["response_format"] = {"type": "json_object"}
        body.update(self.extra_body)
        endpoint = self.base_url if self.base_url.endswith("/chat/completions") else self.base_url + "/chat/completions"
        response, duration = request_json(endpoint, body, timeout=self.timeout, api_key=self.api_key)
        choices = response.get("choices")
        if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
            raise EvalError("裁判响应必须包含一个 choices 项")
        choice = choices[0]
        if choice.get("finish_reason") != "stop":
            raise EvalError("裁判未正常结束（截断、拒绝或协议不兼容），本次不判 PASS")
        message = choice.get("message")
        if not isinstance(message, dict) or message.get("refusal") or message.get("tool_calls"):
            raise EvalError("裁判返回拒绝或工具调用，不能用作判分")
        content = message.get("content")
        return content, {"duration_seconds": duration, "usage": response.get("usage", {}),
                         "requested_model": self.model, "returned_model": response.get("model"),
                         "response_id": response.get("id")}


def call_with_retries(config, messages):
    """裁判调用：仅传输层失败按 config.retries 有限重试（指数退避）；
    HTTP 错误码与裁判协议问题不重试（重试也无意义）。重试次数记入调用元数据。"""
    retries = getattr(config, "retries", 0) or 0
    failures = 0
    while True:
        try:
            content, metadata = config.call(messages)
            metadata["transport_retries"] = failures
            return content, metadata
        except TransportError as exc:
            failures += 1
            if failures > retries:
                if retries:
                    raise TransportError(f"{exc}（已重试 {retries} 次仍失败）") from None
                raise
            time.sleep(min(2 ** failures, 8))


def ordered_events(trace):
    events = trace.get("events")
    if not isinstance(events, list) or any(not isinstance(e, dict) for e in events):
        raise EvalError("trace.events 缺失；请使用 /api/trace_detail 完整回放")
    try:
        return sorted(events, key=lambda e: int(e.get("seq", 0)))
    except (ValueError, TypeError) as exc:
        raise EvalError("trace seq 无效") from exc


def extract_planner(trace, question):
    events = ordered_events(trace)
    metadata = trace.get("trace") or {}
    if not isinstance(metadata, dict):
        raise EvalError("trace.trace 必须为对象")
    if metadata.get("question") and metadata["question"] != question:
        raise EvalError("trace 问题与 case 不一致，禁止给错误样本套用标注")
    attempts = []
    request_time_context = None
    for event in events:
        if event.get("event_type") == "LLM_CALL" and event.get("name") == "answer":
            try:
                answer_input = json_value(event.get("input_json"), "answer.input_json")
                context = answer_input.get("time_context")
                if isinstance(context, dict):
                    request_time_context = context
            except EvalError:
                pass
        if event.get("event_type") != "LLM_CALL" or event.get("name") != "dag_planner":
            continue
        item = {"seq": event.get("seq"), "duration_ms": event.get("duration_ms"),
                "prompt_tokens": event.get("prompt_tokens"), "completion_tokens": event.get("completion_tokens")}
        try:
            inputs = json_value(event.get("input_json"), "planner.input_json")
            outputs = json_value(event.get("output_json"), "planner.output_json")
            item.update({"attempt": inputs.get("attempt", len(attempts) + 1),
                         "messages": inputs.get("messages"), "response_format": inputs.get("response_format"),
                         "content": outputs.get("content"), "error": event.get("error"),
                         # 出参模型身份：同模型不同思考配置的首排/修复轮（如 effort=low 的
                         # 兜底调用）名字相同，只能靠这些字段区分。
                         "model": inputs.get("model"), "model_role": inputs.get("model_role"),
                         "enable_thinking": inputs.get("enable_thinking"),
                         "thinking": inputs.get("thinking"),
                         "reasoning_effort": inputs.get("reasoning_effort")})
            messages = item["messages"]
            if not isinstance(messages, list) or not messages or any(
                    not isinstance(m, dict) or not isinstance(m.get("content"), str)
                    or m.get("role") not in ("system", "user", "assistant") for m in messages):
                raise EvalError("历史 trace 缺少完整 planner messages，不能还原实际能力或上下文")
            if not any(m["role"] == "system" for m in messages):
                raise EvalError("planner messages 缺少 system prompt")
            if not any(m["role"] == "user" and m["content"] == question for m in messages):
                raise EvalError("planner messages 中找不到当前问题")
        except EvalError as exc:
            item["extraction_error"] = str(exc)
        attempts.append(item)
    decisions = [{k: event.get(k) for k in ("seq", "event_type", "name", "output_json", "error")}
                 for event in events if event.get("event_type") in
                 ("PLAN", "PLAN_COMPILED", "INHERIT_SHADOW", "CLARIFY_FALLBACK", "PLAN_DROPPED")]
    for item in attempts:
        item["request_time_context"] = request_time_context
    plan_events = [e for e in decisions if e["event_type"] == "PLAN"]
    last_decision = plan_events[-1] if plan_events else None
    server_accepted = None
    if last_decision:
        # A diagnostic PLAN note without a parsed envelope is not acceptance.
        try:
            decision_output = json_value(last_decision.get("output_json"), "PLAN.output_json")
            server_accepted = not bool(last_decision.get("error")) and type(decision_output.get("in_domain")) is bool
        except EvalError:
            server_accepted = False
    return {"attempts": attempts, "decision_events": decisions, "server_accepted": server_accepted,
            "trace_metadata": {k: metadata.get(k) for k in
                               ("trace_id", "model", "prompt_version", "build_commit", "created_at", "status")}}


def attempt_label(attempt):
    """planner 调用身份标签：模型 + 思考开关/档位；旧 trace 没有这些字段时返回 None。"""
    model = attempt.get("model")
    if not model:
        return None
    details = [attempt["model_role"]] if attempt.get("model_role") else []
    if attempt.get("enable_thinking") is not None:
        details.append("thinking=" + ("on" if attempt["enable_thinking"] else "off"))
    thinking = attempt.get("thinking")
    if isinstance(thinking, dict) and thinking.get("type"):
        details.append("thinking=" + str(thinking["type"]))
    if attempt.get("reasoning_effort"):
        details.append("effort=" + str(attempt["reasoning_effort"]))
    return model + (" [" + ", ".join(details) + "]" if details else "")


def _is_empty_plan(plan):
    """空计划对象（metrics 与 calculations 均空）与 plan=null 等价，无执行内容。"""
    return isinstance(plan, dict) and not plan.get("metrics") and not plan.get("calculations")


def protocol_errors(envelope):
    """Small grammar checks only; Java remains the authority on compilation/execution."""
    errors = []
    if type(envelope.get("in_domain")) is not bool:
        errors.append("in_domain 必须为 boolean")
    if "plan" not in envelope or "reply" not in envelope:
        errors.append("必须包含 plan 和 reply")
    if envelope.get("reply") is not None and not isinstance(envelope["reply"], str):
        errors.append("reply 必须为 string 或 null")
    for flag in ("needs_clarification", "new_frame"):
        if envelope.get(flag) is not None and type(envelope[flag]) is not bool:
            errors.append(flag + " 必须为 boolean 或 null")
    if envelope.get("clarify") is not None and not isinstance(envelope["clarify"], str):
        errors.append("clarify 必须为 string 或 null")
    changes = envelope.get("changes")
    if changes is not None and (not isinstance(changes, list) or any(not isinstance(v, str) for v in changes)):
        errors.append("changes 必须为字符串数组或 null")
    plan = envelope.get("plan")
    if envelope.get("in_domain") is False and plan is not None and not _is_empty_plan(plan):
        errors.append("in_domain=false 时 plan 必须为空（null 或无取数无计算的空对象）")
    if envelope.get("needs_clarification") is True and plan is not None and not _is_empty_plan(plan):
        errors.append("needs_clarification=true 时 plan 必须为空（null 或无取数无计算的空对象）")
    if plan is None:
        text = envelope.get("clarify") or envelope.get("reply")
        if not isinstance(text, str) or not text.strip():
            errors.append("plan=null 必须有明确 reply 或 clarify")
        return errors
    if not isinstance(plan, dict):
        return errors + ["plan 必须为对象或 null"]
    if "nodes" in plan:
        errors.append("旧协议 plan.nodes 不属于当前逻辑计划")
    for key in ("metrics", "calculations"):
        if not isinstance(plan.get(key), list):
            errors.append("plan." + key + " 必须为数组")
    if errors:
        return errors
    if not plan["metrics"] and not plan["calculations"]:
        # 与 Java evaluateReplyWithoutPlan 同口径：空计划等价于缺 plan，
        # 可以是"有意不规划"，但必须有明确话术交代；无话术即协议违约。
        text = envelope.get("clarify") or envelope.get("reply")
        if not isinstance(text, str) or not text.strip():
            errors.append("空计划（无指标无计算）必须有明确 reply 或 clarify")
        return errors
    ids = {}
    for kind, nodes in (("metric", plan["metrics"]), ("calculation", plan["calculations"])):
        for index, node in enumerate(nodes):
            path = f"{kind}[{index}]"
            if not isinstance(node, dict):
                errors.append(path + " 必须为对象")
                continue
            node_id = node.get("id")
            if not isinstance(node_id, str) or not ID_PATTERN.fullmatch(node_id) or node_id.startswith("metric_"):
                errors.append(path + " id 非法")
            elif node_id in ids:
                errors.append(path + " id 重复")
            else:
                ids[node_id] = (kind, node)
            if kind == "metric":
                for key in ("metric", "query_shape"):
                    if not isinstance(node.get(key), str) or not node[key]:
                        errors.append(path + "." + key + " 缺失")
                shape = node.get("query_shape")
                if shape not in ("snapshot", "official_24h", "range_statistic", "time_series", "recent_n"):
                    errors.append(path + " query_shape 未知")
                if shape in ("snapshot", "official_24h", "recent_n"):
                    forbidden = {"time", "interval", "include_unclosed"}
                    if shape != "recent_n":
                        forbidden.add("count")
                    if forbidden & node.keys():
                        errors.append(path + " 该形态禁止参数: " + ", ".join(sorted(forbidden & node.keys())))
                if shape == "recent_n" and (type(node.get("count")) is not int or node["count"] <= 0):
                    errors.append(path + " recent_n 必须有正整数 count")
                if shape in ("range_statistic", "time_series") and not isinstance(node.get("time"), dict):
                    errors.append(path + " 时间形态必须有 time 对象")
                exchanges = node.get("exchanges")
                if not isinstance(exchanges, list) or not exchanges or any(
                        not isinstance(v, str) or v not in ("binance", "okx") for v in exchanges):
                    errors.append(path + " exchanges 非法")
                elif len(set(exchanges)) != len(exchanges):
                    errors.append(path + " exchanges 重复")
                instrument = node.get("instrument")
                if not isinstance(instrument, dict) or any(
                        not isinstance(instrument.get(k), str) or not instrument[k]
                        for k in ("market_type", "base", "quote")):
                    errors.append(path + " instrument 缺少必填字段")
            elif not isinstance(node.get("operator"), str) or not isinstance(node.get("input"), dict):
                errors.append(path + " 缺少 operator/input")

    def check_references(value):
        if isinstance(value, dict):
            for child in value.values():
                check_references(child)
        elif isinstance(value, list):
            for child in value:
                check_references(child)
        elif isinstance(value, str) and ("{{" in value or "}}" in value):
            match = REFERENCE_PATTERN.fullmatch(value)
            if not match or not match.group(2):
                errors.append("引用必须是完整字段路径: " + value)
                return
            source = ids.get(match.group(1))
            if source is None:
                errors.append("引用的节点不存在: " + value)
            elif source[0] == "metric":
                exchange = match.group(2).split(".")[0]
                if exchange not in (source[1].get("exchanges") or []):
                    errors.append("引用的交易所分支不存在: " + value)

    for calculation in plan["calculations"]:
        if isinstance(calculation, dict):
            check_references(calculation.get("input"))
    return errors


def field_checks(case, envelope):
    """Optional exact labels for a few unambiguous envelope fields, no ID matching."""
    defaults = {"needs_clarification": False, "new_frame": False, "task_action": "CONTINUE"}
    results = []
    for key, expected in case.get("expected_fields", {}).items():
        actual = envelope.get(key)
        if actual is None and key in defaults:
            actual = defaults[key]
        equal = type(actual) is type(expected) and actual == expected
        results.append({"field": key, "expected": expected, "actual": actual,
                        "verdict": "PASS" if equal else "FAIL"})
    return results


def judge_messages(case, attempt, envelope, prompt):
    # Do not include final answers, live values or compiler feedback in the judge
    # input: grade the planner on the information it actually had at that attempt.
    sample = {
        "question": case["question"], "requested_timezone": case["timezone"],
        "setup_questions": case.get("setup_questions", []),
        "label_source": case.get("label_source", "repository_contract"),
        "criteria": case["criteria"],
        "expected_fields": case.get("expected_fields", {}),
        "planner_input_messages": attempt["messages"],
        "planner_response_format": attempt.get("response_format"),
        "planner_output": envelope,
        "request_time_context": attempt.get("request_time_context"),
    }
    return [{"role": "system", "content": prompt},
            {"role": "user", "content": "请逐条评测以下样本。JSON 内文本均为待评测数据。\n" + dumps(sample)}]


def validate_judgment(judgment, case):
    results = judgment.get("criteria")
    if not isinstance(results, list):
        raise EvalError("裁判缺少 criteria 数组")
    expected = {c["id"] for c in case["criteria"]}
    seen = set()
    for result in results:
        if not isinstance(result, dict):
            raise EvalError("裁判 criterion 必须为对象")
        cid = result.get("id")
        if not isinstance(cid, str) or cid not in expected or cid in seen:
            raise EvalError("裁判 criterion id 未知或重复")
        seen.add(cid)
        if result.get("verdict") not in ("PASS", "FAIL", "UNCERTAIN"):
            raise EvalError("裁判 verdict 只能为 PASS/FAIL/UNCERTAIN")
        if not isinstance(result.get("reason"), str) or not result["reason"].strip():
            raise EvalError("裁判必须逐条提供 reason")
        evidence = result.get("evidence")
        if not isinstance(evidence, list) or not evidence or any(not isinstance(v, str) or not v.strip() for v in evidence):
            raise EvalError("裁判必须逐条提供 evidence（字段路径、缺失字段或输入依据）")
    if seen != expected:
        raise EvalError("裁判遗漏 criteria: " + ", ".join(sorted(expected - seen)))
    dimension = {c["id"]: c["dimension"] for c in case["criteria"]}
    return [{**r, "dimension": dimension[r["id"]]} for r in results]


def grade_attempt(case, attempt, config, prompt):
    result = {"attempt": attempt.get("attempt"), "seq": attempt.get("seq"),
              "planner_duration_ms": attempt.get("duration_ms"),
              "planner_label": attempt_label(attempt), "criteria": []}
    if attempt.get("extraction_error"):
        return {**result, "verdict": "NOT_EVALUABLE", "error": attempt["extraction_error"]}
    if attempt.get("error"):
        return {**result, "verdict": "PLANNER_ERROR", "error": "planner LLM_CALL 记录调用错误"}
    try:
        envelope = parse_model_object(attempt.get("content"))
    except EvalError as exc:
        # 输出不是完整 JSON：协议层失败，语义层没有可判对象。
        return {**result, "verdict": "FAIL", "protocol_verdict": "FAIL",
                "semantic_verdict": "NOT_EVALUABLE", "protocol_errors": [str(exc)]}
    result["planner_output"] = envelope
    # 程序层（协议）：envelope 语法、plan=null 口径、空计划、引用格式、声明类字段机检。
    result["field_checks"] = field_checks(case, envelope)
    errors = protocol_errors(envelope)
    result["protocol_errors"] = errors
    declaration_failed = any(c["verdict"] == "FAIL" for c in result["field_checks"]
                             if c["field"] not in DECISION_FIELDS)
    decision_failed = any(c["verdict"] == "FAIL" for c in result["field_checks"]
                          if c["field"] in DECISION_FIELDS)
    result["protocol_verdict"] = "FAIL" if errors or declaration_failed else "PASS"
    # 语义层（裁判 + 决策类字段机检）：是否该澄清、指标/时间/计算方向是否正确。
    # 协议失败不豁免语义判分，两层结果分别展示；机检 FAIL 与程序协议判定一样不被裁判覆盖。
    judge_content = None
    try:
        judge_content, call_metadata = call_with_retries(config, judge_messages(case, attempt, envelope, prompt))
        if isinstance(judge_content, str):
            judge_content = judge_content.replace(config.api_key, "[REDACTED]")
        result["judge_call"] = call_metadata
        criteria = validate_judgment(parse_model_object(judge_content), case)
        result["criteria"] = criteria
        verdicts = {c["verdict"] for c in criteria}
        result["semantic_verdict"] = "FAIL" if "FAIL" in verdicts or decision_failed else "UNCERTAIN" if "UNCERTAIN" in verdicts else "PASS"
    except EvalError as exc:
        result.update(semantic_verdict="FAIL" if decision_failed else "JUDGE_ERROR", error=str(exc))
    # Keep the returned text for re-auditing, but remove the configured credential
    # even if a broken upstream were to echo it in its response.
    if judge_content is not None:
        result["judge_content"] = judge_content.replace(config.api_key, "[REDACTED]")
    if result["protocol_verdict"] == "FAIL":
        result["verdict"] = "FAIL"
    elif result["semantic_verdict"] == "JUDGE_ERROR":
        result["verdict"] = "JUDGE_ERROR"
    else:
        result["verdict"] = result["semantic_verdict"]
    return result


def grade_record(record, config, prompt):
    case = record["case"]
    output = {"case_id": case["id"], "title": case["title"], "question": case["question"],
              "case_hash": digest(case), "trace_id": record.get("trace_id"),
              "source_kind": record.get("source_kind"), "judge_model": config.model,
              "judge_prompt_hash": digest(prompt), "judge_config_hash": digest(config.public()),
              "grader_script_hash": digest(Path(__file__).read_text(encoding="utf-8")), "stages": {}}
    if record.get("collection_error"):
        output.update(verdict="COLLECTION_ERROR", error=record["collection_error"])
        return output
    planner = record.get("planner") or {}
    attempts = planner.get("attempts") or []
    output["planner_calls"] = len(attempts)
    output["repair_calls"] = max(0, len(attempts) - 1)
    output["trace_metadata"] = planner.get("trace_metadata", {})
    output["server_decision_events"] = planner.get("decision_events", [])
    output["server_accepted"] = planner.get("server_accepted")
    if not attempts:
        output.update(verdict="NOT_EVALUABLE", error="trace 没有 planner LLM_CALL；不能从 answer 推断 plan")
        return output
    first = grade_attempt(case, attempts[0], config, prompt)
    final = copy.deepcopy(first) if len(attempts) == 1 else grade_attempt(case, attempts[-1], config, prompt)
    output["stages"] = {"first": first, "final": final}
    output["verdict"] = final["verdict"]
    if final["verdict"] == "PASS" and planner.get("server_accepted") is False:
        output.update(verdict="PLANNER_REJECTED", error="裁判语义判PASS，但服务端未接受该计划；请查看PLAN校验事件")
    return output


def collect_case(case, target_url, timeout):
    record = {"format_version": FORMAT_VERSION, "case": copy.deepcopy(case),
              "case_hash": digest(case), "source_kind": "live_ask", "target_url": target_url,
              "setup_results": []}
    conversation_id = None
    try:
        questions = [*case.get("setup_questions", []), case["question"]]
        for turn_index, question in enumerate(questions):
            payload = {"question": question, "timezone": case["timezone"]}
            if conversation_id:
                payload["conversationId"] = conversation_id
            response, duration = request_json(target_url + "/api/ask", payload, timeout=timeout)
            if response.get("busy"):
                raise EvalError("目标会话 busy，本轮没有执行")
            conversation_id = response.get("conversationId")
            trace_id = response.get("traceId")
            if not isinstance(conversation_id, str) or not isinstance(trace_id, str) or not trace_id:
                raise EvalError("/api/ask 未返回 conversationId/traceId")
            if "error" in response:
                raise EvalError("/api/ask 返回 error")
            trace, _ = request_json(target_url + "/api/trace_detail/" + urllib.parse.quote(trace_id, safe=""), timeout=timeout)
            turn = {"question": question, "response": response, "trace": trace, "wall_seconds": duration}
            if turn_index == len(questions) - 1:
                record.update(trace_id=trace_id, response=response, trace=trace, wall_seconds=duration)
                record["planner"] = extract_planner(trace, case["question"])
            else:
                setup_planner = extract_planner(trace, question)
                if not setup_planner["attempts"] or setup_planner["server_accepted"] is not True:
                    raise EvalError("前置轮缺少 planner trace，不能建立可核验上下文")
                record["setup_results"].append(turn)
    except EvalError as exc:
        record["collection_error"] = str(exc)
    return record


def append_jsonl(path, record):
    with Path(path).open("a", encoding="utf-8") as stream:
        stream.write(dumps(record) + "\n")
        stream.flush()


def read_jsonl(path):
    records = []
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise EvalError(f"无法读取 {path}") from exc
    for index, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            record = strict_json(line)
        except ValueError as exc:
            raise EvalError(f"{path}:{index} JSON 不完整，不能静默忽略") from exc
        if not isinstance(record, dict):
            raise EvalError(f"{path}:{index} 必须为对象")
        records.append(record)
    if not records:
        raise EvalError("输入文件没有样本")
    return records


def latest_scores(output_root, source=None):
    root = Path(output_root)
    if not root.exists():
        return None
    excluded = Path(source).resolve() if source else None
    candidates = [p / "scores.jsonl" for p in root.iterdir() if p.is_dir()
                  and p.name != "_backups" and (p / "scores.jsonl").is_file()
                  and (p / "scores.jsonl").resolve() != excluded]
    return max(candidates, key=lambda p: p.stat().st_mtime_ns) if candidates else None


def validate_scores(records):
    seen = set()
    allowed = {"PASS", "FAIL", "UNCERTAIN", "COLLECTION_ERROR", "NOT_EVALUABLE", "PLANNER_ERROR", "PLANNER_REJECTED", "JUDGE_ERROR"}
    for record in records:
        case_id = record.get("case_id")
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise EvalError("scores 中 case_id 缺失或重复")
        seen.add(case_id)
        if not isinstance(record.get("title"), str) or record.get("verdict") not in allowed:
            raise EvalError("scores 中 title/verdict 非法")
        stages = record.get("stages", {})
        if not isinstance(stages, dict) or any(not isinstance(stage, dict) or stage.get("verdict") not in allowed
                                               for stage in stages.values()):
            raise EvalError("scores 中阶段结果非法")
        for stage in stages.values():
            criteria = stage.get("criteria", [])
            if not isinstance(criteria, list) or any(not isinstance(c, dict) or not isinstance(c.get("id"), str)
                    or c.get("dimension") not in DIMENSIONS or c.get("verdict") not in ("PASS", "FAIL", "UNCERTAIN")
                    or not isinstance(c.get("reason"), str) for c in criteria):
                raise EvalError("scores 中 criterion 非法")
    return records


def create_run(output_root, mode, cases, *, config=None, prompt=None, source=None):
    root = Path(output_root)
    root.mkdir(parents=True, exist_ok=True)
    existing = sorted((p for p in root.iterdir() if p.is_dir() and (p / "manifest.json").exists()),
                      key=lambda p: p.stat().st_mtime_ns)
    stamp = datetime.now(ZoneInfo("Asia/Shanghai")).strftime("%Y%m%d-%H%M%S")
    run_id = stamp + "-" + uuid.uuid4().hex[:8]
    backup = None
    if existing:
        backup = root / "_backups" / (existing[-1].name + "-before-" + run_id)
        shutil.copytree(existing[-1], backup)
    directory = root / run_id
    directory.mkdir()
    manifest = {"format_version": FORMAT_VERSION, "run_id": run_id, "mode": mode,
                "created_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
                "cases_hash": digest(cases), "script_hash": digest(Path(__file__).read_text(encoding="utf-8")),
                "coverage": coverage(cases), "previous_backup": str(backup) if backup else None,
                "source": str(Path(source).resolve()) if source else None}
    if config:
        manifest["judge"] = config.public()
    if prompt is not None:
        manifest["judge_prompt_hash"] = digest(prompt)
        (directory / "judge_prompt.txt").write_text(prompt, encoding="utf-8")
    (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    (directory / "cases.json").write_text(json.dumps({"format_version": 1, "cases": cases}, ensure_ascii=False, indent=2), encoding="utf-8")
    return directory


def markdown_cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def failure_reasons(result):
    stage = result.get("stages", {}).get("final", {})
    return [*stage.get("protocol_errors", []),
            *[f"{c['field']}: expected={c['expected']}, actual={c['actual']}" for c in stage.get("field_checks", []) if c["verdict"] == "FAIL"],
            *[f"{c['id']}: {c['reason']}" for c in stage.get("criteria", []) if c["verdict"] != "PASS"],
            *[value for value in (result.get("error"), stage.get("error")) if value]]


def compare_results(results, previous):
    def signature(item):
        return {"final": item.get("verdict"),
                "first": item.get("stages", {}).get("first", {}).get("verdict"),
                "criteria": {stage: {c["id"]: c["verdict"] for c in result.get("criteria", [])}
                             for stage, result in item.get("stages", {}).items()}}

    old = {}
    for item in previous:
        key = item.get("case_id")
        if key in old:
            raise EvalError("对照文件包含重复 case_id")
        old[key] = item
    changes = []
    for item in results:
        before = old.get(item["case_id"])
        if not before:
            changes.append({"case_id": item["case_id"], "before": "NEW", "after": item["verdict"], "comparable": False})
        elif signature(before) != signature(item) or any(before.get(k) != item.get(k) for k in
                ("case_hash", "judge_model", "judge_prompt_hash", "judge_config_hash", "grader_script_hash")):
            comparable = all(before.get(k) == item.get(k) and item.get(k) is not None
                             for k in ("case_hash", "judge_model", "judge_prompt_hash", "judge_config_hash", "grader_script_hash"))
            changes.append({"case_id": item["case_id"], "before": before.get("verdict"),
                            "after": item["verdict"], "first_before": signature(before)["first"],
                            "first_after": signature(item)["first"], "comparable": comparable,
                            "criterion_changed": signature(before)["criteria"] != signature(item)["criteria"]})
    for case_id in sorted(old.keys() - {r["case_id"] for r in results}):
        changes.append({"case_id": case_id, "before": old[case_id].get("verdict"), "after": "MISSING", "comparable": False})
    return changes


def stage_cell(stage):
    """报告单元格：总结果 + 协议/语义分列 + 出参模型身份标签。"""
    verdict = stage.get("verdict", "—")
    protocol = stage.get("protocol_verdict")
    semantic = stage.get("semantic_verdict")
    if protocol or semantic:
        verdict += f"（协议{protocol or '—'}/语义{semantic or '—'}）"
    label = stage.get("planner_label")
    return verdict + (" (" + label + ")" if label else "")


def fallback_events(result):
    """服务端非例行的兜底/观测事件（CLARIFY_FALLBACK 等）：模型输出不能据此记功。"""
    routine = {"PLAN", "PLAN_COMPILED"}
    names = [e.get("event_type") for e in result.get("server_decision_events", [])
             if e.get("event_type") not in routine]
    return ", ".join(dict.fromkeys(names)) or "—"


def write_report(directory, results, previous=None):
    counts = Counter(r["verdict"] for r in results)
    lines = ["# Planner 评测", "", f"样本数：{len(results)}；结果：{dumps(dict(counts))}", "",
             "仅评 planner；采集路径的执行层/answer 不计入本报告。PASS 表示本批已定义判据全部满足，非全能力证明。",
             "first/final 分别是首次/最后一次模型原始输出（后缀标注出参模型与思考配置；同模型不同思考档位的首排/修复轮据此区分，旧 trace 无此字段则不标）；括号内协议/语义分列：协议（plan=null 口径、空计划、字段类型、引用格式、声明类字段机检）由程序判定，语义（是否该澄清、指标/时间/计算方向、决策类字段机检）由裁判判定且机检 FAIL 优先，裁判不能覆盖程序协议判定。",
             "程序兜底列展示服务端非例行事件（如 CLARIFY_FALLBACK 程序代写澄清文案）；兜底结果不能记为模型能力。服务端是否接受独立列出，不将最后一次输出等同于修复成功。",
             "最终模型输出判PASS但服务端拒绝时，整体结果为PLANNER_REJECTED；下表阶段比例仍仅描述模型原始输出。",
             "裁判错误、trace 不完整、调用异常单列，不能视为 PASS，也不能伪装为模型语义 FAIL。", "",
             "| 阶段 | PASS | FAIL | UNCERTAIN | 其他异常 | 总样本 | PASS / 全部样本 |", "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for stage in ("first", "final"):
        values = Counter(r.get("stages", {}).get(stage, {}).get("verdict", r["verdict"]) for r in results)
        other = sum(n for verdict, n in values.items() if verdict not in ("PASS", "FAIL", "UNCERTAIN"))
        lines.append(f"| {stage} | {values['PASS']} | {values['FAIL']} | {values['UNCERTAIN']} | {other} | {len(results)} | {values['PASS'] / len(results):.1%} |")
    dimension = defaultdict(Counter)
    for item in results:
        for criterion in item.get("stages", {}).get("final", {}).get("criteria", []):
            dimension[criterion["dimension"]][criterion["verdict"]] += 1
    lines += ["", "维度计数以 criterion 为单位；未成功判分的样本不进入此表，异常数见上表。", "",
              "| 维度 | PASS | FAIL | UNCERTAIN |", "| --- | ---: | ---: | ---: |"]
    for name in DIMENSIONS:
        values = dimension[name]
        lines.append(f"| {name} | {values['PASS']} | {values['FAIL']} | {values['UNCERTAIN']} |")
    lines += ["", "| 用例 | 标题 | 首次 | 最后一次 | 服务端接受 | 程序兜底 | 修复调用数 | trace | 失败/不确定原因 |",
              "| --- | --- | --- | --- | --- | --- | ---: | --- | --- |"]
    for result in results:
        stages = result.get("stages", {})
        row = [result["case_id"], result["title"], stage_cell(stages.get("first", {})),
               stage_cell(stages.get("final", {})) + " / " + result["verdict"], result.get("server_accepted"), fallback_events(result), result.get("repair_calls", "—"), result.get("trace_id") or "—",
               "; ".join(failure_reasons(result))]
        lines.append("| " + " | ".join(map(markdown_cell, row)) + " |")
    if previous is not None:
        changes = compare_results(results, previous)
        lines += ["", "## 与上一轮逐条对照", "", "| 用例 | 上轮最终 | 本轮最终 | 上轮首次 | 本轮首次 | 判据结果变化 | 相同标注/裁判/prompt/评测代码 |", "| --- | --- | --- | --- | --- | --- | --- |"]
        for change in changes:
            lines.append("| " + " | ".join(markdown_cell(change.get(k, "—")) for k in
                         ("case_id", "before", "after", "first_before", "first_after", "criterion_changed", "comparable")) + " |")
        if not changes:
            lines.append("| — | — | — | — | — | 无 | verdict 与评测配置未变化 |")
        improvements = [c["case_id"] for c in changes if c["before"] == "FAIL" and c["after"] == "PASS"]
        lines += ["", "FAIL → PASS 独立复核清单：" + (", ".join(improvements) or "无"),
                  "每条需检查 scores 中最后一次 planner_output、criterion reason/evidence 和 raw 中实际 prompt；不能仅凭裁判 PASS 宣称修复。",
                  "本报告不评行情数值真值；若需要验证计算或源数据，使用对应执行层测试。"]
    (directory / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    with (directory / "report.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["case_id", "title", "first_verdict", "first_protocol", "first_semantic", "final_verdict", "final_protocol", "final_semantic", "overall_verdict", "server_accepted", "fallback_events", "repair_calls", "trace_id", "reason"])
        for result in results:
            first = result.get("stages", {}).get("first", {})
            final = result.get("stages", {}).get("final", {})
            writer.writerow([result["case_id"], result["title"], first.get("verdict", ""), first.get("protocol_verdict", ""), first.get("semantic_verdict", ""),
                             final.get("verdict", ""), final.get("protocol_verdict", ""), final.get("semantic_verdict", ""),
                             result["verdict"], result.get("server_accepted"), fallback_events(result), result.get("repair_calls", ""), result.get("trace_id", ""), "; ".join(failure_reasons(result))])


def parser():
    main = argparse.ArgumentParser(description=__doc__)
    sub = main.add_subparsers(dest="command", required=True)

    def common(name, help_text):
        command = sub.add_parser(name, help=help_text)
        command.add_argument("--cases", type=Path, default=DEFAULT_CASES)
        command.add_argument("--only", help="精确 case id，逗号分隔")
        return command

    common("list", "离线列出用例及覆盖")
    common("validate", "离线验证用例格式及覆盖")
    for name in ("collect", "run"):
        command = common(name, "真实调用 /api/ask；run 还会调用裁判")
        command.add_argument("--target-url", default=os.environ.get("PLANNER_EVAL_TARGET_URL"), required=not bool(os.environ.get("PLANNER_EVAL_TARGET_URL")))
        command.add_argument("--timeout", type=float, default=240)
        command.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT)
        if name == "run":
            command.add_argument("--judge-prompt", type=Path, default=DEFAULT_PROMPT)
            command.add_argument("--judge-retries", type=int, metavar="N",
                                 help="裁判传输层失败的最大重试次数；默认取 PLANNER_EVAL_JUDGE_RETRIES 或 0")
            command.add_argument("--compare", type=Path, help="上一轮 scores.jsonl")
    command = common("import-trace", "离线导入一条已下载的完整 trace")
    command.add_argument("trace", type=Path)
    command.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT)
    command = sub.add_parser("judge", help="只调用裁判，复评已保存 raw.jsonl；不请求目标系统")
    command.add_argument("raw", type=Path)
    command.add_argument("--cases", type=Path, help="显式改用新标注文件；问题/时区/前置轮必须与采集样本一致")
    command.add_argument("--only")
    command.add_argument("--judge-prompt", type=Path, default=DEFAULT_PROMPT)
    command.add_argument("--judge-retries", type=int, metavar="N",
                         help="裁判传输层失败的最大重试次数；默认取 PLANNER_EVAL_JUDGE_RETRIES 或 0")
    command.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT)
    command.add_argument("--compare", type=Path)
    command = sub.add_parser("report", help="离线重建报告及逐条对照")
    command.add_argument("scores", type=Path)
    command.add_argument("--compare", type=Path)
    command.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT)
    return main


def read_prompt(path):
    try:
        prompt = Path(path).read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise EvalError("无法读取 judge prompt") from exc
    if not prompt:
        raise EvalError("judge prompt 不能为空")
    return prompt


def main(argv=None):
    args = parser().parse_args(argv)
    if args.command in ("list", "validate", "collect", "run", "import-trace"):
        suite, cases = load_cases(args.cases, args.only)
    if args.command in ("list", "validate"):
        for case in cases:
            print(f"{case['id']}\t{case['title']}\t{len(case['criteria'])} criteria")
        print(dumps(coverage(cases)))
        return 0
    if args.command == "import-trace":
        if len(cases) != 1:
            raise EvalError("import-trace 必须用 --only 选择一条 case")
        trace = read_json(args.trace)
        if not isinstance(trace, dict):
            raise EvalError("trace 文件必须为对象")
        case = cases[0]
        planner = extract_planner(trace, case["question"])
        directory = create_run(args.output_root, args.command, cases, source=args.trace)
        append_jsonl(directory / "raw.jsonl", {"format_version": 1, "case": case,
                      "case_hash": digest(case), "source_kind": "imported_trace", "trace": trace,
                      "trace_id": planner["trace_metadata"].get("trace_id"), "planner": planner})
        print(f"已导入：{directory / 'raw.jsonl'}")
        return 0
    comparison_source = getattr(args, "compare", None)
    if comparison_source is None and args.command in ("run", "judge"):
        comparison_source = latest_scores(args.output_root)
    previous = validate_scores(read_jsonl(comparison_source)) if comparison_source else None
    if args.command == "report":
        results = validate_scores(read_jsonl(args.scores))
        directory = create_run(args.output_root, args.command, [], source=args.scores)
        for result in results:
            append_jsonl(directory / "scores.jsonl", result)
        write_report(directory, results, previous)
        print(f"报告：{directory / 'report.md'}")
        return 0
    config = JudgeConfig.from_env() if args.command in ("run", "judge") else None
    if config is not None and getattr(args, "judge_retries", None) is not None:
        if args.judge_retries < 0:
            raise EvalError("--judge-retries 必须为非负整数")
        config = replace(config, retries=args.judge_retries)
    prompt = read_prompt(args.judge_prompt) if config else None
    if args.command == "judge":
        records = read_jsonl(args.raw)
        if any(r.get("format_version") != 1 or not isinstance(r.get("case"), dict) for r in records):
            raise EvalError("raw 必须由本脚本 collect/import-trace 生成")
        # By default judge the captured labels. Explicit --cases supports label
        # corrections without calling the target system again.
        captured = {"format_version": 1, "cases": [r["case"] for r in records]}
        _, cases = validate_suite(captured, args.only)
        records = [r for r in records if r["case"]["id"] in {c["id"] for c in cases}]
        for record in records:
            if record.get("case_hash") != digest(record["case"]):
                raise EvalError("raw 中标注已修改或缺少指纹；请显式重新采集/导入后再评测")
            if not record.get("collection_error"):
                if not isinstance(record.get("trace"), dict):
                    raise EvalError("raw 缺少完整原始 trace")
                record["planner"] = extract_planner(record["trace"], record["case"]["question"])
        if args.cases:
            _, replacements = load_cases(args.cases, args.only)
            replacements = {c["id"]: c for c in replacements}
            for record in records:
                replacement = replacements.get(record["case"]["id"])
                if replacement is None:
                    raise EvalError("新标注文件缺少已采集 case")
                for key in ("question", "timezone", "setup_questions"):
                    default = [] if key == "setup_questions" else None
                    if record["case"].get(key, default) != replacement.get(key, default):
                        raise EvalError("修改 query/时区/前置轮后必须重新采集，不能复用旧 trace")
                record["previous_case_hash"] = record["case_hash"]
                record["case"] = copy.deepcopy(replacement)
                record["case_hash"] = digest(replacement)
            cases = [r["case"] for r in records]
        if not records:
            raise EvalError("没有选中样本")
        directory = create_run(args.output_root, args.command, cases, config=config, prompt=prompt, source=args.raw)
    else:
        target_url = validate_url(args.target_url, "target-url")
        if not math.isfinite(args.timeout) or args.timeout <= 0:
            raise EvalError("timeout 必须为正数秒")
        directory = create_run(args.output_root, args.command, cases, config=config, prompt=prompt)
        print("正在采集完整问答；每条含前置轮可能耗时数分钟。逐条落盘，建议全量放后台。", flush=True)
        records = (collect_case(case, target_url, args.timeout) for case in cases)
    if comparison_source:
        print(f"逐条对照：{comparison_source}", flush=True)
    results = []
    collection_failed = False
    for record in records:
        append_jsonl(directory / "raw.jsonl", record)
        if args.command == "collect":
            verdict = "COLLECTION_ERROR" if record.get("collection_error") else "COLLECTED"
            collection_failed |= bool(record.get("collection_error"))
        else:
            result = grade_record(record, config, prompt)
            append_jsonl(directory / "scores.jsonl", result)
            results.append(result)
            verdict = result["verdict"]
            write_report(directory, results, previous)
        print(f"{record['case']['id']} {verdict}", flush=True)
    print(f"产物：{directory}", flush=True)
    return 1 if collection_failed or any(r["verdict"] != "PASS" for r in results) else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (EvalError, OSError) as exc:
        print("错误：" + str(exc), file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        print("已停止；已完成样本保留在本轮产物目录。", file=sys.stderr)
        sys.exit(130)
