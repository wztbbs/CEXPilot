#!/usr/bin/env python3
"""旧 trace + 本地新模板，单次直调 LLM；不执行 ask、工具或自动修复。

python3 scripts/replay_trace.py <traceId>       # key: LLM_NORMAL_API_KEY
python3 scripts/replay_trace.py <traceId> --dry-run
详见 docs/提示词离线重放.md。仅 Python 标准库，无 import 时网络请求。
"""
from __future__ import annotations

import argparse
import copy
import difflib
import json
import os
import re
import subprocess
import sys
import uuid
from datetime import datetime
from pathlib import Path

from planner_eval import EvalError, json_value, ordered_events, read_json, request_json, strict_json, validate_url

ROOT = Path(__file__).resolve().parents[1]
PROMPTS = Path("src/main/resources/prompts")
OUTPUT = ROOT / "importants/回归测试/prompt-replay"
TEMPLATES = {
    "planner": ("dag_planner", "clarification_repair"),
    "router": ("intent_router",),
    "answer": ("agent_system",),
    "monitor": ("monitor_planner",),
}
STAGES = {"planner": "dag_planner", "router": "router", "answer": "answer", "monitor": "monitor_planner"}
VARIABLES = {
    "dag_planner": ("concepts", "metrics", "operators", "max_nodes", "max_depth", "conversation_context"),
    "clarification_repair": ("concepts", "metrics"),
    "intent_router": (),
    "agent_system": ("time_context", "conversation_context"),
    "monitor_planner": ("concepts", "metrics", "operators"),
}
PARAMETERS = (
    "model", "temperature", "seed", "response_format", "enable_thinking", "thinking", "reasoning_effort",
    "max_tokens", "max_completion_tokens", "top_p", "stop", "frequency_penalty", "presence_penalty",
)


def select_call(trace, stage, attempt):
    metadata = trace.get("trace")
    if not isinstance(metadata, dict):
        raise EvalError("trace.trace 缺失")
    calls = [e for e in ordered_events(trace)
             if e.get("event_type") == "LLM_CALL" and e.get("name") == STAGES[stage]]
    if not 1 <= attempt <= len(calls):
        raise EvalError(f"{stage} 共 {len(calls)} 次调用，无法选择第 {attempt} 次")
    event = calls[attempt - 1]
    inputs = json_value(event.get("input_json"), "LLM_CALL.input_json")
    messages = inputs.get("messages")
    if not isinstance(messages, list) or not messages or any(
            not isinstance(m, dict) or m.get("role") not in ("system", "user", "assistant")
            or not isinstance(m.get("content"), str) or m.get("tool_calls") for m in messages):
        raise EvalError("trace 缺少完整文本 messages；无法可靠重放，不使用 question 猜造输入")
    if sum(m["role"] == "system" for m in messages) != 1 or not any(m["role"] == "user" for m in messages):
        raise EvalError("重放要求一个 system 消息及至少一个 user 消息")
    return metadata, event, inputs


def extract_variables(template, rendered, names):
    """用旧版本模板精确反解插值，保留 {{m1.binance.value}} 等 DAG 引用原文。"""
    token = re.compile(r"\{\{(" + "|".join(map(re.escape, names)) + r")\}\}") if names else None
    pieces, seen, position = [], set(), 0
    for match in token.finditer(template) if token else ():
        name = match.group(1)
        pieces.append(re.escape(template[position:match.start()]))
        pieces.append(f"(?P={name})" if name in seen else f"(?P<{name}>.*?)")
        seen.add(name)
        position = match.end()
    pieces.append(re.escape(template[position:]))
    matched = re.fullmatch("".join(pieces), rendered, re.S)
    return matched.groupdict() if matched else None


def git_template(root, commit, name):
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-fA-F]{7,40}", commit):
        return None
    result = subprocess.run(["git", "show", f"{commit}:{(PROMPTS / (name + '.txt')).as_posix()}"],
                            cwd=root, capture_output=True, text=True, encoding="utf-8", check=False)
    return result.stdout if result.returncode == 0 else None


def render_new(template, variables):
    # 单次替换：不能把旧上下文/目录内部的 {{...}} 再当模板变量展开。
    names = set(re.findall(r"\{\{([a-z_][a-z_0-9]*)\}\}", template))
    missing = names - variables.keys()
    if missing:
        raise EvalError("新模板新增了无法从 trace 恢复的变量：" + ", ".join(sorted(missing)))
    return re.sub(r"\{\{([a-z_][a-z_0-9]*)\}\}", lambda m: variables[m.group(1)], template)


