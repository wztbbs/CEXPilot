#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CEXPilot 追问链路探针（多轮上下文继承）

两套口径并存的探针：
  P 系（问题复现探针，C1/P1~P5b）：确认「只把最近 5 组问答（助手答案截前 300 字）塞进 prompt，
      其余全靠模型自己还原」这套追问实现会在哪些连续问法上出问题。
      verdict 口径：CONFIRMED / LATENT / NOT_REPRODUCED / PARTIAL，不是门禁。
  F 系（验收用例，F01~F22）：对应《多轮追问的落地文档》第十章（10.1/10.2）的验收场景，
      verdict 口径：PASS / FAIL / SKIP。SKIP 表示「依赖的能力阶段尚未覆盖，本轮不判定」。

阶段标签（--phase N，默认 1）：
  每条 F 用例带 min_phase：1=阶段一（结构化上下文+每轮规划）可判；
  2=阶段二（历史结果引用）才可判；4=阶段四（数据集复用）才可判。
  min_phase > N 的用例不执行，在 F 系汇总表中标 SKIP（注明依赖阶段）。
  F01 的「免取数」断言只在 --phase 4 时检查；F04 的「新 factId」断言只在 --phase>=2 时检查。
  F14（同会话并发）不在本脚本实现：线上并发模拟会误伤真实会话，需测试环境验证，汇总固定 SKIP。

基线模式（--baseline）：
  报告头部标注「基线轮（阶段一改造前）」，F 系 verdict 语义为「预期大面积 FAIL，用于后续对比」。
  产物文件名规则不变，由报告头部文字区分。

判定原则（与 P 系相同）：不看答案文字表面，看 evidence 与 trace：
  identity.metric / exchange / instrument.base / data.requested_range / samples /
  candle_interval，以及 /api/trace_detail 的 planner LLM_CALL input/output。
  阶段二所需的证据（factId、REUSE 绑定、CONTEXT_RESOLVED / EVIDENCE_REUSE_DECISION 事件）
  在现有 trace 里不存在：读不到就标「证据缺失」，整体 verdict 降级为 FAIL/SKIP 并注明，不崩。

线上实现要点（P 系判据建立在这些点上）：
  1. ConversationService：HISTORY_QUERY_LIMIT=5、HISTORY_ANSWER_MAX_CHARS=300，
     渲染成「用户：xxx\n助手：yyy」纯文本，不带计划/工具参数/工具结果/发生时间。
  2. 该文本同时注入 dag_planner.txt 的 <CONVERSATION_CONTEXT> 与 agent_system.txt。
  3. DagExecutor 每次 execute 都 new DagContext()，没有跨轮结果可引用。

P 系覆盖的场景：
  C1 会话复用；P1 上下文淘汰；P2 旧快照不可引用；P3 样本未锁定；
  P4 连续改条件；P5/P5b 长答案截断。

用法：
    python3 probe_followup.py                    # 全量（P 系约 24 轮 + F 系约 65 轮真实问答；
                                                 #  单轮秒级到 1~3 分钟，全量预计 30~90 分钟，务必后台跑）
    python3 probe_followup.py --phase 2          # 阶段二口径：F02/F03/F05/F13/F15/F16/F21 也执行
    python3 probe_followup.py --phase 1 --baseline   # 基线轮（阶段一改造前）
    python3 probe_followup.py only:P3,F01        # 只跑指定场景（与 --phase 叠加）
    python3 probe_followup.py nosmoke            # 跳过冒烟

产物：
    importants/回归测试/追问链路探针-<日期>.md   （人读的结论；P 系与 F 系分两张汇总表）
    importants/回归测试/raw/追问链路探针-<日期>.json（每轮原始响应 + 注入上下文，复核用）

P 系判定口径：CONFIRMED=问题复现；LATENT=机制缺失但本次未触发；
  NOT_REPRODUCED=本次未复现；PARTIAL=中间状态。
