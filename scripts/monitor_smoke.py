#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
监控（monitor agent）线上冒烟：2 个 query 覆盖 规划 → 草稿 → 确认 → 排期执行 → 告警 → 邮件。

只依赖标准库，不装 requests。

用法：
    export MONITOR_ACCESS_TOKEN='<32 位以上凭证>'
    export MONITOR_SMOKE_EMAIL='you@example.com'        # 收件人；RESEND_DRY_RUN=true 时不会真发
    python3 scripts/monitor_smoke.py --base https://<你的实例域名>

    python3 scripts/monitor_smoke.py --only C2          # 只跑第 2 条 query
    python3 scripts/monitor_smoke.py --wait 600         # 等首轮执行的秒数（默认 420）
    python3 scripts/monitor_smoke.py --stop-all         # 结束时停掉两条规则
    python3 scripts/monitor_smoke.py --skip-wait        # 只验结构，不等执行（不碰行情/邮件）

两个用例的分工：
    C1  真实条件（BTC 5 分钟跌幅 > 2%）：验「自然语言 → 规则 → 排期 → 执行 DONE」。
        阈值大概率不命中，所以不产出告警，这是预期行为。
    C2  必定命中条件（BTC 价格 > 10000 USDT，当前行情恒真）：把「命中 → event → Outbox
        → DRY_RUN 投递 SENT」这条只能在命中时才走的路补上。验完默认停掉这条规则。

前置条件（缺一条脚本就跑不通）：
    monitor.enabled=true、MONITOR_ACCESS_TOKEN、RESEND_DRY_RUN=true、有效的 MONITOR_MAIL_FROM。
