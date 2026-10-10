#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CEXPilot 追问链路探针（多轮上下文继承）

目的：用端到端真实问答，确认「只把最近 5 组问答（助手答案截前 300 字）塞进 prompt，
      其余全靠模型自己还原」这套追问实现，会在哪些连续问法上出问题。

线上实现要点（写代码前已从源码确认，探针的判据都建立在这些点上）：
  1. ConversationService：HISTORY_QUERY_LIMIT=5、HISTORY_ANSWER_MAX_CHARS=300，
     渲染成「用户：xxx\n助手：yyy」纯文本，不带计划/工具参数/工具结果/发生时间。
  2. 该文本同时注入 dag_planner.txt 的 <CONVERSATION_CONTEXT> 与 agent_system.txt。
  3. DagExecutor 每次 execute 都 new DagContext()，没有跨轮结果可引用。
  4. 因此「本轮到底继承了哪些条件」不能看答案文字，要看 evidence：
     identity.metric / identity.exchange / identity.instrument.base /
     data.requested_range（起止 + timezone）/ data.samples / data.candle_count。
     本脚本所有条件判定都走 evidence，答案文本只作为辅助观察。

覆盖的场景（对应用户列出的 5 个问题；第 6 个跨天场景需要改系统时间，不自动化）：
  C1 会话复用：不传 conversationId 时到底新建还是复用（接口注释与实现是否一致）
  P1 上下文淘汰：首轮条件插 5 轮后掉出窗口
  P2 旧快照不可引用：「比刚才涨了多少」
  P3 样本未锁定：「把刚才那 10 期求均值 / 最小值」
  P4 连续改条件：换币 → 只看一所 → 改日期 → 加指标
  P5 / P5b 长答案截断：答案尾部（实际覆盖区间）在下一轮前被裁掉。
        P5 用自然问法；P5b 要求逐根列出，把关键信息逼到答案尾部（模型是否把它写在开头不受我们控制）

用法：
    python3 probe_followup.py                # 全量（约 24 次真实问答，数分钟）
    python3 probe_followup.py only:P3        # 只跑指定场景（逗号分隔，如 only:P1,P5）
    python3 probe_followup.py nosmoke        # 跳过冒烟

产物：
    importants/回归测试/追问链路探针-<日期>.md   （人读的结论）
    importants/回归测试/raw/追问链路探针-<日期>.json（每轮原始响应 + 注入上下文，复核用）

判定口径（重要）：
  CONFIRMED        = 问题复现（条件/样本/信息确实丢了，或用了不该用的来源）
  LATENT           = 机制缺失但本次未触发（例：期间没发生新结算，样本没漂移）
  NOT_REPRODUCED   = 本次没复现（模型猜对了/答案被压缩了），如实记录
  PARTIAL          = 中间状态（如条件已掉出上下文，但结果仍然正确）
本脚本不是回归门禁：跑出来的目的是看清现状，不是为了判 PASS/FAIL。
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
    try:
        with urllib.request.urlopen(DETAIL + tid, timeout=60) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"events": [], "error": repr(e)[:120]}


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
    t = {"q": q, "d": d, "tz_sent": timezone, "rows": rows(d) if d.get("ok") else []}
    if d.get("ok") and want_ctx:
        t["ctx"] = planner_ctx(d.get("traceId")) or ""
        t["pairs"] = ctx_pairs(t["ctx"])
    RAW.append({"tag": tag, "question": q, "conversationId": conv,
                "timezone": timezone, "response": d,
                "injected_context": t.get("ctx")})
    print("   %s | %s | %.1fs | %s" % (tag, q[:34], d.get("wall", 0), brief(t)))
    time.sleep(1.2)
    return t


def finish(sid, title, expect_lines, check_lines, verdict, note_lines):
    """check_lines: [(判据, 是否命中, 说明)]"""
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
                    "checks": [{"name": n, "hit": bool(h), "desc": d}
                               for n, h, d in check_lines],
                    "notes": note_lines})
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
        ("注入上下文只含 5 组问答", len(pairs) == 5, "实际 %d 组（首轮应已被淘汰）" % len(pairs)),
        ("首轮条件已掉出上下文", not still,
         "上下文中同时出现 BTC+成交额+昨天：%s" % ("是" if still else "否")),
        ("追问继承：交易所=OKX", c_ex, "evidence exchanges=%s" % ex),
        ("追问继承：币种=BTC", c_bs, "evidence base=%s" % bs),
        ("追问继承：指标=成交额", c_mt, "evidence metric=%s" % mt),
        ("追问继承：日期与首轮一致", c_dt, "evidence 日期=%s，首轮=%s" % (dt, b_dt)),
        ("追问继承：时区与首轮一致", c_tz, "evidence timezone=%s，首轮=%s" % (tz, b_tz)),
    ]
    if not still and inherited < 5:
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
    if truncated and cover_lost and not cover_in_ctx:
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


# ---------------- 汇总与产出 ----------------

def write_report():
    stamp = datetime.now(SH).strftime("%Y-%m-%d")
    ts = datetime.now(SH).strftime("%Y%m%d-%H%M")
    md_path = os.path.join(REPORT_DIR, "追问链路探针-%s.md" % stamp)
    raw_path = os.path.join(RAW_DIR, "追问链路探针-%s.json" % ts)
    os.makedirs(RAW_DIR, exist_ok=True)
    with open(raw_path, "w", encoding="utf-8") as f:
        json.dump(RAW, f, ensure_ascii=False, indent=1)
    head = ["# 追问链路探针 %s" % stamp, "",
            "脚本：scripts/probe_followup.py；原始响应：raw/%s" % os.path.basename(raw_path), "",
            "| 场景 | 结论 |", "|---|---|"]
    body = list(MD)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(head + ["| %s %s | %s |" % (r["id"], r["title"], r["verdict"])
                                  for r in RESULTS] + [""] + body) + "\n")
    return md_path, raw_path


def main():
    args = sys.argv[1:]
    filt = None
    run_smoke = True
    for a in args:
        if a.startswith("only:"):
            filt = [k.strip().upper() for k in a[5:].split(",") if k.strip()]
        elif a == "nosmoke":
            run_smoke = False
    if run_smoke:
        smoke()

    md("# 追问链路探针执行过程\n")
    md("当前时间（Asia/Shanghai）：%s；昨天=%s 前天=%s\n"
       % (datetime.now(SH).strftime("%Y-%m-%d %H:%M:%S"), YESTERDAY_SH, DAY_BEFORE_SH))

    plan = [("C1", scenario_c1), ("P1", scenario_p1), ("P2", scenario_p2),
            ("P3", scenario_p3), ("P4", scenario_p4), ("P5", scenario_p5)]
    t0 = time.time()
    for sid, fn in plan:
        if filt and sid not in filt:
            continue
        print("\n=== %s ===" % sid)
        fn()
    print("\n用时 %.0fs" % (time.time() - t0))
    md("\n## 汇总\n")
    md("| 场景 | 结论 |")
    md("|---|---|")
    for r in RESULTS:
        md("| %s %s | %s |" % (r["id"], r["title"], r["verdict"]))
    md("\n口径：CONFIRMED=问题复现；LATENT=机制缺失但本次未触发；"
       "NOT_REPRODUCED=本次未复现；PARTIAL=中间状态。")
    p_md, p_raw = write_report()
    print("\n报告：%s\n原始：%s" % (p_md, p_raw))


if __name__ == "__main__":
    main()