def replace_prompt(metadata, inputs, stage, *, root=ROOT, prompt=None, old_template=None):
    messages = copy.deepcopy(inputs["messages"])
    system = next(m for m in messages if m["role"] == "system")
    old_system = system["content"]
    for name in TEMPLATES[stage]:
        local = Path(prompt) if prompt else root / PROMPTS / (name + ".txt")
        if not local.is_file():
            raise EvalError(f"本地新模板不存在：{local}")
        current = local.read_text(encoding="utf-8")
        if old_template:
            candidates = [Path(old_template).read_text(encoding="utf-8")]
        else:
            previous = git_template(root, metadata.get("build_commit"), name)
            # 同版本或旧 commit 不可用时，仅当当前模板能完整匹配旧输入，才允许回退。
            candidates = [previous, current] if previous is not None else [current]
        for previous in candidates:
            variables = extract_variables(previous, old_system, VARIABLES[name])
            if variables is None:
                continue
            new_system = render_new(current, variables)
            system["content"] = new_system
            return messages, {"template": name, "template_file": str(local),
                              "system_changed": old_system != new_system,
                              "preserved_variables": sorted(variables),
                              "system_diff": "".join(difflib.unified_diff(
                                  old_system.splitlines(keepends=True), new_system.splitlines(keepends=True),
                                  fromfile="trace/system", tofile="local/system"))}
    raise EvalError("无法用旧模板完整还原 trace 的 system prompt。请获取 trace.build_commit 对应 Git 历史，"
                    "或用 --old-template 指定部署时的原始模板；不能用新版模板猜旧上下文")


def build_request(metadata, inputs, messages, model=None):
    nested = inputs.get("request_parameters") or {}
    if not isinstance(nested, dict):
        raise EvalError("request_parameters 必须为对象")
    parameters = {**nested, **{k: v for k, v in inputs.items() if k in PARAMETERS}}
    body = {k: copy.deepcopy(parameters[k]) for k in PARAMETERS if parameters.get(k) is not None}
    body["model"] = model or body.get("model") or metadata.get("model")
    if not isinstance(body["model"], str) or not body["model"].strip():
        raise EvalError("trace 未记录模型，请用 --model 指定")
    body.setdefault("temperature", 0)
    body.update(messages=messages, stream=False)
    return body


def refresh_parameter_protocol(body, stage, keep_trace_schema=False):
    """同步本地参数操作协议，保留历史指标/算子 schema，避免旧 schema 强制模型输出旧字段。"""
    response_format = body.get("response_format") or {}
    if keep_trace_schema or stage != "planner" or response_format.get("type") != "json_schema":
        return False
    schema = response_format.get("json_schema", {}).get("schema", {})
    properties = schema.get("properties", {})
    if "plan" not in properties:
        return False  # 澄清专项修复等其他信封不改。
    local = read_json(ROOT / "src/main/resources/metrics/plan-schema.json")
    old = copy.deepcopy(properties)
    properties.pop("explicit_conditions", None)
    properties["condition_updates"] = copy.deepcopy(local["properties"]["condition_updates"])
    schema["required"] = [key for key in schema.get("required", []) if key != "explicit_conditions"]
    return old != properties


def safe_text(text, secret):
    if secret:
        # JSON 转义后的回显也需脱敏，例如 key 中包含引号或非 ASCII 字符。
        for variant in sorted({secret, json.dumps(secret)[1:-1], json.dumps(secret, ensure_ascii=False)[1:-1]}, key=len, reverse=True):
            text = text.replace(variant, "[REDACTED]")
    return text


def redacted(value, secret):
    if isinstance(value, str):
        return safe_text(value, secret)
    if isinstance(value, list):
        return [redacted(item, secret) for item in value]
    if isinstance(value, dict):
        return {safe_text(key, secret): redacted(item, secret) for key, item in value.items()}
    return value