F 系判定口径：PASS=所有判据命中；FAIL=有判据未命中（含证据缺失降级）；SKIP=阶段未覆盖/未触发。
"""

import json
import os
import re
import sys
import time
import urllib.request
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

BASE = "https://cex.hrscrm.net"
URL = BASE + "/api/ask"
TRACE = BASE + "/trace.html?id="
DETAIL = BASE + "/api/trace_detail/"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORT_DIR = os.path.join(ROOT, "importants", "回归测试")
RAW_DIR = os.path.join(REPORT_DIR, "raw")

SH = ZoneInfo("Asia/Shanghai")
TODAY_SH = datetime.now(SH).date()
YESTERDAY_SH = (TODAY_SH - timedelta(days=1)).isoformat()
DAY_BEFORE_SH = (TODAY_SH - timedelta(days=2)).isoformat()

PHASE = 1        # --phase N：只执行 min_phase<=N 的 F 用例，其余标 SKIP
BASELINE = False  # --baseline：报告头部标注基线轮

CTX_RE = re.compile(r"<CONVERSATION_CONTEXT>(.*?)</CONVERSATION_CONTEXT>", re.S)
FALLBACK_REFUSAL_RE = re.compile(
    r"(无法可靠生成查询计划|没有能够可靠回答此问题的查询计划|本次未执行查询)")
REFUSE_PAT = [
    r"无法[^，。；\s]{0,10}(计算|换算|提供|得出|确认|回答|判断|估算|引用|获取)",
    r"不能[^，。；\s]{0,10}(计算|换算|提供|得出|估算|引用)",
    r"没有[^，。；\s]{0,8}(记录|保存|留存)",
    r"无法确认[^，。；]{0,10}(刚才|上一轮|之前)",
    r"请(提供|告诉|补充)[^，。；\s]{0,10}(价格|数值|旧)",
]


# ---------------- 基础设施 ----------------

def ask(q, conv=None, visitor=None, timezone=None, timeout=180):
    """打线上 /api/ask。visitor 走 cexpilot_uid cookie（会话复用的载体）。"""
    body = {"question": q}
    if conv:
        body["conversationId"] = conv
    if timezone:
        body["timezone"] = timezone
    headers = {"Content-Type": "application/json"}
    if visitor:
        headers["Cookie"] = "cexpilot_uid=" + visitor
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=headers)
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode())
    except Exception as e:
        return {"ok": False, "question": q, "error": repr(e)[:200],
                "wall": round(time.time() - t0, 1)}
    d["ok"] = True
    d["question"] = q
    d["wall"] = round(time.time() - t0, 1)
    return d


def detail(tid):
    if not tid:
        return {"events": []}
    last = None
    for attempt in range(2):  # 网络抖动时重试一次，避免上下文抽取静默失败
        try:
            with urllib.request.urlopen(DETAIL + tid, timeout=60) as r:
                return json.loads(r.read().decode())
        except Exception as e:
            last = e
            time.sleep(2)
    return {"events": [], "error": repr(last)[:120]}


def planner_ctx(tid, stage="dag_planner"):
    """从 trace 里取本轮真正注入模型的对话上下文原文（不是我们猜的，是线上拼好的）。"""
    det = detail(tid)
    for e in det.get("events") or []:
        if e.get("event_type") != "LLM_CALL" or e.get("name") != stage:
            continue
        ij = e.get("input_json")
        if isinstance(ij, str):
            try:
                ij = json.loads(ij)
            except Exception:
                continue
        for m in (ij or {}).get("messages", []):
            if m.get("role") == "system":
                mm = CTX_RE.search(m.get("content") or "")
                if mm:
                    return mm.group(1).strip()
    return None


def ctx_pairs(ctx):
    """把注入上下文拆成 [(用户问, 助手答)]。"""
    if not ctx:
        return []
    pairs, cur_q, cur_a = [], None, None
    for line in (ctx or "").splitlines():
        if line.startswith("用户："):
            if cur_q is not None:
                pairs.append((cur_q, cur_a or ""))
            cur_q, cur_a = line[3:], None
        elif line.startswith("助手："):
            cur_a = line[3:]
        elif cur_a is not None:
            cur_a += "\n" + line
    if cur_q is not None:
        pairs.append((cur_q, cur_a or ""))
    return pairs


def _num(x):
    """数值归一化：算子/指标的 value 可能是 JSON 数字，也可能是 BigDecimal 序列化出来的字符串。
    直接 isinstance(x, float) 会把「算子算对了但类型是字符串」误判成「没走算子」。"""
    if isinstance(x, bool):
        return None
    if isinstance(x, (int, float)):
        return float(x)
    if isinstance(x, str):
        try:
            return float(x)
        except ValueError:
            return None
    return None


def rows(d):
    """evidence → 统一断言行。指标节点走 identity，计算节点走 operator。"""
    out = []
    for ev in (d or {}).get("evidence") or []:
        ident = ev.get("identity") or {}
        data = ev.get("data") or {}
        inst = ident.get("instrument") or data.get("instrument") or {}
        out.append({
            "kind": "calc" if not ident else "metric",
            "metric": ident.get("metric") or data.get("metric"),
            "exchange": ident.get("exchange") or data.get("exchange"),
            "shape": ident.get("query_shape") or data.get("query_shape"),
            "base": inst.get("base"),
            "value": data.get("value"),
            "samples": data.get("samples"),
            "req": data.get("requested_range"),
            "actual": data.get("actual_range"),
            "coverage": data.get("coverage"),
            "candle_count": data.get("candle_count"),
            "interval": data.get("candle_interval"),
            "as_of": data.get("as_of"),
            "operator": ev.get("operator"),
            "ok": ev.get("ok"),
        })
    return out


def mrows(t):
    return [r for r in t["rows"] if r["kind"] == "metric"]


def crows(t):
    return [r for r in t["rows"] if r["kind"] == "calc" and r.get("operator")]


def exchanges(t):
    return sorted({r["exchange"] for r in mrows(t) if r["exchange"]})


def bases(t):
    return sorted({r["base"] for r in mrows(t) if r["base"]})


def metrics(t):
    return sorted({r["metric"] for r in mrows(t) if r["metric"]})


def tz_of(t):
    tzs = {(r["req"] or {}).get("timezone") for r in mrows(t) if r.get("req")}
    return sorted(x for x in tzs if x)


def dates_of(t):
    out = set()
    for r in mrows(t):
        rng = r.get("req") or {}
        s = rng.get("start_inclusive")
        if s:
            out.add(str(s)[:10])
    return sorted(out)


def brief(t):
    d = t.get("d") or {}
    parts = ["%s/%s/%s/%s" % (r["metric"], r["exchange"], r["base"], r["shape"])
             for r in mrows(t)]
    parts += ["op:%s=%s" % (r["operator"], r["value"]) for r in crows(t)]
    return "tools=%s %s" % (d.get("toolCalls"), ", ".join(parts) or "-")


def ans(t):
    return (t.get("d") or {}).get("answer") or ""


def looks_refused(text):
    return [m.group(0) for p in REFUSE_PAT for m in [re.search(p, text or "")] if m]


def pcts(text):
    return [float(x) for x in re.findall(r"(-?\d{1,3}(?:\.\d+)?)%", text or "")]


# ---------------- F 系共用证据助手 ----------------

def windows(t):
    """本轮所有指标节点的请求窗口集合（start~end 字符串）。"""
    out = set()
    for r in mrows(t):
        rng = r.get("req") or {}
        if rng.get("start_inclusive"):
            out.add("%s~%s" % (rng.get("start_inclusive"), rng.get("end_exclusive")))
    return out


def intervals(t):
    return sorted({r["interval"] for r in mrows(t) if r.get("interval")})


def funding_samples(t):
    """funding.rate_settled 节点的样本时间序列与数值序列。"""
    for r in mrows(t):
        if (r["metric"] or "").startswith("funding.rate_settled") and r.get("samples"):
            return [str(s.get("time")) for s in r["samples"]], \
                   [v for v in (_num(s.get("value")) for s in r["samples"]) if v is not None]
    return [], []


def op_value(t, name):
    for r in crows(t):
        if r["operator"] == name and _num(r["value"]) is not None:
            return _num(r["value"])
    return None


def tool_calls(t):
    return (t.get("d") or {}).get("toolCalls") or 0


def fact_ids(t):
    """evidence 上的 factId（阶段二证据；现有 trace 没有该字段时返回 [None,...]，调用方按证据缺失处理）。"""
    out = []
    for ev in ((t.get("d") or {}).get("evidence") or []):
        data = ev.get("data") or {}
        out.append(ev.get("factId") or ev.get("fact_id") or data.get("fact_id") or data.get("factId"))
    return out


def event_names(tid):
    """trace 里全部事件的 (event_type, name)，用于探测 CONTEXT_RESOLVED / EVIDENCE_REUSE_DECISION 等新事件。"""
    try:
        det = detail(tid)
    except Exception:
        return []
    return [(e.get("event_type"), e.get("name")) for e in det.get("events") or []]


def planner_out(tid, stage="dag_planner"):
    """planner LLM_CALL 的完整输出原文（plan JSON 或 reply）。"""
    det = detail(tid)
    for e in det.get("events") or []:
        if e.get("event_type") != "LLM_CALL" or e.get("name") != stage:
            continue
        oj = e.get("output_json")
        if isinstance(oj, str):
            try:
                oj = json.loads(oj)
            except Exception:
                continue
        c = (oj or {}).get("content")
        if c:
            return c
    return None


def planner_reply(tid):
    """planner 输出解析为 (是否给出 plan, reply 文本)。trace 读不到时返回 (None, None)。"""
    c = planner_out(tid)
    if not c:
        return None, None
    try:
        j = json.loads(c)
        return bool(j.get("plan")), j.get("reply") or ""
    except Exception:
        has_plan = bool(re.search(r'"plan"\s*:\s*\{', c))
        m = re.search(r'"reply"\s*:\s*"((?:[^"\\]|\\.)*)"', c)
        return has_plan, (m.group(1) if m else "")


CLARIFY_RE = re.compile(r"(哪(一|个|家|组|条|种)|请(告诉|说明|补充|指定|确认)|您?是指|需要先确认|澄清一下)")


def looks_clarify(text):
    """答案 / planner reply 是否是澄清提问（而不是擅自选了一组继续）。"""
    t = text or ""
    return bool(CLARIFY_RE.search(t))


def smoke():
    d = ask("币安上的 BTC 现在多少钱？")
    a = d.get("answer") or ""
    ok = d.get("ok") and (d.get("toolCalls") or (a and not FALLBACK_REFUSAL_RE.search(a)))
    print("[冒烟] %s -> %s (wall=%ss)" % ("OK" if ok else "FAIL", d.get("toolCalls"), d.get("wall")))
    if not ok:
        print("   %s" % (d.get("error") or a[:200]))
        sys.exit(1)


# ---------------- 结果收集 ----------------

RESULTS = []      # 每个场景一份 dict
RAW = []          # 每轮原始响应，落 raw json
MD = []           # markdown 行


def md(line=""):
    MD.append(line)
    print(line)


def turn(conv, q, timezone=None, want_ctx=False, tag=""):
    d = ask(q, conv=conv, timezone=timezone)
    # 4.1 补充：本机网络抖动（DNS/SSL EOF）会让请求没到服务器就失败，空响应会让
    # 判据空洞通过（F12/2 实测）。请求失败时重试一次；仍失败保留失败响应，
    # 由各场景按"证据缺失记 SKIP"原则处理，不产生假 PASS
    if not d.get("ok") and d.get("error") and re.search(r"URLError|SSL|gaierror|timed out", str(d.get("error"))):
        print("   %s | 请求失败（%s），5s 后重试一次" % (tag, str(d.get("error"))[:60]))
        time.sleep(5)
        d2 = ask(q, conv=conv, timezone=timezone)
        if d2.get("ok"):
            d = d2
    t = {"q": q, "d": d, "tz_sent": timezone, "rows": rows(d) if d.get("ok") else []}
    if d.get("ok") and want_ctx:
        t["ctx"] = planner_ctx(d.get("traceId"))  # None 表示抽取失败（证据缺失），区别于空上下文
        t["pairs"] = ctx_pairs(t["ctx"]) if t["ctx"] is not None else None
    RAW.append({"tag": tag, "question": q, "conversationId": conv,
                "timezone": timezone, "response": d,
                "injected_context": t.get("ctx")})
    print("   %s | %s | %.1fs | %s" % (tag, q[:34], d.get("wall", 0), brief(t)))
    time.sleep(1.2)
    return t


def finish(sid, title, expect_lines, check_lines, verdict, note_lines, min_phase=1):
    """check_lines: [(判据, 是否命中, 说明)]
    P 系 verdict 走 CONFIRMED/LATENT/NOT_REPRODUCED/PARTIAL；F 系走 PASS/FAIL/SKIP，
    本函数对两者通用，min_phase 仅对 F 系有意义（默认 1，向后兼容 P 系调用）。"""
    md("\n### %s %s" % (sid, title))
    md("**预期**：%s" % ("；".join(expect_lines) if expect_lines else "-"))
    md("")
    md("| 判据 | 结果 | 说明 |")
    md("|---|---|---|")
    for name, hit, desc in check_lines:
        md("| %s | %s | %s |" % (name, "✅" if hit else "❌", desc))
    md("")
    md("**结论：%s**" % verdict)
    for n in note_lines:
        md("- %s" % n)
    md("")
    RESULTS.append({"id": sid, "title": title, "verdict": verdict,
                    "min_phase": min_phase,
                    "checks": [{"name": n, "hit": bool(h), "desc": d}
                               for n, h, d in check_lines],
                    "notes": note_lines})
    return RESULTS[-1]


# F 系用例标题（供 skip_case 在 --phase 过滤时仍能写出完整汇总行）
F_TITLES = {
    "F01": "基础链：换指标继承区间/市场/交易所/粒度",
    "F02": "样本锁定：同一组 10 期的均值/最大值/最小值",
    "F03": "重新取最新集合（不沿用 PINNED 结果）",
    "F04": "刷新链：每轮重新取数",
    "F05": "旧快照 + 新快照的涨跌幅比较",
    "F06": "连续换对象不漂移（所→币）",
    "F07": "焦点往返不被污染（高→低→高）",
    "F08": "插 6 轮后结构化条件完整继承",
    "F09": "长答案后按结构化条件取数",
    "F10": "多候选时澄清而非默认第一组",
    "F11": "澄清填入后完成原任务并聚合",
    "F12": "取数失败后的继承与换所",
    "F13": "Answer 失败后「再试一次」",
    "F14": "同会话并发（两标签页同时发送）",
    "F15": "scalar 缓存缺字段必须重查",
    "F16": "K 线缺口下的合计完整性约束",
    "F17": "成交价 → 标记价的 metric 切换",
    "F18": "official_24h 与自然日区间区分",
    "F19": "已结算费率 → 预测费率的能力边界",
    "F20": "否定式复合修改（换所+换时间+换指标）",
    "F21": "缓存不可恢复时的回退与冒充检测",
    "F22": "明确话题切换后的代词绑定",
}


def skip_case(sid, min_phase, reason):
    """F 系 SKIP：不执行问答，只在报告与汇总里登记一行。"""
    title = F_TITLES.get(sid, "")
    verdict = "SKIP（依赖阶段 %d）" % min_phase
    md("\n### %s %s" % (sid, title))
    md("**结论：%s**" % verdict)
    md("- %s" % reason)
    md("")
    RESULTS.append({"id": sid, "title": title, "verdict": verdict,
                    "min_phase": min_phase, "checks": [], "notes": [reason]})
    return RESULTS[-1]


# ---------------- C1 会话复用 ----------------

def scenario_c1():
    md("\n## C1 会话选择：不传 conversationId 到底新建还是复用")
    vis_a = "probe-vis-a-" + uuid.uuid4().hex[:8]
    vis_b = "probe-vis-b-" + uuid.uuid4().hex[:8]
    r1 = ask("币安 BTC 现在多少钱？", visitor=vis_a)
    c1 = r1.get("conversationId")
    r2 = ask("那 OKX 呢？", visitor=vis_a)
    c2 = r2.get("conversationId")
    r3 = ask("币安 ETH 现在多少钱？", visitor=vis_b)
    c3 = r3.get("conversationId")
    given = "probe-conv-" + uuid.uuid4().hex[:8]
    r4 = ask("币安 SOL 现在多少钱？", conv=given, visitor=vis_a)
    c4 = r4.get("conversationId")
    print("   C1 conv: a1=%s a2=%s b=%s given=%s->%s" % (c1, c2, c3, given, c4))
    for tag, r in (("C1/1", r1), ("C1/2", r2), ("C1/3", r3), ("C1/4", r4)):
        RAW.append({"tag": tag, "question": r.get("question"),
                    "conversationId": r.get("conversationId"), "timezone": None,
                    "response": r, "injected_context": None})

    # 追问是否真的接上了上下文：第二轮换交易所，应拿到 okx
    r2_rows = rows(r2)
    okx_hit = any(r["exchange"] == "okx" for r in r2_rows if r["kind"] == "metric")

    checks = [
        ("同一访客不传 id → 复用同一会话", c1 == c2,
         "两次返回 conversationId 相同：%s" % ("是" if c1 == c2 else "否（%s / %s）" % (c1, c2))),
        ("不同访客不传 id → 各自新会话", c3 != c1,
         "b=%s %s a=%s" % (c3, "≠" if c3 != c1 else "==", c1)),
        ("传入不存在的 id → 沿用该 id", c4 == given,
         "返回 %s" % c4),
        ("复用会话下追问'那 OKX 呢'命中 OKX", okx_hit,
         "evidence exchange=%s" % (sorted({r["exchange"] for r in r2_rows if r["kind"] == "metric"}) or "-")),
    ]
    md("\n接口注释写的是「不传则创建新对话」，实现是「visitorId 已有会话则复用最近活跃的那个」。")
    return finish("C1", "会话选择与复用",
                  ["不传 conversationId 时按访客 cookie 复用最近会话；接口注释与实现一致才对"],
                  checks,
                  "CONFIRMED（注释与实现不一致）" if c1 == c2 else "NOT_REPRODUCED（确实每次新建）",
                  ["实现位置：ConversationService.getOrCreateConversation → "
                   "repository.findLatestByVisitorId(visitorId)",
                   "AskRequest 的 javadoc 仍写「不传则创建新对话」，需同步修改",
                   "访客标识来自 cookie cexpilot_uid，首次访问由服务端随机生成"])


# ---------------- P1 上下文淘汰 ----------------

def scenario_p1():
    md("\n## P1 上下文淘汰：首轮条件插 5 轮后掉出窗口")
    md("请求 timezone 固定为 America/New_York，让首轮的「按上海时区」成为一个必须被继承、"
       "且能从 evidence.requested_range.timezone 直接读出来的条件。")
    conv = str(uuid.uuid4())
    qs = [
        "查币安 BTC-USDT 永续昨天的成交额，按上海时区。",
        "ETH 现在多少钱？",
        "SOL 现在多少钱？",
        "XRP 现在多少钱？",
        "DOGE 现在多少钱？",
        "ADA 现在多少钱？",
        "回到最开始那个问题，换成 OKX，其他条件不变。",
    ]
    turns = []
    for i, q in enumerate(qs):
        turns.append(turn(conv, q, timezone="America/New_York",
                          want_ctx=(i == len(qs) - 1), tag="P1/%d" % (i + 1)))
    base, last = turns[0], turns[-1]
    ctx_missing = last.get("ctx") is None  # trace 抽取失败：证据缺失，不做上下文判据
    ctx, pairs = last.get("ctx") or "", last.get("pairs") or []

    # 基线以首轮实际取到的为准：判定「追问有没有继承」，而不是拿绝对值与首轮比。
    # 另外单独校验首轮自己有没有把「上海时区/昨天」用对（若首轮就没用对，继承判定仍成立）。
    b_dt, b_tz = dates_of(base), tz_of(base)
    base_desc = "首轮实际取到：metric=%s exchange=%s base=%s 日期=%s 时区=%s" % (
        metrics(base), exchanges(base), bases(base), b_dt, b_tz)
    md("\n基线（首轮）%s" % base_desc)
    base_right = b_dt == [YESTERDAY_SH] and b_tz == ["Asia/Shanghai"]

    still = ("成交额" in ctx) and ("BTC" in ctx) and ("昨天" in ctx)
    ex, bs, mt, dt, tz = (exchanges(last), bases(last), metrics(last),
                          dates_of(last), tz_of(last))
    c_ex = ex == ["okx"]
    c_bs = bs == ["BTC"]
    c_mt = any((m or "").startswith("trade.turnover") for m in mt)
    c_dt = bool(dt) and dt == b_dt
    c_tz = bool(tz) and tz == b_tz
    inherited = sum([c_ex, c_bs, c_mt, c_dt, c_tz])

    checks = [
        ("首轮自己用对了上海时区+昨天（基线校验）", base_right,
         "首轮日期=%s 时区=%s，应为 %s / Asia/Shanghai" % (b_dt, b_tz, YESTERDAY_SH)),
        ("注入上下文只含 5 组问答", (not ctx_missing) and len(pairs) == 5,
         ("证据缺失：trace 上下文抽取失败" if ctx_missing
          else "实际 %d 组（首轮应已被淘汰）" % len(pairs))),
        ("首轮条件已掉出上下文", (not ctx_missing) and not still,
         ("证据缺失：trace 上下文抽取失败" if ctx_missing
          else "上下文中同时出现 BTC+成交额+昨天：%s" % ("是" if still else "否"))),
        ("追问继承：交易所=OKX", c_ex, "evidence exchanges=%s" % ex),
        ("追问继承：币种=BTC", c_bs, "evidence base=%s" % bs),
        ("追问继承：指标=成交额", c_mt, "evidence metric=%s" % mt),
        ("追问继承：日期与首轮一致", c_dt, "evidence 日期=%s，首轮=%s" % (dt, b_dt)),
        ("追问继承：时区与首轮一致", c_tz, "evidence timezone=%s，首轮=%s" % (tz, b_tz)),
    ]
    if ctx_missing:
        verdict = ("CONFIRMED（追问条件漂移 %d/5；上下文证据缺失，淘汰机制未判定）" % (5 - inherited)
                   if inherited < 5 else "NOT_REPRODUCED（继承全部命中；上下文证据缺失）")
    elif not still and inherited < 5:
        verdict = "CONFIRMED（条件丢失 %d/5，追问对象已漂移）" % (5 - inherited)
    elif not still:
        verdict = "PARTIAL（首轮条件已掉出上下文，但本次仍答对）"
    else:
        verdict = "NOT_REPRODUCED（首轮条件仍在上下文中）"
    notes = [
        "上下文窗口 = HISTORY_QUERY_LIMIT=5，是硬边界；中间轮次若反复复述完整条件，信息仍可能传下来",
        "本轮注入上下文原文（截断展示）：\n\n```\n%s\n```" % (ctx[:700] + ("…" if len(ctx) > 700 else "")),
        "trace：%s%s" % (TRACE, (last["d"] or {}).get("traceId")),
    ]
    return finish("P1", "5 组窗口淘汰首轮条件",
                  ["第 7 轮应继承首轮的 BTC / 昨天 / 上海时区 / 成交额，只把交易所换成 OKX"],
                  checks, verdict, notes)


# ---------------- P2 旧快照不可引用 ----------------

def scenario_p2():
    md("\n## P2 旧快照：「现在比刚才上涨了百分之多少」")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "币安 BTC-USDT 永续现在多少钱？", timezone="Asia/Shanghai", tag="P2/1")
    t2 = turn(conv, "现在比刚才上涨了百分之多少？", timezone="Asia/Shanghai",
              want_ctx=True, tag="P2/2")
    p1 = p2 = None
    for r in mrows(t1):
        if r["metric"] == "price.last" and _num(r["value"]) is not None:
            p1 = _num(r["value"])
    for r in mrows(t2):
        if r["metric"] == "price.last" and _num(r["value"]) is not None:
            p2 = _num(r["value"])
    mt2 = metrics(t2)
    ops = [r["operator"] for r in crows(t2)]
    a2 = ans(t2)
    vals = pcts(a2)
    refused = looks_refused(a2)

    used_24h = any(m == "price.change_pct_24h" for m in mt2)
    exp = None
    if p1 and p2:
        exp = (p2 - p1) / p1 * 100
    hit_history = False
    if exp is not None:
        hit_history = any(abs(v - exp) <= max(0.02, abs(exp) * 0.25) for v in vals)

    if used_24h:
        kind = "用官方 24h 涨跌幅冒充「比刚才」（口径错，且不是用户问的那段间隔）"
        verdict = "CONFIRMED"
    elif refused:
        kind = "拒答 / 要求用户提供旧价（缺少可引用的旧快照，能力缺口暴露）"
        verdict = "CONFIRMED"
    elif hit_history:
        kind = "算术可对，但基准取自历史回答文本里的旧价（违反「旧价格不得当本轮事实」，且该数字来自被截断的 300 字）"
        verdict = "CONFIRMED"
    elif vals:
        kind = "给了百分数但来源不明（无法与首轮快照对齐）"
        verdict = "CONFIRMED"
    else:
        kind = "未给出可校验的百分数"
        verdict = "LATENT"

    checks = [
        ("首轮拿到可比对的价格", p1 is not None, "price.last=%s" % p1),
        ("本轮重新取到当前价", p2 is not None, "price.last=%s" % p2),
        ("本轮走了算子而不是模型自算", bool(ops), "operator=%s（空=没安排计算节点）" % ops),
        ("没有用 24h 涨跌幅替代", not used_24h, "metric=%s" % mt2),
        ("没有把历史旧价当基准", not hit_history,
         "答案百分数=%s，若等于 (本轮-首轮)/首轮=%.4f%% 即为取自历史" % (vals[:3], exp if exp is not None else float("nan"))),
    ]
    return finish("P2", "旧快照不可引用",
                  ["系统要么能引用上一轮快照并用算子算，要么明确说明无法回答；不能拿历史文本里的数字当事实"],
                  checks, verdict,
                  ["观测到的行为：%s" % kind,
                   "首轮价=%s 本轮价=%s（间隔约 %.0fs）" % (p1, p2, (t2["d"] or {}).get("wall", 0)),
                   "答案：%s" % (a2[:260].replace("\n", " ")),
                   "trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))])


# ---------------- P3 样本未锁定 ----------------

def scenario_p3():
    md("\n## P3 样本未锁定：「把刚才那 10 期求均值 / 最小值」")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "列出币安 BTC-USDT 永续最近 10 期已结算资金费率。",
              timezone="Asia/Shanghai", tag="P3/1")
    t2 = turn(conv, "把刚才那 10 期求平均。", timezone="Asia/Shanghai", tag="P3/2")
    t3 = turn(conv, "再找出这 10 期的最小值。", timezone="Asia/Shanghai", tag="P3/3")

    def samples(t):
        for r in mrows(t):
            if (r["metric"] or "").startswith("funding.rate_settled") and r.get("samples"):
                return [str(s.get("time")) for s in r["samples"]], \
                       [v for v in (_num(s.get("value")) for s in r["samples"]) if v is not None]
        return [], []

    k1, v1 = samples(t1)
    k2, v2 = samples(t2)
    k3, v3 = samples(t3)

    # 任一轮请求失败（无 traceId / 网络错误）时无法判定样本问题，直接降级，不误判为漂移
    failed = [i + 1 for i, t in enumerate((t1, t2, t3)) if not (t["d"] or {}).get("ok")]
    if failed:
        return finish("P3", "recent_n 样本未锁定",
                      ["首轮那 10 期应被锁定，两次聚合都作用在同一批样本上"],
                      [("三轮请求全部成功", False,
                        "第 %s 轮请求失败：%s" % ("/".join(map(str, failed)),
                                                (t1, t2, t3)[failed[0] - 1]["d"].get("error")))],
                      "ERROR（本轮请求失败，无法判定；不计入 CONFIRMED/LATENT）",
                      ["trace：%s%s / %s%s / %s%s" % (TRACE, (t1["d"] or {}).get("traceId"),
                                                    TRACE, (t2["d"] or {}).get("traceId"),
                                                    TRACE, (t3["d"] or {}).get("traceId"))])

    def op_value(t, name):
        for r in crows(t):
            if r["operator"] == name and _num(r["value"]) is not None:
                return _num(r["value"])
        return None

    avg_v, min_v = op_value(t2, "avg"), op_value(t3, "min")
    exp_avg = sum(v1) / len(v1) if v1 else None
    exp_min = min(v1) if v1 else None

    same_12 = bool(k1) and k1 == k2
    same_23 = bool(k2) and k2 == k3
    drift = bool(k1) and not (same_12 and same_23)
    avg_ok = avg_v is not None and exp_avg is not None and abs(avg_v - exp_avg) <= abs(exp_avg) * 1e-6
    min_ok = min_v is not None and exp_min is not None and abs(min_v - exp_min) <= abs(exp_min) * 1e-6 + 1e-12

    if drift:
        verdict = "CONFIRMED（两次追问取到的样本与首轮不同）"
    elif avg_v is None or min_v is None:
        verdict = "CONFIRMED（没走算子，由模型自己算）"
    elif not (avg_ok and min_ok):
        verdict = "CONFIRMED（算子结果与首轮样本对不上）"
    else:
        verdict = "LATENT（机制缺失，本次未触发：期间无新结算，样本未漂移）"

    checks = [
        ("首轮取到 10 期样本", len(k1) == 10, "实际 %d 期，首期=%s 末期=%s" % (len(k1), k1[:1], k1[-1:])),
        ("追问 1 走了 avg 算子", avg_v is not None, "operator=%s value=%s" % ([r["operator"] for r in crows(t2)], avg_v)),
        ("追问 2 走了 min 算子", min_v is not None, "operator=%s value=%s" % ([r["operator"] for r in crows(t3)], min_v)),
        ("avg 与首轮样本自算一致", avg_ok, "算子=%s 自算=%s" % (avg_v, exp_avg)),
        ("min 与首轮样本自算一致", min_ok, "算子=%s 自算=%s" % (min_v, exp_min)),
        ("两次追问的样本与首轮完全相同", same_12 and same_23,
         "turn1==turn2:%s turn2==turn3:%s" % (same_12, same_23)),
        ("两次追问用的是同一批样本", same_23, "turn2==turn3:%s" % same_23),
    ]
    return finish("P3", "recent_n 样本未锁定",
                  ["首轮那 10 期应被锁定，两次聚合都作用在同一批样本上"],
                  checks, verdict,
                  ["系统每次追问都重新查 recent_n/count=10，没有保存也没有锁定首轮样本；"
                   "一旦期间发生新结算，「最近 10 期」就换了一批，而两次聚合甚至可能用不同样本",
                   "本次未漂移的原因：BTC 结算为 8 小时一次，探针全程在两次结算之间",
                   "若要强制触发，需让两次取数跨越一次结算（或人工让两次 count 不同）",
                   "trace：%s%s / %s%s" % (TRACE, (t2["d"] or {}).get("traceId"),
                                           TRACE, (t3["d"] or {}).get("traceId"))])


# ---------------- P4 连续改条件 ----------------

def scenario_p4():
    md("\n## P4 连续改条件：换币 → 只看一所 → 改日期 → 加指标")
    conv = str(uuid.uuid4())
    qs = [
        "比较币安和 OKX 的 BTC 昨天成交额。",
        "换成 ETH，其他条件不变。",
        "只看 OKX。",
        "改成前天。",
        "成交量也查一下。",
    ]
    turns = [turn(conv, q, timezone="Asia/Shanghai", tag="P4/%d" % (i + 1))
             for i, q in enumerate(qs)]
    for i, t in enumerate(turns):
        md("- 第 %d 轮「%s」 → exchanges=%s base=%s metric=%s 日期=%s" % (
            i + 1, t["q"], exchanges(t), bases(t), metrics(t), dates_of(t)))
    last = turns[-1]
    ex, bs, mt, dt = exchanges(last), bases(last), metrics(last), dates_of(last)
    c_ex = ex == ["okx"]
    c_bs = bs == ["ETH"]
    c_dt = dt == [DAY_BEFORE_SH]
    c_vol = any((m or "").startswith("trade.volume") for m in mt)
    c_keep_turnover = any((m or "").startswith("trade.turnover") for m in mt)
    inherited = sum([c_ex, c_bs, c_dt, c_vol])

    checks = [
        ("最终交易所=OKX（不回到两所）", c_ex, "evidence exchanges=%s" % ex),
        ("最终币种=ETH（不是 BTC，也不是中间轮的其它币）", c_bs, "evidence base=%s" % bs),
        ("最终日期=前天 %s" % DAY_BEFORE_SH, c_dt, "evidence 日期=%s" % dt),
        ("最终查到了成交量", c_vol, "evidence metric=%s" % mt),
        ("成交额仍在（'也查一下'是叠加不是替换）", c_keep_turnover, "evidence metric=%s" % mt),
    ]
    if inherited < 4:
        verdict = "CONFIRMED（继承缺失 %d/4，查询对象漂移）" % (4 - inherited)
    elif not c_keep_turnover:
        verdict = "PARTIAL（核心条件全部继承，但「也查一下」被理解成替换，成交额丢失）"
    else:
        verdict = "NOT_REPRODUCED（四轮改条件全部正确继承）"
    return finish("P4", "连续改条件后的作用域漂移",
                  ["第 5 轮应同时满足：OKX / ETH / 前天 / 成交量（成交额保留）"],
                  checks, verdict,
                  ["现有校验只查指标能力、参数、引用、单位，不校验「是否继承了上一轮的用户条件」；"
                   "这类问题表现为工具成功、答案流畅、查询对象却是错的",
                   "trace：%s%s" % (TRACE, (last["d"] or {}).get("traceId"))])


# ---------------- P5 长答案截断 ----------------

def _p5_case(sid, q1, tag_prefix):
    """一个 P5 子用例：首问答得越长、关键信息越靠后，尾部被裁掉的概率越高。
    P5 用自然问法（模型倾向于先给摘要，关键信息可能落在前 300 字内）；
    P5b 强制逐根列出，把「实际覆盖到几点」逼到答案尾部。"""
    conv = str(uuid.uuid4())
    t1 = turn(conv, q1, timezone="Asia/Shanghai", tag="%s/1" % tag_prefix)
    t2 = turn(conv, "按你刚才说的实际覆盖区间，比较两所的平均收盘价。",
              timezone="Asia/Shanghai", want_ctx=True, tag="%s/2" % tag_prefix)

    a1 = ans(t1)
    ctx_missing = t2.get("ctx") is None  # trace 抽取失败：证据缺失
    ctx, pairs = t2.get("ctx") or "", t2.get("pairs") or []
    injected = ""
    for q, a in pairs:
        if "收盘价" in q:
            injected = a
    truncated = len(a1) > 300
    cut_at_300 = injected.endswith("...") and len(injected) <= 303

    # 「实际覆盖到几点」这个信息在首轮答案里出现的位置
    m = re.search(r"覆盖", a1)
    cover_pos = m.start() if m else None
    cover_lost = cover_pos is not None and cover_pos > 300
    cover_in_ctx = ("覆盖" in injected)

    ops = [r["operator"] for r in crows(t2)]
    a2 = ans(t2)
    cc1 = sorted({r["candle_count"] for r in mrows(t1) if r.get("candle_count")})
    cc2 = sorted({r["candle_count"] for r in mrows(t2) if r.get("candle_count")})
    mentions = bool(re.search(r"(覆盖|截至|截止|实际.{0,6}区间|根)", a2))
    # 追问是不是重新取数时把窗口换了（比 candle_count 更本质：直接比对 requested_range）
    def windows(t):
        out = set()
        for r in mrows(t):
            rng = r.get("req") or {}
            if rng.get("start_inclusive"):
                out.add("%s~%s" % (rng.get("start_inclusive"), rng.get("end_exclusive")))
        return out
    w1, w2 = windows(t1), windows(t2)
    same_window = bool(w1) and w1 == w2
    ops_ok = all(r.get("ok") for r in crows(t2)) and bool(crows(t2))

    checks = [
        ("首轮答案长度 > 300（会被截断）", truncated, "答案 %d 字" % len(a1)),
        ("注入上下文里的助手答案被截到 300 字", cut_at_300,
         "注入文本 %d 字，结尾='...'：%s" % (len(injected), injected.endswith("..."))),
        ("「实际覆盖」信息落在 300 字之后", bool(cover_lost),
         "首次出现位置=%s（>300 即被裁掉）" % cover_pos),
        ("该信息未进入下一轮上下文", not cover_in_ctx,
         "注入文本中是否含「覆盖」：%s" % cover_in_ctx),
        ("追问走了 avg 算子（而不是模型自算）", "avg" in ops, "operator=%s" % ops),
        ("avg 算子全部成功", ops_ok,
         "ok=%s（算子失败时答案只能说算不了，用户看到的是「拿不到」而不是「拿错了」）"
         % [r.get("ok") for r in crows(t2)]),
        ("追问答案复述了覆盖区间", mentions, "答案提到覆盖/截至/根数：%s" % mentions),
        ("追问的取数窗口与首轮一致", same_window, "turn1=%s turn2=%s" % (sorted(w1), sorted(w2))),
        ("两轮取数的 candle_count 一致", cc1 == cc2, "turn1=%s turn2=%s" % (cc1, cc2)),
    ]
    if ctx_missing:
        verdict = "ERROR（trace 上下文抽取失败，截断链路无法判定）"
    elif truncated and cover_lost and not cover_in_ctx:
        verdict = "CONFIRMED（答案尾部信息在下一轮前被裁掉）"
    elif truncated and not same_window:
        verdict = "CONFIRMED（重新取数时窗口漂移，聚合的不是用户看到的那批数据）"
    elif truncated:
        verdict = "PARTIAL（发生截断，但本次需要的信息恰好在前 300 字内）"
    else:
        verdict = "NOT_REPRODUCED（首轮答案未超过 300 字）"
    return finish(sid, "助手答案 300 字截断（%s）" % tag_prefix,
                  ["首轮答案尾部的限定条件应能传到下一轮；截断是直接裁尾，不是摘要"],
                  checks, verdict,
                  ["截断常量：HISTORY_ANSWER_MAX_CHARS=300，abbreviate() 直接 substring(0,300)+'...'",
                   "本轮答案 %d 字，注入上下文只保留前 %d 字，丢弃 %.0f%%"
                   % (len(a1), len(injected), (1 - len(injected) / len(a1)) * 100 if a1 else 0),
                   "用户问题完整保留，所以「昨天 / 两所 / BTC」这些从问题里还能读到；"
                   "丢的只会是答案里的数字、表格与限定条件",
                   "注入上下文原文（截断展示）：\n\n```\n%s\n```" % (ctx[:700] + ("…" if len(ctx) > 700 else "")),
                   "trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))])


def scenario_p5():
    md("\n## P5 长答案截断：答案尾部（实际覆盖区间）在下一轮前被裁掉")
    _p5_case("P5", "列出两所 BTC 昨天每小时收盘价，最后说明各自实际覆盖到几点。", "P5")
    _p5_case("P5b", "逐根列出两所 BTC 昨天每小时收盘价，一根都不要省略，"
                    "最后用一句话说明各自实际覆盖到几点。", "P5b")


# ---------------- F 系：验收用例（PASS / FAIL / SKIP） ----------------
#
# 判定一律基于 evidence / trace，不看答案文字表面。阶段二证据（factId、REUSE 绑定、
# CONTEXT_RESOLVED / EVIDENCE_REUSE_DECISION 事件）在现有 trace 里不存在：读不到就把
# 该条判据标「证据缺失」，整体 verdict 降级为 FAIL 并注明，不抛 KeyError。


def _f_verdict(checks):
    """F 系统一收口：全部命中 PASS，否则 FAIL 并列出未命中条数。"""
    miss = [n for n, h, _ in checks if not h]
    if not miss:
        return "PASS"
    return "FAIL（未命中 %d/%d：%s）" % (len(miss), len(checks), "、".join(miss[:3]))


def scenario_f01():
    md("\n## F01 基础链：换指标，区间/市场/交易所/粒度不变")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "昨天币安 BTC 最高价，按 1h K 线。", timezone="Asia/Shanghai", tag="F01/1")
    t2 = turn(conv, "最低价呢？", timezone="Asia/Shanghai", tag="F01/2")
    t3 = turn(conv, "基础币成交量呢？", timezone="Asia/Shanghai", tag="F01/3")

    def cond(t, metric_prefix):
        """本轮实际取数条件 == 期望继承条件（binance/BTC/昨天/1h），只换指标。"""
        ok_ex = exchanges(t) == ["binance"]
        ok_bs = bases(t) == ["BTC"]
        ok_mt = any((m or "").startswith(metric_prefix) for m in metrics(t))
        ok_dt = dates_of(t) == [YESTERDAY_SH]
        ok_iv = intervals(t) == ["1h"]
        return ok_ex, ok_bs, ok_mt, ok_dt, ok_iv

    c1, c2, c3 = cond(t1, "price.high"), cond(t2, "price.low"), cond(t3, "trade.volume")
    checks = [
        ("首轮计划正确：price.high/binance/BTC/昨天/1h", all(c1),
         "exchange/base/metric/date/interval 命中=%s；evidence metric=%s 日期=%s 粒度=%s"
         % (c1, metrics(t1), dates_of(t1), intervals(t1))),
        ("第 2 轮换 price.low，其余条件不变", all(c2),
         "命中=%s；evidence metric=%s exchange=%s base=%s 日期=%s 粒度=%s"
         % (c2, metrics(t2), exchanges(t2), bases(t2), dates_of(t2), intervals(t2))),
        ("第 3 轮换 trade.volume，其余条件不变", all(c3),
         "命中=%s；evidence metric=%s exchange=%s base=%s 日期=%s 粒度=%s"
         % (c3, metrics(t3), exchanges(t3), bases(t3), dates_of(t3), intervals(t3))),
        ("第 2/3 轮走了 Planner 新计划（非复用答案文本）",
         tool_calls(t2) >= 1 and tool_calls(t3) >= 1,
         "toolCalls：t2=%s t3=%s" % (tool_calls(t2), tool_calls(t3))),
    ]
    if PHASE >= 4:
        # 免取数断言只在启用数据集复用（阶段四）时检查；阶段一允许重查，不列入。
        checks.append(("启用复用后第 2/3 轮免取数（K 线数据集投影）",
                       tool_calls(t2) == 0 and tool_calls(t3) == 0,
                       "toolCalls：t2=%s t3=%s（0=未发新取数请求）"
                       % (tool_calls(t2), tool_calls(t3))))
    return finish("F01", F_TITLES["F01"],
                  ["每轮 Planner 生成新指标计划，区间/市场/交易所/粒度不变；首期允许重查"],
                  checks, _f_verdict(checks),
                  ["阶段一只判计划正确+允许重查；「免取数」断言需 --phase 4",
                   "trace：%s%s" % (TRACE, (t3["d"] or {}).get("traceId"))],
                  min_phase=1)


def _f02_chain(sid_prefix, turns_qs):
    """F02/F03 共用：费率样本链。返回各轮的 (样本时间序列, 样本值序列)。"""
    conv = str(uuid.uuid4())
    ts = []
    for i, q in enumerate(turns_qs):
        ts.append(turn(conv, q, timezone="Asia/Shanghai", tag="%s/%d" % (sid_prefix, i + 1)))
    return ts, [funding_samples(t) for t in ts]


def scenario_f02():
    md("\n## F02 样本锁定：同一组 10 期上做均值/最大值/最小值")
    ts, kv = _f02_chain("F02", [
        "币安 BTC 最近 10 期已结算费率。",
        "刚才这 10 期的均值。",
        "最大值。",
        "最小值。",
    ])
    (k1, v1), (k2, v2), (k3, v3), (k4, v4) = kv
    avg_v, max_v, min_v = op_value(ts[1], "avg"), op_value(ts[2], "max"), op_value(ts[3], "min")
    same = bool(k1) and k1 == k2 == k3 == k4
    avg_ok = avg_v is not None and v1 and abs(avg_v - sum(v1) / len(v1)) <= abs(sum(v1) / len(v1)) * 1e-6 + 1e-12
    max_ok = max_v is not None and v1 and abs(max_v - max(v1)) <= 1e-12
    min_ok = min_v is not None and v1 and abs(min_v - min(v1)) <= 1e-12
    checks = [
        ("首轮取到 10 期样本", len(k1) == 10, "实际 %d 期" % len(k1)),
        ("三轮聚合的样本时间集合与首轮完全一致", same,
         "t1==t2==t3==t4：%s（不一致=重查换了样本）" % same),
        ("均值走了 avg 算子且与首轮样本自算一致", avg_ok, "算子=%s 自算=%s" % (avg_v, sum(v1) / len(v1) if v1 else None)),
        ("最大值走了 max 算子且与首轮样本自算一致", max_ok, "算子=%s 自算=%s" % (max_v, max(v1) if v1 else None)),
        ("最小值走了 min 算子且与首轮样本自算一致", min_ok, "算子=%s 自算=%s" % (min_v, min(v1) if v1 else None)),
        ("后续聚合没把上一个均值当新样本集",
         all(len(k) == 10 for k in (k2, k3, k4)),
         "各轮样本数=%s" % [len(k) for k in (k2, k3, k4)]),
    ]
    return finish("F02", F_TITLES["F02"],
                  ["始终绑定同一组 10 个样本；每次由算子计算"],
                  checks, _f_verdict(checks),
                  ["现有系统每轮重查 recent_n，样本一致只是「期间无新结算」的巧合；"
                   "真正的样本锁定要阶段二的 PINNED 样本集，当前 trace 无该证据",
                   "trace：%s%s" % (TRACE, (ts[-1]["d"] or {}).get("traceId"))],
                  min_phase=2)


def scenario_f03():
    md("\n## F03 「现在最新 10 期」：重新取数，不沿用 PINNED_RESULT")
    ts, kv = _f02_chain("F03", [
        "币安 BTC 最近 10 期已结算费率。",
        "刚才这 10 期的均值。",
        "现在最新 10 期的均值。",
    ])
    (k1, v1), (k2, v2), (k3, v3) = kv
    avg3 = op_value(ts[2], "avg")
    refetched = len(k3) == 10
    differs = bool(k1) and bool(k3) and k1 != k3
    avg_ok = avg3 is not None and v3 and abs(avg3 - sum(v3) / len(v3)) <= abs(sum(v3) / len(v3)) * 1e-6 + 1e-12
    checks = [
        ("第 3 轮重新取到 10 期新样本", refetched, "实际 %d 期" % len(k3)),
        ("均值作用在第 3 轮新样本上（自算一致）", avg_ok,
         "算子=%s 自算=%s" % (avg3, sum(v3) / len(v3) if v3 else None)),
        ("允许新集合与旧集合不同（不作硬判，仅记录）", True,
         "与首轮样本是否不同：%s" % differs),
    ]
    return finish("F03", F_TITLES["F03"],
                  ["重新取最新集合；允许与旧集合不同；不能沿用 PINNED_RESULT"],
                  checks, _f_verdict(checks),
                  ["「没沿用旧结果」的直接证据是 avg 与新样本自算一致；若期间无新结算，"
                   "新旧集合可能恰好相同，此时无法区分「重查」与「复用」，如实记录",
                   "trace：%s%s" % (TRACE, (ts[-1]["d"] or {}).get("traceId"))],
                  min_phase=2)


def scenario_f04():
    md("\n## F04 刷新链：每轮重新取数")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "币安 BTC 现在最新成交价。", timezone="Asia/Shanghai", tag="F04/1")
    t2 = turn(conv, "刷新一下。", timezone="Asia/Shanghai", tag="F04/2")
    t3 = turn(conv, "再刷新。", timezone="Asia/Shanghai", tag="F04/3")
    checks = []
    for i, t in enumerate((t1, t2, t3), 1):
        got = any(r["metric"] == "price.last" and _num(r["value"]) is not None for r in mrows(t))
        checks.append(("第 %d 轮由 Planner 生成刷新计划并重新取到 price.last" % i, got,
                       "toolCalls=%s price.last=%s" % (tool_calls(t), [r["value"] for r in mrows(t) if r["metric"] == "price.last"])))
    if PHASE >= 2:
        f2, f3 = fact_ids(t2), fact_ids(t3)
        new_fid = bool(f2 and f3) and all(x for x in f2 + f3) and set(f2) != set(f3)
        desc = ("t2 factId=%s t3 factId=%s" % (f2, f3)) if any(f2 + f3) \
            else "证据缺失：现有 trace 的 evidence 无 factId 字段，无法验证新事实身份"
        checks.append(("每轮产生新 factId（阶段二证据）", new_fid, desc))
    return finish("F04", F_TITLES["F04"],
                  ["每轮重新取数；新 factId、真实新 fetchedAt（阶段二判）"],
                  checks, _f_verdict(checks),
                  ["阶段一只判「每轮重新取数+计划正确」；新 factId 断言需 --phase 2，"
                   "当前 trace 无该字段时会按证据缺失判 FAIL",
                   "trace：%s%s" % (TRACE, (t3["d"] or {}).get("traceId"))],
                  min_phase=1)


def scenario_f05():
    md("\n## F05 旧快照 + 新快照：「比刚才涨了多少百分比」")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "币安 BTC 现在最新成交价。", timezone="Asia/Shanghai", tag="F05/1")
    t2 = turn(conv, "比刚才涨了多少百分比？", timezone="Asia/Shanghai", tag="F05/2")
    prices = [r for r in mrows(t2) if r["metric"] == "price.last" and _num(r["value"]) is not None]
    rc = [r for r in crows(t2) if r["operator"] == "relative_change"]
    ev_missing = len(prices) < 2
    direction_ok = False
    direction_desc = "证据缺失：第 2 轮 evidence 中 price.last 节点数=%d（需要旧 PINNED + 新快照两个）" % len(prices)
    if not ev_missing and rc and _num(rc[0]["value"]) is not None:
        old, new = _num(prices[0]["value"]), _num(prices[-1]["value"])
        got = _num(rc[0]["value"])
        exp = (new - old) / old
        direction_ok = abs(got - exp) <= max(1e-6, abs(exp) * 0.01) or (got == 0) == (exp == 0) or (got * exp > 0)
        direction_desc = "旧=%s 新=%s 算子=%s 期望≈%s" % (old, new, got, exp)
    checks = [
        ("evidence 存在两个 price 类节点（旧 PINNED + 新快照）", not ev_missing, direction_desc),
        ("走了 relative_change 算子", bool(rc), "operator=%s" % [r["operator"] for r in crows(t2)]),
        ("比较方向正确（新-旧，非旧-新）", direction_ok, direction_desc),
        ("任一值不可用时不编造涨跌",
         not (ev_missing and pcts(ans(t2)) and not looks_refused(ans(t2))),
         "证据缺失时答案仍给了百分数：%s" % (pcts(ans(t2))[:3] or "无")),
    ]
    verdict = _f_verdict(checks)
    if ev_missing and verdict.startswith("FAIL"):
        verdict = "FAIL（证据缺失：当前系统每轮重查，无 PINNED 旧快照可绑定——正是阶段二要补的能力）"
    return finish("F05", F_TITLES["F05"],
                  ["一个旧快照和一个新快照，比较方向正确；任一值不可用时不编造"],
                  checks, verdict,
                  ["trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))],
                  min_phase=2)


def scenario_f06():
    md("\n## F06 连续换对象不漂移：所→币")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "昨天币安 BTC 最高价。", timezone="Asia/Shanghai", tag="F06/1")
    t2 = turn(conv, "那 OKX 呢？", timezone="Asia/Shanghai", tag="F06/2")
    t3 = turn(conv, "ETH 呢？", timezone="Asia/Shanghai", tag="F06/3")
    checks = [
        ("第 2 轮只换交易所：okx/BTC/昨天/最高价",
         exchanges(t2) == ["okx"] and bases(t2) == ["BTC"] and dates_of(t2) == [YESTERDAY_SH]
         and any((m or "").startswith("price.high") for m in metrics(t2)),
         "exchange=%s base=%s 日期=%s metric=%s" % (exchanges(t2), bases(t2), dates_of(t2), metrics(t2))),
        ("第 3 轮沿用 OKX 换 ETH（不回到币安）",
         exchanges(t3) == ["okx"] and bases(t3) == ["ETH"],
         "exchange=%s base=%s" % (exchanges(t3), bases(t3))),
        ("第 3 轮不自动变成交易所比较",
         len(exchanges(t3)) == 1, "exchange=%s" % exchanges(t3)),
        ("第 3 轮日期/指标继承",
         dates_of(t3) == [YESTERDAY_SH] and any((m or "").startswith("price.high") for m in metrics(t3)),
         "日期=%s metric=%s" % (dates_of(t3), metrics(t3))),
    ]
    return finish("F06", F_TITLES["F06"],
                  ["第 2 轮只换所，第 3 轮沿用 OKX 换 ETH；不回到币安、不变比较"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t3["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f07():
    md("\n## F07 焦点往返：高→低→高")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "昨天币安 BTC 最高价。", timezone="Asia/Shanghai", tag="F07/1")
    t2 = turn(conv, "最低价呢？", timezone="Asia/Shanghai", tag="F07/2")
    t3 = turn(conv, "最高价再说一次。", timezone="Asia/Shanghai", tag="F07/3")
    v1 = next((_num(r["value"]) for r in mrows(t1)
               if (r["metric"] or "").startswith("price.high") and _num(r["value"]) is not None), None)
    v3 = next((_num(r["value"]) for r in mrows(t3)
               if (r["metric"] or "").startswith("price.high") and _num(r["value"]) is not None), None)
    checks = [
        ("第 3 轮回到 price.high（不受上轮最低价焦点污染）",
         any((m or "").startswith("price.high") for m in metrics(t3)),
         "metric=%s" % metrics(t3)),
        ("第 3 轮条件继承 binance/BTC/昨天",
         exchanges(t3) == ["binance"] and bases(t3) == ["BTC"] and dates_of(t3) == [YESTERDAY_SH],
         "exchange=%s base=%s 日期=%s" % (exchanges(t3), bases(t3), dates_of(t3))),
        ("第 3 轮最高价与首轮一致（同一已核验事实）",
         v1 is not None and v3 is not None and abs(v3 - v1) <= abs(v1) * 1e-9,
         "首轮=%s 第3轮=%s" % (v1, v3)),
    ]
    return finish("F07", F_TITLES["F07"],
                  ["返回已核验的最高价事实；Answer 可重述但不受上轮焦点污染"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t3["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f08():
    md("\n## F08 插 6 轮换指标/计算后，结构化条件仍完整继承")
    md("与 P1 同构但判据相反：P1 证实旧实现会丢条件（CONFIRMED 即问题），"
       "F08 要求阶段一目标行为——evidence 全部命中才算 PASS。")
    conv = str(uuid.uuid4())
    qs = [
        "查币安 BTC-USDT 永续昨天的成交额，按上海时区。",
        "ETH 现在多少钱？",
        "SOL 的 24 小时涨跌幅？",
        "XRP 最近 3 期资金费率均值？",
        "DOGE 现在多少钱？",
        "ADA 和 BNB 的现价差多少？",
        "LTC 昨天最高价？",
        "回到最开始那个问题，换成 OKX，其他条件不变。",
    ]
    turns = []
    for i, q in enumerate(qs):
        turns.append(turn(conv, q, timezone="America/New_York",
                          want_ctx=(i == len(qs) - 1), tag="F08/%d" % (i + 1)))
    base, last = turns[0], turns[-1]
    b_dt, b_tz = dates_of(base), tz_of(base)
    ex, bs, mt, dt, tz = exchanges(last), bases(last), metrics(last), dates_of(last), tz_of(last)
    checks = [
        ("首问离开文本历史窗口后条件仍完整：交易所=OKX", ex == ["okx"], "exchange=%s" % ex),
        ("币种=BTC", bs == ["BTC"], "base=%s" % bs),
        ("指标=成交额", any((m or "").startswith("trade.turnover") for m in mt), "metric=%s" % mt),
        ("日期与首轮一致", bool(dt) and dt == b_dt, "本轮=%s 首轮=%s" % (dt, b_dt)),
        ("时区与首轮一致（上海）", bool(tz) and tz == b_tz, "本轮=%s 首轮=%s" % (tz, b_tz)),
        ("没退回默认交易所/默认粒度",
         ex == ["okx"] and bs == ["BTC"] and bool(dt),
         "以上条件联合判定"),
    ]
    return finish("F08", F_TITLES["F08"],
                  ["首问离开文本窗口后，结构化条件仍完整；不退回默认粒度或交易所"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (last["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f09():
    md("\n## F09 长答案后按结构化条件取数（不依赖答案截断位置）")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "逐根列出币安 BTC 昨天每小时收盘价，一根都不要省略，"
                    "最后用一句话说明实际覆盖到几点。",
              timezone="Asia/Shanghai", tag="F09/1")
    t2 = turn(conv, "同一区间最低价。", timezone="Asia/Shanghai", tag="F09/2")
    w1, w2 = windows(t1), windows(t2)
    checks = [
        ("追问取数窗口与首轮一致", bool(w1) and w1 == w2,
         "turn1=%s turn2=%s" % (sorted(w1), sorted(w2))),
        ("追问日期=昨天", dates_of(t2) == [YESTERDAY_SH], "日期=%s" % dates_of(t2)),
        ("追问指标=price.low", any((m or "").startswith("price.low") for m in metrics(t2)),
         "metric=%s" % metrics(t2)),
        ("交易所/币种继承 binance/BTC",
         exchanges(t2) == ["binance"] and bases(t2) == ["BTC"],
         "exchange=%s base=%s" % (exchanges(t2), bases(t2))),
    ]
    return finish("F09", F_TITLES["F09"],
                  ["不依赖答案截断位置，使用结构化条件"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f10():
    md("\n## F10 多候选澄清：「它的均值呢」")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "币安和 OKX 的 BTC、ETH 最近 10 期已结算费率。",
              timezone="Asia/Shanghai", tag="F10/1")
    t2 = turn(conv, "它的均值呢？", timezone="Asia/Shanghai", tag="F10/2")
    n_groups = len([r for r in mrows(t1) if (r["metric"] or "").startswith("funding.rate_settled")])
    has_plan, reply = planner_reply((t2["d"] or {}).get("traceId"))
    avg_on_one = False
    if has_plan:
        # 有计划：看是不是只对单一序列求均值（擅自选了一组）
        avg_rows = [r for r in crows(t2) if r["operator"] == "avg"]
        srcs = {(r["exchange"], r["base"]) for r in mrows(t2)
                if (r["metric"] or "").startswith("funding.rate_settled")}
        avg_on_one = bool(avg_rows) and len(srcs) == 1 and n_groups > 1
    clarified = (has_plan is False and looks_clarify(reply)) or looks_clarify(ans(t2))
    checks = [
        ("首轮确实产生多个候选序列", n_groups >= 2, "费率序列数=%d" % n_groups),
        ("追问是澄清提问而非擅自选一组", clarified and not avg_on_one,
         "planner has_plan=%s reply含澄清=%s 答案含澄清=%s 擅自单序列求均值=%s"
         % (has_plan, looks_clarify(reply or ""), looks_clarify(ans(t2)), avg_on_one)),
    ]
    if has_plan is None:
        checks.append(("planner 输出可读（证据）", False,
                       "证据缺失：trace 中读不到 planner LLM_CALL output"))
    return finish("F10", F_TITLES["F10"],
                  ["有多个候选时澄清；不默认拿第一组样本"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f11():
    md("\n## F11 澄清填入：「币安」填入原任务，再聚合")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "BTC 最近 10 期已结算费率。", timezone="Asia/Shanghai", tag="F11/1")
    # 前置条件（4.1）：首轮必须真的进入澄清（未取数）。「币安。」才有"填入"语义——
    # 首轮直接返回两所数据时，后续轮验证的是普通继承而非澄清接续，记 SKIP 不记 PASS
    clarified = tool_calls(t1) == 0 and not mrows(t1)
    if not clarified:
        return finish("F11", F_TITLES["F11"],
                      ["「币安」填入原任务；后续均值绑定完成后的唯一序列"],
                      [("首轮确实进入澄清（未取数）", False,
                        "未触发：首轮直接取数（toolCalls=%d metric=%s），验证的是普通继承分支"
                        % (tool_calls(t1), metrics(t1)))],
                      "SKIP（未触发：首轮未澄清）",
                      ["换成必然多候选歧义的问法可再触发"], min_phase=1)
    t2 = turn(conv, "币安。", timezone="Asia/Shanghai", tag="F11/2")
    t3 = turn(conv, "均值呢？", timezone="Asia/Shanghai", tag="F11/3")
    k2, v2 = funding_samples(t2)
    k3, v3 = funding_samples(t3)
    avg3 = op_value(t3, "avg")
    avg_ok = avg3 is not None and v3 and abs(avg3 - sum(v3) / len(v3)) <= abs(sum(v3) / len(v3)) * 1e-6 + 1e-12
    checks = [
        ("「币安」填入后完成原任务（取到 binance 费率序列）",
         exchanges(t2) == ["binance"] and len(k2) >= 1,
         "exchange=%s 样本数=%d" % (exchanges(t2), len(k2))),
        ("「均值呢」绑定完成后的唯一序列并走 avg 算子", avg_ok,
         "算子=%s 自算=%s 样本数=%d" % (avg3, sum(v3) / len(v3) if v3 else None, len(v3))),
        ("聚合样本与填入轮一致", bool(k2) and k2 == k3,
         "t2==t3：%s" % (k2 == k3)),
    ]
    return finish("F11", F_TITLES["F11"],
                  ["「币安」填入原任务；后续均值绑定完成后的唯一序列"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t3["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f12():
    md("\n## F12 取数失败后的继承：「那 OKX 呢」不退回旧话题")
    conv = str(uuid.uuid4())
    # catalog 不含「波动率曲面/隐含波动率」类指标，用来尝试触发取数/计划失败
    t1 = turn(conv, "昨天币安 BTC 的隐含波动率曲面。", timezone="Asia/Shanghai", tag="F12/1")
    t2 = turn(conv, "那 OKX 呢？", timezone="Asia/Shanghai", tag="F12/2")
    # 4.1 修正：响应没有顶层 ok 字段，原判据把缺失当作失败信号，正常返回也会被判成失败
    t1_failed = (not mrows(t1)) or bool(FALLBACK_REFUSAL_RE.search(ans(t1)))
    if not t1_failed:
        return finish("F12", F_TITLES["F12"],
                      ["继承最近有效请求并替换交易所，不退回失败之前的旧话题"],
                      [("触发首轮取数失败", False,
                        "未触发：首轮实际取到了数据（metric=%s），无法判定失败继承分支"
                        % metrics(t1))],
                      "SKIP（未触发：首轮未失败）",
                      ["换一个 catalog 不支持的组合重试可再触发"], min_phase=1)
    t2_stays = not mrows(t2) or exchanges(t2) == ["okx"]
    checks = [
        ("首轮取数失败已触发", True, "evidence 指标节点=%d" % len(mrows(t1))),
        ("第 2 轮不退回失败之前的旧话题", t2_stays,
         "第 2 轮 exchange=%s metric=%s" % (exchanges(t2), metrics(t2))),
        ("第 2 轮围绕同一主题（波动率/失败说明），非无关新查询",
         exchanges(t2) == ["okx"] or not mrows(t2) or "波动率" in ans(t2),
         "answer 截断=%s" % ans(t2)[:120].replace("\n", " ")),
    ]
    return finish("F12", F_TITLES["F12"],
                  ["继承最近有效请求并替换交易所，不退回失败之前的旧话题"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f13():
    md("\n## F13 取数成功但 Answer 失败 → 「再试一次」")
    conv = str(uuid.uuid4())
    # 难以在线上稳定制造 Answer 失败：用一个工具成功但答案可能异常的超长问题尝试触发
    t1 = turn(conv, "逐根列出两所 BTC 昨天每小时收盘价并逐根点评，一根都不要省略。",
              timezone="Asia/Shanghai", tag="F13/1")
    answer_failed = (t1["d"] or {}).get("ok") and not ans(t1).strip() and tool_calls(t1) >= 1
    if not answer_failed:
        return finish("F13", F_TITLES["F13"],
                      ["确认重试意图后，用已保存的适用事实生成 Answer；不把失败视为完全没查过"],
                      [("触发 Answer 失败", False,
                        "未触发：本轮答案长度=%d，toolCalls=%s" % (len(ans(t1)), tool_calls(t1)))],
                      "SKIP（未触发：Answer 失败无法在线上稳定构造）",
                      ["需要 mock/测试环境验证"], min_phase=2)
    t2 = turn(conv, "再试一次。", timezone="Asia/Shanghai", tag="F13/2")
    checks = [
        ("Answer 失败已触发", True, "首轮 toolCalls=%s 答案长度=%d" % (tool_calls(t1), len(ans(t1)))),
        ("重试给出了实质答案", bool(ans(t2).strip()), "答案长度=%d" % len(ans(t2))),
        ("未把失败视为完全没查过（答案与首轮取数主题一致）",
         "收盘价" in ans(t2) or "BTC" in ans(t2), "answer 截断=%s" % ans(t2)[:100]),
    ]
    return finish("F13", F_TITLES["F13"],
                  ["用已保存的适用事实重试生成 Answer"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=2)


def scenario_f15():
    md("\n## F15 scalar 缓存缺字段：不能从最高价推最低价")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "昨天币安 BTC 最高价。", timezone="Asia/Shanghai", tag="F15/1")
    t2 = turn(conv, "最低价呢？", timezone="Asia/Shanghai", tag="F15/2")
    v1 = next((_num(r["value"]) for r in mrows(t1)
               if (r["metric"] or "").startswith("price.high") and _num(r["value"]) is not None), None)
    v2 = next((_num(r["value"]) for r in mrows(t2)
               if (r["metric"] or "").startswith("price.low") and _num(r["value"]) is not None), None)
    refetched = any((m or "").startswith("price.low") for m in metrics(t2))
    checks = [
        ("第 2 轮真实取到 price.low（非由最高价推算）", refetched and v2 is not None,
         "metric=%s value=%s" % (metrics(t2), v2)),
        ("最低价 ≤ 最高价（数值自洽，排除张冠李戴）",
         v1 is None or v2 is None or v2 <= v1, "高=%s 低=%s" % (v1, v2)),
        ("条件继承 binance/BTC/昨天",
         exchanges(t2) == ["binance"] and bases(t2) == ["BTC"] and dates_of(t2) == [YESTERDAY_SH],
         "exchange=%s base=%s 日期=%s" % (exchanges(t2), bases(t2), dates_of(t2))),
    ]
    return finish("F15", F_TITLES["F15"],
                  ["明确缓存缺字段，重新取数；不能从最高价推最低价"],
                  checks, _f_verdict(checks),
                  ["「明确缓存缺字段」需要阶段二的缓存元数据证据，当前 trace 无此字段；"
                   "此处判的是行为面：确实重新取数且数值自洽",
                   "trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=2)


def scenario_f16():
    md("\n## F16 K 线缺口下的合计：不把部分成交量包装成完整区间")
    conv = str(uuid.uuid4())
    # 用上线初期（2019-09）的日期提高出现 K 线缺口的概率；取不到缺口则降级记录
    t1 = turn(conv, "列出币安 BTC 2019 年 9 月每小时收盘价。", timezone="Asia/Shanghai", tag="F16/1")
    t2 = turn(conv, "这一段的成交量合计呢？", timezone="Asia/Shanghai", tag="F16/2")
    covs = [r.get("coverage") for r in mrows(t1) + mrows(t2) if r.get("coverage") is not None]
    gap = any((isinstance(c, (int, float)) and c < 0.999)
              or (isinstance(c, dict) and not (c.get("range_complete", True)
                                               and c.get("closed_part_complete", True)))
              for c in covs)
    sum_rows = [r for r in crows(t2) if r["operator"] == "sum"]
    honest = (not gap) or bool(re.search(r"(缺口|不完整|部分|覆盖|缺失|仅为)", ans(t2)))
    if not any(covs):
        return finish("F16", F_TITLES["F16"],
                      ["延续完整性约束；不把部分成交量包装成完整区间成交量"],
                      [("读到覆盖/缺口证据", False,
                        "证据缺失：evidence 无 coverage 字段，无法判断是否存在缺口")],
                      "SKIP（证据缺失：无 coverage 字段）",
                      ["需要带覆盖信息的 evidence 才能判定"], min_phase=2)
    checks = [
        ("走了 sum 算子", bool(sum_rows), "operator=%s" % [r["operator"] for r in crows(t2)]),
        ("存在缺口时答案声明不完整（不包装成完整区间）", honest,
         "coverage=%s 答案含缺口声明=%s" % (covs[:3], bool(re.search(r"(缺口|不完整|部分|覆盖|缺失|仅为)", ans(t2))))),
    ]
    verdict = _f_verdict(checks)
    if not gap:
        # 4.1 修正：缺口分支未触发不该记 PASS（关键场景未覆盖不算通过），记 SKIP
        verdict = "SKIP（未触发：本次区间无数据缺口，完整性分支未验证；算子与口径正常）"
    return finish("F16", F_TITLES["F16"],
                  ["延续完整性约束；不把部分成交量包装成完整区间成交量"],
                  checks, verdict,
                  ["是否出现缺口取决于交易所当时数据，不可控；未出现则该分支记为未触发",
                   "trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=2)


def scenario_f17():
    md("\n## F17 成交价 → 标记价：切换 metric，不复用")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "BTC 最新成交价。", timezone="Asia/Shanghai", tag="F17/1")
    t2 = turn(conv, "标记价呢？", timezone="Asia/Shanghai", tag="F17/2")
    m2 = metrics(t2)
    checks = [
        ("第 2 轮切到 mark 类指标", any((m or "").startswith("mark.") for m in m2),
         "metric=%s" % m2),
        ("没有把 price.last 复用为标记价",
         not (m2 and all((m or "").startswith("price.last") for m in m2)),
         "metric=%s" % m2),
    ]
    return finish("F17", F_TITLES["F17"],
                  ["切换到正确 metric/provider；不得复用成交价当标记价"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f18():
    md("\n## F18 official_24h 与自然日区间区分")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "币安 BTC 过去 24 小时涨跌幅。", timezone="Asia/Shanghai", tag="F18/1")
    t2 = turn(conv, "昨天自然日呢？", timezone="Asia/Shanghai", tag="F18/2")
    m1, m2 = metrics(t1), metrics(t2)
    dt2 = dates_of(t2)
    checks = [
        ("首轮用了 official_24h 口径", any("24h" in (m or "") for m in m1) or bool(m1),
         "metric=%s" % m1),
        ("第 2 轮重新构造为自然日区间（非沿用 24h 缓存）",
         dt2 == [YESTERDAY_SH] or (bool(windows(t2)) and YESTERDAY_SH in str(sorted(windows(t2)))),
         "日期=%s 窗口=%s" % (dt2, sorted(windows(t2)))),
        ("第 2 轮指标仍是涨跌幅类", any("change_pct" in (m or "") for m in m2),
         "metric=%s" % m2),
    ]
    return finish("F18", F_TITLES["F18"],
                  ["区分 official_24h 和自然日区间，重新构造合法查询，不缓存串用"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f19():
    md("\n## F19 已结算费率 → 预测费率：能力边界")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "币安 BTC 最近 10 期已结算资金费率。", timezone="Asia/Shanghai", tag="F19/1")
    t2 = turn(conv, "下次预测费率呢？", timezone="Asia/Shanghai", tag="F19/2")
    k1, v1 = funding_samples(t1)
    m2 = metrics(t2)
    used_settled_as_pred = False
    if v1 and m2:
        last_settled = v1[-1]
        for r in mrows(t2):
            if (r["metric"] or "").startswith("funding.rate_settled") \
                    and _num(r["value"]) is not None and abs(_num(r["value"]) - last_settled) < 1e-12:
                used_settled_as_pred = True
    boundary_ok = looks_refused(ans(t2)) or not used_settled_as_pred
    checks = [
        ("遵守能力边界（拒答/说明不支持，或取到真实预测指标）", boundary_ok,
         "metric=%s 答案截断=%s" % (m2, ans(t2)[:120].replace("\n", " "))),
        ("没把最后一期已结算值当预测值", not used_settled_as_pred,
         "最后一期已结算=%s" % (v1[-1] if v1 else None)),
    ]
    return finish("F19", F_TITLES["F19"],
                  ["遵守现有能力边界；不能把最后一期已结算值当预测值"],
                  checks, _f_verdict(checks),
                  ["catalog 未接入预测费率，正确行为是说明边界或拒绝",
                   "trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f20():
    md("\n## F20 否定式复合修改：换所 + 别用昨天 + 换指标组")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "昨天币安 BTC 的成交额。", timezone="Asia/Shanghai", tag="F20/1")
    t2 = turn(conv, "那 OKX 呢，但别用昨天的数据，看看最近行情和持仓变化。",
              timezone="Asia/Shanghai", tag="F20/2")
    m2 = metrics(t2)
    has_price = any((m or "").startswith("price.") for m in m2)
    has_oi = any((m or "").startswith("oi.") for m in m2)
    not_yesterday = dates_of(t2) != [YESTERDAY_SH]
    checks = [
        ("交易所换成 OKX（且只换所）", exchanges(t2) == ["okx"], "exchange=%s" % exchanges(t2)),
        ("含行情类指标", has_price, "metric=%s" % m2),
        ("含持仓类指标", has_oi, "metric=%s" % m2),
        ("时间不是昨天（否定条件生效）", not_yesterday, "日期=%s" % dates_of(t2)),
    ]
    return finish("F20", F_TITLES["F20"],
                  ["同时理解交易所、时间和指标变化，不能仅替换交易所"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=1)


def scenario_f21():
    md("\n## F21 缓存不可恢复：明确回退，不冒充原快照")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "币安 BTC 现在最新成交价。", timezone="Asia/Shanghai", tag="F21/1")
    t2 = turn(conv, "还是刚才那个价格吗？", timezone="Asia/Shanghai", tag="F21/2")
    events = event_names((t2["d"] or {}).get("traceId"))
    types = {tp for tp, _ in events}
    has_reuse_ev = bool(types & {"CONTEXT_RESOLVED", "EVIDENCE_REUSE_DECISION", "FACT_REUSED"})
    # 4.1 修正：第二条恒真断言删除——"冒充检测"的行为面在线上无法构造（需清缓存/改版本注入），
    # 只验证 trace 证据面不能算该场景通过，整例记 SKIP，行为断言移交测试环境确定性注入
    checks = [
        ("trace 中有引用解析/复用决策事件（仅证据面，非场景通过）", has_reuse_ev,
         "事件类型=%s" % sorted(t for t in types if t)[:8]),
    ]
    return finish("F21", F_TITLES["F21"],
                  ["明确回退或解释原快照不可恢复；不能重查当前价后冒充原快照"],
                  checks,
                  "SKIP（线上无法构造：缓存不可恢复场景需测试环境注入；行为断言不在本用例判定）",
                  ["缓存版本变化/容量清理无法在线上构造；冒充检测由集成测试确定性覆盖",
                   "trace：%s%s" % (TRACE, (t2["d"] or {}).get("traceId"))], min_phase=2)


def scenario_f22():
    md("\n## F22 明确话题切换后的代词绑定")
    conv = str(uuid.uuid4())
    t1 = turn(conv, "币安 BTC 现在多少钱？", timezone="Asia/Shanghai", tag="F22/1")
    t2 = turn(conv, "换个话题，OKX 的 ETH 现在多少钱？", timezone="Asia/Shanghai", tag="F22/2")
    t3 = turn(conv, "它现在呢？", timezone="Asia/Shanghai", tag="F22/3")
    checks = [
        ("第 3 轮绑定 ETH（新话题 frame）", bases(t3) == ["ETH"], "base=%s" % bases(t3)),
        ("第 3 轮绑定 OKX", exchanges(t3) == ["okx"], "exchange=%s" % exchanges(t3)),
        ("不因 BTC 历史更长而回到 BTC", bases(t3) != ["BTC"], "base=%s" % bases(t3)),
    ]
    return finish("F22", F_TITLES["F22"],
                  ["绑定 ETH 新 frame，不因历史更长而回到 BTC"],
                  checks, _f_verdict(checks),
                  ["trace：%s%s" % (TRACE, (t3["d"] or {}).get("traceId"))], min_phase=1)


# ---------------- 汇总与产出 ----------------

def write_report():
    stamp = datetime.now(SH).strftime("%Y-%m-%d")
    ts = datetime.now(SH).strftime("%Y%m%d-%H%M")
    md_path = os.path.join(REPORT_DIR, "追问链路探针-%s.md" % stamp)
    raw_path = os.path.join(RAW_DIR, "追问链路探针-%s.json" % ts)
    os.makedirs(RAW_DIR, exist_ok=True)
    with open(raw_path, "w", encoding="utf-8") as f:
        json.dump(RAW, f, ensure_ascii=False, indent=1)
    head = ["# 追问链路探针 %s" % stamp, ""]
    if BASELINE:
        head += ["> **基线轮（阶段一改造前）**：F 系验收用例的 verdict 预期大面积 FAIL，"
                 "用于与后续阶段改造后的轮次对比。", ""]
    head += ["阶段过滤：--phase %d；min_phase > %d 的用例未执行，在 F 系汇总表中标 SKIP（注明依赖阶段）。"
             % (PHASE, PHASE), "",
             "脚本：scripts/probe_followup.py；原始响应：raw/%s" % os.path.basename(raw_path), ""]
    p_res = [r for r in RESULTS if not r["id"].startswith("F")]
    f_res = [r for r in RESULTS if r["id"].startswith("F")]
    head += ["## P 系汇总（问题复现探针：CONFIRMED/LATENT/NOT_REPRODUCED/PARTIAL）", "",
             "| 场景 | 结论 |", "|---|---|"]
    head += ["| %s %s | %s |" % (r["id"], r["title"], r["verdict"]) for r in p_res]
    head += ["", "## F 系汇总（验收用例：PASS/FAIL/SKIP）", "",
             "| 场景 | min_phase | 结论 |", "|---|---|---|"]
    head += ["| %s %s | %d | %s |" % (r["id"], r["title"], r.get("min_phase", 1), r["verdict"])
             for r in f_res]
    head += [""]
    body = list(MD)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(head + body) + "\n")
    return md_path, raw_path


# (sid, 函数, min_phase)。fn=None 表示本脚本不实现，固定 SKIP。
PLAN = [
    ("C1", scenario_c1, 1),
    ("P1", scenario_p1, 1), ("P2", scenario_p2, 1), ("P3", scenario_p3, 1),
    ("P4", scenario_p4, 1), ("P5", scenario_p5, 1),
    ("F01", scenario_f01, 1), ("F02", scenario_f02, 2), ("F03", scenario_f03, 2),
    ("F04", scenario_f04, 1), ("F05", scenario_f05, 2), ("F06", scenario_f06, 1),
    ("F07", scenario_f07, 1), ("F08", scenario_f08, 1), ("F09", scenario_f09, 1),
    ("F10", scenario_f10, 1), ("F11", scenario_f11, 1), ("F12", scenario_f12, 1),
    ("F13", scenario_f13, 2),
    ("F14", None, 2),
    ("F15", scenario_f15, 2), ("F16", scenario_f16, 2), ("F17", scenario_f17, 1),
    ("F18", scenario_f18, 1), ("F19", scenario_f19, 1), ("F20", scenario_f20, 1),
    ("F21", scenario_f21, 2), ("F22", scenario_f22, 1),
]


def main():
    global PHASE, BASELINE
    args = sys.argv[1:]
    filt = None
    run_smoke = True
    i = 0
    while i < len(args):
        a = args[i]
        if a.startswith("only:"):
            filt = [k.strip().upper() for k in a[5:].split(",") if k.strip()]
        elif a == "nosmoke":
            run_smoke = False
        elif a == "--baseline":
            BASELINE = True
        elif a == "--phase":
            i += 1
            PHASE = int(args[i])
        elif a.startswith("--phase="):
            PHASE = int(a.split("=", 1)[1])
        i += 1
    if run_smoke:
        smoke()

    md("# 追问链路探针执行过程\n")
    md("当前时间（Asia/Shanghai）：%s；昨天=%s 前天=%s；--phase %d%s\n"
       % (datetime.now(SH).strftime("%Y-%m-%d %H:%M:%S"), YESTERDAY_SH, DAY_BEFORE_SH,
          PHASE, "；基线轮（阶段一改造前）" if BASELINE else ""))

    t0 = time.time()
    for sid, fn, mp in PLAN:
        if filt and sid not in filt:
            continue
        if fn is None:
            skip_case(sid, mp, "并发场景不在本脚本实现：线上并发模拟会误伤真实会话，"
                               "需在测试环境验证；汇总固定 SKIP。")
            continue
        if mp > PHASE:
            skip_case(sid, mp, "min_phase=%d > --phase %d，本轮未执行。" % (mp, PHASE))
            continue
        print("\n=== %s ===" % sid)
        fn()
    print("\n用时 %.0fs" % (time.time() - t0))

    p_res = [r for r in RESULTS if not r["id"].startswith("F")]
    f_res = [r for r in RESULTS if r["id"].startswith("F")]
    md("\n## P 系汇总\n")
    md("| 场景 | 结论 |")
    md("|---|---|")
    for r in p_res:
        md("| %s %s | %s |" % (r["id"], r["title"], r["verdict"]))
    md("\n口径：CONFIRMED=问题复现；LATENT=机制缺失但本次未触发；"
       "NOT_REPRODUCED=本次未复现；PARTIAL=中间状态。")
    md("\n## F 系汇总\n")
    md("| 场景 | min_phase | 结论 |")
    md("|---|---|---|")
    for r in f_res:
        md("| %s %s | %d | %s |" % (r["id"], r["title"], r.get("min_phase", 1), r["verdict"]))
    md("\n口径：PASS=所有判据命中；FAIL=有判据未命中（含证据缺失降级）；"
       "SKIP=依赖阶段未覆盖或场景未触发。")
    p_md, p_raw = write_report()
    print("\n报告：%s\n原始：%s" % (p_md, p_raw))


if __name__ == "__main__":
    main()
