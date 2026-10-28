#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
监控（monitor agent）线上冒烟：把「规划 → 草稿 → 确认 → 排期执行 → 告警 → 邮件」在线上跑通。

只依赖标准库，不装 requests。

用法：
    export MONITOR_ACCESS_TOKEN='<32 位以上凭证>'
    export MONITOR_SMOKE_EMAIL='you@example.com'        # 收件人；RESEND_DRY_RUN=true 时不会真发
    python3 scripts/monitor_smoke.py --base https://<你的实例域名>

    python3 scripts/monitor_smoke.py --suite full       # 再加 C3 连续命中、C4 取消草稿（多 1 条 query、约 6 分钟）
    python3 scripts/monitor_smoke.py --only C2          # 只跑某一条
    python3 scripts/monitor_smoke.py --stop-all         # 结束时停掉本次创建的规则
    python3 scripts/monitor_smoke.py --skip-wait        # 只验结构，不等执行
    python3 scripts/monitor_smoke.py --allow-real-mail  # 线上是真实发信模式时，必须显式开启

用例分工：
    C1  真实条件（BTC 5 分钟跌幅 > 2%）：验「自然语言 → 规则 → 排期 → 执行 DONE」。
        阈值大概率不命中，所以不产出告警，这是预期行为。默认保留，作为一个线上存活探针。
    C2  必定命中（BTC 价格 > 10000 USDT，当前行情恒真）：把「命中 → event → Outbox
        → 投递 SENT」这条只有命中才会走的路补上。验完默认停掉。
    C3  连续命中（同 C2 但要求连续 2 次）：第 1 轮必须只计数、不告警；第 2 轮才发。验完停掉。
    C4  取消草稿：取消后不排期、且不能再被确认。
    每条用例都带同组守卫：错 specHash 的确认必须 409、其他访客读/确认必须 404。

前置条件（缺一条脚本就跑不通）：
    monitor.enabled=true、MONITOR_ACCESS_TOKEN、有效的 MONITOR_MAIL_FROM；
    演练默认要求 RESEND_DRY_RUN=true，接真实发信后需显式 --allow-real-mail。
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