def write_artifact(directory, record, secret):
    directory.mkdir(parents=True, exist_ok=True)
    filename = directory / (datetime.now().strftime("%Y%m%d-%H%M%S-%f") + "-" + uuid.uuid4().hex[:8] + ".json")
    # 不写 Authorization；即使对端把 key 回显进响应，也不落盘/打印明文。
    data = json.dumps(redacted(record, secret), ensure_ascii=False, indent=2)
    descriptor = os.open(filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(data + "\n")
    return filename


def parser():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("traceid", help="历史 trace UUID")
    ap.add_argument("--stage", choices=tuple(STAGES), default="planner", help="默认 planner；可重放路由/回答/监控规划")
    ap.add_argument("--attempt", type=int, default=1, help="该阶段第几次 LLM_CALL，默认首轮")
    ap.add_argument("--api-key-env", default="LLM_NORMAL_API_KEY", help="仅传环境变量名，不传密钥")
    ap.add_argument("--base-url", default=os.environ.get("LLM_NORMAL_BASE_URL", "https://tokenhub-intl.tencentmaas.com/v1"))
    ap.add_argument("--trace-base", default=os.environ.get("CEXPILOT_BASE", "https://cex.hrscrm.net"))
    ap.add_argument("--model", default=os.environ.get("LLM_NORMAL_MODEL", "").strip() or None,
                    help="模型覆盖；优先级 --model > LLM_NORMAL_MODEL > trace 模型")
    ap.add_argument("--prompt", type=Path, help="默认读取仓库当前阶段的提示词，可指定候选模板")
    ap.add_argument("--old-template", type=Path, help="旧 commit 不可用时，提供部署时的原始模板以恢复插值")
    ap.add_argument("--trace-file", type=Path, help="使用本地完整 trace JSON，不再 GET 线上 trace")
    ap.add_argument("--output-dir", type=Path, default=OUTPUT)
    ap.add_argument("--timeout", type=int, default=180)
    ap.add_argument("--dry-run", action="store_true", help="只组装并保存请求，不调用 LLM，也不要求 key")
    ap.add_argument("--keep-trace-schema", action="store_true", help="不更新参数操作 schema，仅用于严格重放旧协议")
    return ap


def main(argv=None):
    args = parser().parse_args(argv)
    key = os.environ.get(args.api_key_env, "").strip()
    try:
        try:
            traceid = str(uuid.UUID(args.traceid))
        except ValueError:
            raise EvalError("traceid 必须为 UUID") from None
        if args.attempt < 1 or args.timeout < 1:
            raise EvalError("attempt 和 timeout 必须大于 0")
        if not args.dry_run and (not key or any(c.isspace() for c in key)):
            raise EvalError(f"请设置环境变量 {args.api_key_env} 为有效 API key；不接受命令行明文 key")
        base = validate_url(args.base_url, "LLM base URL")
        trace_base = validate_url(args.trace_base, "trace base URL")
        trace = read_json(args.trace_file) if args.trace_file else request_json(
            trace_base + "/api/trace_detail/" + traceid, timeout=args.timeout)[0]
        if not isinstance(trace, dict) or not isinstance(trace.get("trace"), dict) or trace["trace"].get("trace_id") != traceid:
            raise EvalError("返回的 trace_id 与请求不一致，或 trace 不存在")
        metadata, event, inputs = select_call(trace, args.stage, args.attempt)
        messages, replacement = replace_prompt(metadata, inputs, args.stage, prompt=args.prompt, old_template=args.old_template)
        body = build_request(metadata, inputs, messages, args.model)
        schema_updated = refresh_parameter_protocol(body, args.stage, args.keep_trace_schema)
        previous = json_value(event.get("output_json"), "LLM_CALL.output_json")
        record = {"trace_id": traceid, "question": metadata.get("question"), "build_commit": metadata.get("build_commit"),
                  "replayed_at": datetime.now().astimezone().isoformat(), "llm_base_url": base,
                  "stage": args.stage, "attempt": args.attempt, "source_event_seq": event.get("seq"),
                  "mode": "dry_run" if args.dry_run else "single_llm_call",
                  "parameter_schema_updated": schema_updated,
                  "prompt": replacement, "request": body, "previous_response": previous,
                  "previous_error": event.get("error"), "business_validation": "NOT_RUN"}
        def say(text):
            print(safe_text(str(text), key))
        say(f"query: {metadata.get('question')}\nstage={args.stage} attempt={args.attempt} model={body['model']}")
        say(f"模板：{replacement['template_file']}；历史上下文/目录/时间保持原值")
        if schema_updated:
            say("已同步本地 condition_updates 输出协议；历史指标与算子 schema 保持原值。")
        if not replacement["system_changed"]:
            say("提示：system prompt 与旧 trace 相同，本次只是在相同输入下再次采样。")
        if args.dry_run:
            say(replacement["system_diff"] or "无提示词差异")
        else:
            url = base if base.endswith("/chat/completions") else base + "/chat/completions"
            try:
                response, seconds = request_json(url, body, timeout=args.timeout, api_key=key)
            except EvalError as exc:
                record["call_error"] = str(exc)
                say(f"失败记录：{write_artifact(args.output_dir, record, key)}")
                raise
            record.update(response=response, duration_ms=round(seconds * 1000))
            choices = response.get("choices")
            if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict) or not isinstance(choices[0].get("message"), dict):
                record["call_error"] = "LLM 未返回 choices[0].message"
                say(f"失败记录：{write_artifact(args.output_dir, record, key)}")
                raise EvalError(record["call_error"])
            message = choices[0]["message"]
            content = message.get("content")
            say("\n旧响应：\n" + str(previous.get("content", "（无响应）")))
            say("\n新响应：\n" + str(content or message))
            say(f"\n耗时：{record['duration_ms']}ms；finish_reason={choices[0].get('finish_reason')}；usage="
                + json.dumps(response.get("usage"), ensure_ascii=False))
            # 只检查格式，不把 JSON 可解析误报为 Java 校验或业务预期通过。
            try:
                record["parsed_content"] = strict_json(content)
                record["json_parse"] = "VALID_JSON"
            except (ValueError, TypeError):
                record["json_parse"] = "NOT_JSON"
            say("未执行 Java 计划校验、工具取数或后续回答；请对照预期查看新响应。")
        say(f"保存：{write_artifact(args.output_dir, record, key)}")
        return 0
    except (EvalError, OSError) as exc:
        # OSError 的任意路径/异常上下文可能含秘密，统一只暴露异常类型。
        print(safe_text(str(exc) if isinstance(exc, EvalError) else f"本地文件或 Git 操作失败：{type(exc).__name__}", key), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