"""

import argparse
import json
import os
import sys
import time
import uuid
import urllib.error
import urllib.request
from datetime import datetime, timezone

TIMEOUT = 240


class ApiError(Exception):
    def __init__(self, code, body, path):
        self.code = code
        self.body = body
        self.path = path
        super().__init__("HTTP %s %s %s" % (code, path, body[:200]))


class Api(object):
    """全部请求都带同一个访客 cookie；monitor-flow 的写操作另带 X-Monitor-Action。"""

    def __init__(self, base, visitor, token=None):
        self.base = base.rstrip("/")
        self.visitor = visitor
        self.token = token

    def call(self, method, path, body=None, action=False, token=False, timeout=TIMEOUT):
        url = self.base + path
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Accept", "application/json")
        request.add_header("Cookie", "cexpilot_uid=" + self.visitor)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if action:
            request.add_header("X-Monitor-Action", "1")
        if token:
            request.add_header("Authorization", "Bearer " + (self.token or ""))
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read().decode("utf-8", "replace")
                return response.status, _json(raw)
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", "replace")
            return error.code, _json(raw)
        except urllib.error.URLError as error:
            raise ApiError(0, "连接失败: %s" % error.reason, path)


def _json(raw):
    try:
        return json.loads(raw)
    except ValueError:
        return {"_raw": raw}


def now_iso():
    return datetime.now(timezone.utc).strftime("%H:%M:%S")


class Steps(object):
    def __init__(self, key):
        self.key = key
        self.rows = []

    def ok(self, name, detail=""):
        self.rows.append((name, True, detail))
        print("  ✓ %-22s %s" % (name, detail))

    def fail(self, name, detail=""):
        self.rows.append((name, False, detail))
        print("  ✗ %-22s %s" % (name, detail))

    def fatal(self, name, detail=""):
        """致命步骤：后续步骤不再执行。"""
        self.fail(name, detail)
        raise StepFailed(name + "：" + detail)

    @property
    def passed(self):
        return bool(self.rows) and all(row[1] for row in self.rows)


class StepFailed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def text(value, limit=160):
    if value is None:
        return ""
    value = str(value).replace("\n", " / ")
    return value[:limit] + ("…" if len(value) > limit else "")


# ---------------------------------------------------------------------------
# 预检：健康接口是唯一能证明「进程活着 + 定时器在跑」的入口
# ---------------------------------------------------------------------------

def preflight(api, since_hours=1):
    print("\n[0/3] 预检 /api/monitor-health")
    code, body = api.call("GET", "/api/monitor-health?sinceHours=%d" % since_hours, token=True)
    if code == 404:
        raise SystemExit(
            "  该实例没有 /api/monitor-health（Spring 返回 404）：\n"
            "  → 线上跑的还是旧 jar，或请求打到了另一台实例。请确认部署已重启、base url 正确。")
    if code == 401:
        raise SystemExit("  401：MONITOR_ACCESS_TOKEN 与线上不一致。")
    if code == 503 and isinstance(body, dict) and "error" in body:
        raise SystemExit("  503：%s（monitor.enabled 未打开或凭证未配置）" % body.get("error"))
    if code != 200:
        raise SystemExit("  预检失败 HTTP %s：%s" % (code, text(body)))

    checks = body.get("checks", 0)
    ran = body.get("ran", 0)
    runtime = body.get("runtime") or {}
    schedule = runtime.get("schedule") or {}
    mail = runtime.get("mail") or {}
    print("  enabled=%s status=%s mailMode=%s 检查 %s/%s"
          % (body.get("enabled"), body.get("status"), runtime.get("mailMode"), ran, checks))
    print("  调度心跳 %s（滞后 %ss，running=%s）" % (schedule.get("lastSucceededAt"),
                                                runtime.get("scanLagSeconds"), schedule.get("running")))
    print("  邮件心跳 %s（滞后 %ss）" % (mail.get("lastSucceededAt"), runtime.get("mailScanLagSeconds")))

    problems = []
    if not body.get("enabled"):
        problems.append("monitor.enabled=false，任务不会被调度")
    if ran != checks:
        problems.append("巡检只跑了 %s/%s 条检查，结果不可信" % (ran, checks))
    if runtime.get("mailMode") != "DRY_RUN":
        problems.append("mailMode=%s，不是 DRY_RUN（会真发邮件）" % runtime.get("mailMode"))
    if schedule.get("lastSucceededAt") is None:
        problems.append("调度心跳为空：定时器没跑起来")
    elif (runtime.get("scanLagSeconds") or 0) > 120:
        problems.append("调度心跳滞后 %ss：定时器可能已停" % runtime.get("scanLagSeconds"))
    if mail.get("lastSucceededAt") is None:
        problems.append("邮件心跳为空：通知 worker 没跑起来")
    if problems:
        for problem in problems:
            print("  ! " + problem)
        raise SystemExit("  预检不通过，先修配置再看业务链路。")
    print("  预检通过")
    return body


# ---------------------------------------------------------------------------
# 用例定义
# ---------------------------------------------------------------------------

CASES = [
    {
        "key": "C1",
        "title": "真实条件：BTC 最近 5 分钟跌幅 > 2%",
        "question": "帮我盯一下 BTCUSDT 永续合约，最近5分钟价格跌幅超过2%就发邮件提醒我",
        "expect_alert": False,
        "stop_after": False,
    },
    {
        "key": "C2",
        "title": "必定命中：BTC 价格 > 10000 USDT（当前行情恒真）",
        "question": "监控 BTCUSDT 永续合约，如果价格高于10000 USDT 就发邮件提醒我",
        "expect_alert": True,
        "stop_after": True,
    },
]


def bind_email(api, email):
    code, body = api.call("PUT", "/api/monitor-flow/profile", {"email": email}, action=True)
    require(code == 200, "绑定邮箱失败 HTTP %s %s" % (code, text(body)))
    require((body or {}).get("emailBound") is True, "绑定后 emailBound 仍为 false")
    require((body or {}).get("email") == email, "绑定后返回的邮箱不一致：%s" % body.get("email"))
    return body


def poll(fn, timeout, interval=10, label=""):
    """轮询到 fn 返回非 None，超时返回 None。"""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        print("    … %s 等待中（剩 %ds）" % (label, int(deadline - time.time())))
        time.sleep(interval)
    return None


def run_case(api, case, args, steps):
    print("\n[%s] %s" % (case["key"], case["title"]))
    print("  query: %s" % case["question"])

    # ① 提问 → monitor 候选
    conversation = str(uuid.uuid4())
    code, answer = api.call("POST", "/api/ask", {
        "conversationId": conversation,
        "question": case["question"],
        "timezone": "Asia/Shanghai",
    })
    if code != 200:
        steps.fatal("① 提问", "HTTP %s %s" % (code, text(answer)))
    agent = answer.get("type")
    planning = answer.get("planning") or {}
    card = planning.get("confirmation")
    if agent != "monitor" or not card:
        steps.fatal("① 路由到监控",
                    "type=%s status=%s reply=%s trace=%s/api/trace/%s"
                    % (agent, planning.get("status"), text(answer.get("answer")),
                       api.base, answer.get("traceId")))
    steps.ok("① 路由到监控", "status=%s ruleId=%s v%s hits=%s"
             % (planning.get("status"), card.get("ruleId"), card.get("version"), card.get("requiredHits")))

    rule_id = card["ruleId"]
    version = card["version"]
    spec_hash = card["specHash"]

    # ② 草稿态：规则说明可读、可执行、但还没有排期
    require(card.get("status") == "PENDING_CONFIRMATION", "草稿状态不是 PENDING_CONFIRMATION：%s" % card.get("status"))
    require(card.get("mailMode") == "DRY_RUN", "card.mailMode=%s" % card.get("mailMode"))
    require(card.get("executionAvailable") is True,
            "executionAvailable=false：%s" % card.get("unavailableReason"))
    require(card.get("checkIntervalSeconds") == 300, "检查间隔不是 300s")
    require(bool(card.get("rules")), "规则说明为空")
    require(len(spec_hash) == 64, "specHash 长度异常：%s" % spec_hash)
    steps.ok("② 草稿可执行", "规则 %d 条，%s" % (len(card["rules"]), text(card["rules"][0].get("description"), 60)))

    code, executions = api.call("GET", "/api/monitor-flow/rules/%s/executions" % rule_id)
    require(code == 200, "读取 executions 失败 HTTP %s" % code)
    if executions:
        steps.fail("② 草稿不排期", "未确认就已有 %d 条 execution" % len(executions))
    else:
        steps.ok("② 草稿不排期", "executions=0")

    # ③ 确认前必须绑邮箱，否则 canConfirm=false（未绑就能确认＝闸门失效）
    if not (card.get("profile") or {}).get("emailBound"):
        require(card.get("canConfirm") is False, "未绑邮箱却 canConfirm=true，确认闸门失效")
        steps.ok("③ 未绑邮箱不可确认", "canConfirm=false")
        bind_email(api, args.email)
        steps.ok("③ 绑定邮箱", args.email)
    card = fetch_card(api, rule_id)
    require(card.get("canConfirm") is True,
            "canConfirm=false（emailBound=%s executionAvailable=%s）"
            % ((card.get("profile") or {}).get("emailBound"), card.get("executionAvailable")))
    steps.ok("③ 可确认", "canConfirm=true 收件人=%s" % (card.get("profile") or {}).get("email"))

    # ④ 确认启用
    code, card = api.call("POST", "/api/monitor-flow/rules/%s/confirm" % rule_id,
                          {"version": version, "specHash": spec_hash,
                           "recipient": (card.get("profile") or {}).get("email")}, action=True)
    if code != 200:
        steps.fatal("④ 确认启用", "HTTP %s %s" % (code, text(card)))
    require(card.get("status") == "ACTIVE", "确认后状态为 %s" % card.get("status"))
    steps.ok("④ 确认启用", "status=ACTIVE v%s" % version)

    # ⑤ 重复确认必须幂等（不能重置排期或命中的连续计数）
    code, again = api.call("POST", "/api/monitor-flow/rules/%s/confirm" % rule_id,
                           {"version": version, "specHash": spec_hash,
                            "recipient": (card.get("profile") or {}).get("email")}, action=True)
    require(code == 200 and (again or {}).get("status") == "ACTIVE",
            "重复确认异常：HTTP %s %s" % (code, text(again)))
    steps.ok("⑤ 重复确认幂等", "仍为 ACTIVE，未报错")

    if args.skip_wait:
        print("  --skip-wait：跳过执行与告警等待")
        return result(case, rule_id, steps)

    # ⑥ 等首轮执行落库（排期游标立刻到期，一次扫描内就会派发）
    execution = poll(lambda: newest_terminal(api, rule_id), args.wait, label="首轮执行")
    if not execution:
        steps.fatal("⑥ 首轮执行", "%ds 内没有终态 execution（查看 /api/monitor-flow/rules/%s/executions）" % (args.wait, rule_id))
    status = execution.get("status")
    if status != "DONE":
        steps.fatal("⑥ 首轮执行", "status=%s lastError=%s" % (status, text(execution.get("lastError"))))
    scheduled = execution.get("scheduledAt") or ""
    require(_aligned(scheduled), "scheduledAt 不在 300s 网格上：%s" % scheduled)
    steps.ok("⑥ 首轮执行", "DONE slot=%s attempts=%s nodes=%s"
             % (scheduled, execution.get("attemptCount"), execution.get("checkpointNodeCount")))

    # ⑦ 告警与投递：C1 预期不命中，C2 必须命中且 DRY_RUN 投递成功
    if case["expect_alert"]:
        event = poll(lambda: sent_event(api, rule_id), min(args.wait, 180), interval=10, label="告警投递")
        if not event:
            steps.fatal("⑦ 告警投递", "命中后没有 SENT 的 event（DRY_RUN 下应为 SENT + simulated=true）")
        require(event.get("simulated") is True,
                "投递成功但 simulated=false（会真发邮件）providerMessageId=%s" % event.get("providerMessageId"))
        steps.ok("⑦ 告警投递", "delivery=SENT simulated=true attempts=%s" % event.get("attemptCount"))
    else:
        events = api.call("GET", "/api/monitor-flow/rules/%s/events" % rule_id)[1]
        if events:
            steps.ok("⑦ 告警投递", "意外命中 %d 次，delivery=%s" % (len(events), events[0].get("deliveryStatus")))
        else:
            steps.ok("⑦ 告警投递", "未命中（预期），无 event")

    if case["stop_after"] and not args.keep:
        code, body = api.call("POST", "/api/monitor-flow/rules/%s/stop" % rule_id,
                              {"version": version, "specHash": spec_hash}, action=True)
        if code == 200:
            steps.ok("⑧ 停止规则", "已停止（合成条件用后即弃）")
        else:
            steps.fail("⑧ 停止规则", "HTTP %s %s" % (code, text(body)))
    return result(case, rule_id, steps)


def result(case, rule_id, steps):
    return {"key": case["key"], "ruleId": rule_id, "passed": steps.passed}


def newest_terminal(api, rule_id):
    code, executions = api.call("GET", "/api/monitor-flow/rules/%s/executions" % rule_id)
    if code != 200 or not executions:
        return None
    newest = executions[0]
    return newest if newest.get("status") in ("DONE", "FAILED", "CANCELLED") else None


def sent_event(api, rule_id):
    code, events = api.call("GET", "/api/monitor-flow/rules/%s/events" % rule_id)
    if code != 200 or not events:
        return None
    for event in events:
        if event.get("deliveryStatus") in ("SENT", "FAILED", "UNKNOWN"):
            return event
    return None


def fetch_card(api, rule_id):
    code, card = api.call("GET", "/api/monitor-flow/rules/%s" % rule_id)
    require(code == 200, "读取规则卡片失败 HTTP %s %s" % (code, text(card)))
    return card


def _aligned(scheduled):
    try:
        stamp = datetime.fromisoformat(scheduled.replace("Z", "+00:00"))
    except ValueError:
        return False
    return int(stamp.timestamp()) % 300 == 0


# ---------------------------------------------------------------------------
# 收尾：巡检确认这轮操作没有留下不一致数据
# ---------------------------------------------------------------------------

def verify_health(api, rules, steps):
    print("\n[3/3] 复检 /api/monitor-health")
    for rule_id in rules:
        code, body = api.call("GET", "/api/monitor-health?sinceHours=1&ruleId=%s" % rule_id, token=True)
        if code != 200:
            steps.fail("巡检(%s)" % rule_id[:8], "HTTP %s %s" % (code, text(body)))
            continue
        if body.get("ran") != body.get("checks"):
            steps.fail("巡检(%s)" % rule_id[:8], "只跑了 %s/%s 条检查" % (body.get("ran"), body.get("checks")))
            continue
        errors = [v for v in (body.get("violations") or []) if v.get("severity") == "ERROR"]
        warnings = [v for v in (body.get("violations") or []) if v.get("severity") != "ERROR"]
        if errors:
            steps.fail("巡检(%s)" % rule_id[:8],
                       "%d 条 ERROR：%s" % (len(errors), text(errors[0].get("code") + " " + errors[0].get("detail"))))
        else:
            detail = "0 ERROR"
            if warnings:
                detail += "，%d 条 WARN：%s" % (len(warnings), warnings[0].get("code"))
            steps.ok("巡检(%s)" % rule_id[:8], detail)


def main():
    parser = argparse.ArgumentParser(description="监控线上冒烟（2 条 query）")
    parser.add_argument("--base", default=os.environ.get("CEXPILOT_BASE", "https://cex.hrscrm.net"))
    parser.add_argument("--token", default=os.environ.get("MONITOR_ACCESS_TOKEN", ""))
    parser.add_argument("--email", default=os.environ.get("MONITOR_SMOKE_EMAIL", "monitor-smoke@hrscrm.net"))
    parser.add_argument("--visitor", default=os.environ.get("MONITOR_SMOKE_VISITOR", ""),
                        help="复用一个访客 cookie（排查时方便在浏览器里看同一批任务）")
    parser.add_argument("--only", default="", help="只跑某个用例，如 C1 / C2")
    parser.add_argument("--wait", type=int, default=420, help="等首轮执行/告警的秒数")
    parser.add_argument("--skip-wait", action="store_true", help="只验结构，不等执行")
    parser.add_argument("--keep", action="store_true", help="C2（合成条件）不停")
    parser.add_argument("--stop-all", action="store_true", help="结束时停掉全部规则")
    args = parser.parse_args()

    if len(args.token) < 32:
        raise SystemExit("需要 MONITOR_ACCESS_TOKEN（≥32 字符）")

    visitor = args.visitor or ("smoke" + uuid.uuid4().hex)
    api = Api(args.base, visitor, args.token)
    print("base=%s visitor=%s email=%s wait=%ss" % (api.base, visitor, args.email, args.wait))

    started = time.time()
    preflight(api)

    steps = Steps("all")
    results = []
    for case in CASES:
        if args.only and args.only.upper() != case["key"]:
            continue
        case_steps = Steps(case["key"])
        try:
            results.append(run_case(api, case, args, case_steps))
        except (AssertionError, StepFailed) as failure:
            if not case_steps.rows or case_steps.rows[-1][1]:
                case_steps.fail("断言", str(failure))
            results.append({"key": case["key"], "ruleId": None, "passed": False, "error": str(failure)})
        except ApiError as failure:
            case_steps.fail("请求", str(failure))
            results.append({"key": case["key"], "ruleId": None, "passed": False, "error": str(failure)})
        steps.rows.extend(case_steps.rows)

    if args.stop_all:
        for item in results:
            if not item.get("ruleId"):
                continue
            body = _stop_params(api, item["ruleId"])
            code, _ = api.call("POST", "/api/monitor-flow/rules/%s/stop" % item["ruleId"], body, action=True)
            print("  停止 %s → HTTP %s" % (item["ruleId"][:8], code))

    alive = []
    for item in results:
        if not item.get("ruleId") or args.stop_all:
            continue
        if item["key"] == "C2" and not args.keep:
            continue
        alive.append(item["ruleId"])
    if alive:
        verify_health(api, alive, steps)

    print("\n===== 结果 =====")
    for item in results:
        print("  %s  %s  ruleId=%s %s" % (item["key"], "PASS" if item["passed"] else "FAIL",
                                          (item.get("ruleId") or "-")[:8], item.get("error", "")))
    failures = [row for row in steps.rows if not row[1]]
    print("  步骤 %d，失败 %d，耗时 %ds" % (len(steps.rows), len(failures), int(time.time() - started)))
    if alive:
        print("\n  仍在运行的规则：%s" % ", ".join(alive))
        print("  浏览器里查看：把 document.cookie 的 cexpilot_uid 设为 %s 后刷新监控列表" % visitor)
    print("  清理：--stop-all")
    return 0 if all(item["passed"] for item in results) and not failures else 1


def _stop_params(api, rule_id):
    card = fetch_card(api, rule_id)
    return {"version": card.get("version"), "specHash": card.get("specHash")}


if __name__ == "__main__":
    sys.exit(main())