def preflight(api, since_hours=1, allow_real_mail=False, email=""):
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
    if runtime.get("mailMode") == "DRY_RUN":
        pass
    elif runtime.get("mailMode") == "RESEND" and allow_real_mail:
        print("  ! 真实发信模式：本次会给 %s 发真邮件，请确认这是你的地址" % email)
    else:
        problems.append("mailMode=%s，不是 DRY_RUN；确要真发请显式 --allow-real-mail" % runtime.get("mailMode"))
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
        "suite": "core",
    },
    {
        "key": "C2",
        "title": "必定命中：BTC 价格 > 10000 USDT（当前行情恒真）",
        "question": "监控 BTCUSDT 永续合约，如果价格高于10000 USDT 就发邮件提醒我",
        "expect_alert": True,
        "stop_after": True,
        "suite": "core",
    },
    {
        "key": "C3",
        "title": "连续命中：恒真条件 + 连续 2 次才提醒",
        "question": "监控 BTCUSDT 永续合约，如果价格高于10000 USDT 就发邮件提醒我，连续2次满足才提醒",
        "expect_alert": True,
        "expect_hits": 2,
        "stop_after": True,
        "suite": "full",
    },
    {
        "key": "C4",
        "title": "取消草稿：取消后不排期、也不能再确认",
        "question": "监控 BTCUSDT 永续合约，最近5分钟涨幅超过3%就提醒我",
        "reject": True,
        "stop_after": False,
        "suite": "full",
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
    require(card.get("mailMode") == args.mail_mode,
            "card.mailMode=%s，与预检的 %s 不一致" % (card.get("mailMode"), args.mail_mode))
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

    # ③b 越权与错参必须被拒（不改变状态，因此可与主流程共用同一草稿）
    email = (card.get("profile") or {}).get("email")
    code, body = api.call("POST", "/api/monitor-flow/rules/%s/confirm" % rule_id,
                          {"version": version, "specHash": "0" * 64, "recipient": email}, action=True)
    require(code in (400, 409), "错误 specHash 的确认没被拒绝：HTTP %s %s" % (code, text(body)))
    steps.ok("③ 错 specHash 被拒", "HTTP %s" % code)

    intruder = Api(api.base, "intruder" + uuid.uuid4().hex, api.token)
    code, body = intruder.call("GET", "/api/monitor-flow/rules/%s" % rule_id)
    require(code == 404, "其他访客能读到这条规则：HTTP %s" % code)
    code, body = intruder.call("POST", "/api/monitor-flow/rules/%s/confirm" % rule_id,
                               {"version": version, "specHash": spec_hash, "recipient": email}, action=True)
    require(code == 404, "其他访客能确认这条规则：HTTP %s" % code)
    steps.ok("③ 跨访客隔离", "读/确认均 404")

    if case.get("reject"):
        code, body = api.call("POST", "/api/monitor-flow/rules/%s/reject" % rule_id,
                              {"version": version, "specHash": spec_hash}, action=True)
        if code != 200:
            steps.fatal("④ 取消草稿", "HTTP %s %s" % (code, text(body)))
        card = fetch_card(api, rule_id)
        require(card.get("status") == "DELETED", "取消后状态为 %s" % card.get("status"))
        steps.ok("④ 取消草稿", "status=DELETED")
        executions = api.call("GET", "/api/monitor-flow/rules/%s/executions" % rule_id)[1]
        require(not executions, "已取消的草稿仍有 %d 条 execution" % len(executions))
        steps.ok("④ 取消后不排期", "executions=0")
        code, body = api.call("POST", "/api/monitor-flow/rules/%s/confirm" % rule_id,
                              {"version": version, "specHash": spec_hash, "recipient": email}, action=True)
        require(code >= 400, "已取消的草稿仍能确认：HTTP %s" % code)
        steps.ok("④ 取消后不可确认", "HTTP %s" % code)
        return result(case, rule_id, steps, deleted=True)

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
    required_hits = int(card.get("requiredHits") or 1)
    if case.get("expect_hits"):
        require(required_hits == case["expect_hits"],
                "编译出的 requiredHits=%d，预期 %d" % (required_hits, case["expect_hits"]))
    execution = wait_execution(api, rule_id, 1, args.wait)
    if not execution:
        steps.fatal("⑥ 首轮执行", "%ds 内没有终态 execution（查看 /api/monitor-flow/rules/%s/executions）" % (args.wait, rule_id))
    if execution.get("status") != "DONE":
        steps.fatal("⑥ 首轮执行", "status=%s lastError=%s" % (execution.get("status"), text(execution.get("lastError"))))
    scheduled = execution.get("scheduledAt") or ""
    require(_aligned(scheduled), "scheduledAt 不在 300s 网格上：%s" % scheduled)
    steps.ok("⑥ 首轮执行", "DONE slot=%s attempts=%s nodes=%s"
             % (scheduled, execution.get("attemptCount"), execution.get("checkpointNodeCount")))

    # ⑥b 连续命中：第 1 次只累计计数，不能告警
    if required_hits > 1:
        events = api.call("GET", "/api/monitor-flow/rules/%s/events" % rule_id)[1]
        if events:
            steps.fail("⑥ 首轮不告警", "hits=1 < %d 却已产生 %d 条 event" % (required_hits, len(events)))
        else:
            steps.ok("⑥ 首轮不告警", "hits=1 < %d，无 event（预期）" % required_hits)
        second = wait_execution(api, rule_id, 2, args.wait)
        if not second:
            steps.fatal("⑥ 第二轮执行", "%ds 内没有第 2 条 execution" % args.wait)
        if second.get("status") != "DONE":
            steps.fatal("⑥ 第二轮执行", "status=%s lastError=%s"
                        % (second.get("status"), text(second.get("lastError"))))
        steps.ok("⑥ 第二轮执行", "DONE slot=%s attempts=%s"
                 % (second.get("scheduledAt"), second.get("attemptCount")))

    # ⑦ 告警与投递：C1 预期不命中，其余必须命中且投递成功
    if case["expect_alert"]:
        event = poll(lambda: sent_event(api, rule_id), min(args.wait, 180), interval=10, label="告警投递")
        if not event:
            steps.fatal("⑦ 告警投递", "命中后没有 SENT 的 event（DRY_RUN 下应为 SENT + simulated=true）")
        if args.mail_mode == "DRY_RUN":
            require(event.get("simulated") is True,
                    "DRY_RUN 下 simulated=false，可能真的发出了邮件：%s" % event.get("providerMessageId"))
            steps.ok("⑦ 告警投递", "delivery=SENT simulated=true attempts=%s" % event.get("attemptCount"))
        else:
            message_id = str(event.get("providerMessageId") or "")
            require(event.get("simulated") is False and message_id and not message_id.startswith("dry-run:"),
                    "真实模式却拿到模拟回执：simulated=%s msgId=%s" % (event.get("simulated"), message_id))
            steps.ok("⑦ 告警投递", "delivery=SENT 真实回执 msgId=%s attempts=%s"
                     % (message_id, event.get("attemptCount")))
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


def result(case, rule_id, steps, deleted=False):
    return {"key": case["key"], "ruleId": rule_id, "passed": steps.passed, "deleted": deleted}


def wait_execution(api, rule_id, count, timeout):
    """等到至少 count 条 execution 且最新一条已终态；返回最新一条或 None。"""
    def ready():
        code, executions = api.call("GET", "/api/monitor-flow/rules/%s/executions" % rule_id)
        if code != 200 or len(executions) < count:
            return None
        newest = executions[0]
        return newest if newest.get("status") in ("DONE", "FAILED", "CANCELLED") else None
    return poll(ready, timeout, interval=10, label="第 %d 条 execution" % count)


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
    parser.add_argument("--only", default="", help="只跑某个用例，如 C1 / C2 / C3 / C4")
    parser.add_argument("--suite", default="core", choices=("core", "full"),
                        help="core=C1+C2（约 1 分钟）；full=再加 C3 连续命中、C4 取消草稿（多约 6 分钟、1 条 query）")
    parser.add_argument("--wait", type=int, default=420, help="等首轮执行/告警的秒数")
    parser.add_argument("--skip-wait", action="store_true", help="只验结构，不等执行")
    parser.add_argument("--keep", action="store_true", help="合成条件（C2/C3）不停")
    parser.add_argument("--allow-real-mail", action="store_true",
                        help="线上是 RESEND 真实发信模式时，必须显式开启才会继续，否则预检中止")
    parser.add_argument("--stop-all", action="store_true", help="结束时停掉本次创建的规则")
    parser.add_argument("--stop", action="append", default=[], metavar="RULE_ID",
                        help="停止指定 ruleId 后退出（跨进程清理用，需同时传 --visitor）")
    args = parser.parse_args()

    if len(args.token) < 32:
        raise SystemExit("需要 MONITOR_ACCESS_TOKEN（≥32 字符）")

    visitor = args.visitor or ("smoke" + uuid.uuid4().hex)
    api = Api(args.base, visitor, args.token)
    print("base=%s visitor=%s email=%s wait=%ss" % (api.base, visitor, args.email, args.wait))

    if args.stop:
        return stop_rules(api, args.stop)

    started = time.time()
    body = preflight(api, allow_real_mail=args.allow_real_mail, email=args.email)
    args.mail_mode = (body.get("runtime") or {}).get("mailMode")

    steps = Steps("all")
    results = []
    selected = [case for case in CASES
                if (args.suite == "full" or case["suite"] == "core")
                and (not args.only or args.only.upper() == case["key"])]
    if not selected:
        raise SystemExit("没有匹配的用例：--only %s / --suite %s" % (args.only, args.suite))
    print("  用例：%s" % ", ".join(case["key"] for case in selected))
    for case in selected:
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
            if not item.get("ruleId") or item.get("deleted"):
                continue
            body = _stop_params(api, item["ruleId"])
            code, _ = api.call("POST", "/api/monitor-flow/rules/%s/stop" % item["ruleId"], body, action=True)
            print("  停止 %s → HTTP %s" % (item["ruleId"][:8], code))

    alive = []
    for item in results:
        if not item.get("ruleId") or item.get("deleted") or args.stop_all:
            continue
        if item["key"] in ("C2", "C3") and not args.keep:
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
    print("  清理本次：--stop-all；清理旧规则：--visitor <id> --stop <ruleId>")
    return 0 if all(item["passed"] for item in results) and not failures else 1


def stop_rules(api, rule_ids):
    """停止历史运行留下的规则：stop 校验 owner，必须复用创建它时的 --visitor。"""
    failed = 0
    for rule_id in rule_ids:
        code, card = api.call("GET", "/api/monitor-flow/rules/%s" % rule_id)
        if code == 404:
            print("  %s 读不到：owner 不是当前 --visitor（停止需复用创建它时的访客 cookie）" % rule_id[:8])
            failed += 1
            continue
        if code != 200:
            print("  %s 读取失败 HTTP %s %s" % (rule_id[:8], code, text(card)))
            failed += 1
            continue
        if card.get("status") != "ACTIVE":
            print("  %s 当前状态 %s，无需停止" % (rule_id[:8], card.get("status")))
            continue
        code, body = api.call("POST", "/api/monitor-flow/rules/%s/stop" % rule_id,
                              {"version": card.get("version"), "specHash": card.get("specHash")}, action=True)
        if code == 200 and (body or {}).get("stopped") is True:
            print("  %s 已停止（v%s）" % (rule_id[:8], card.get("version")))
        else:
            print("  %s 停止失败 HTTP %s %s" % (rule_id[:8], code, text(body)))
            failed += 1
    return 0 if failed == 0 else 1


def _stop_params(api, rule_id):
    card = fetch_card(api, rule_id)
    return {"version": card.get("version"), "specHash": card.get("specHash")}


if __name__ == "__main__":
    sys.exit(main())
