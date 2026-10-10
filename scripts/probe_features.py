#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CEXPilot 功能测试（指标化架构）

改动背景：
  - Planner 不再挑 Tool，而是声明「指标 + 查询形态」（metric / query_shape / count）。
  - evidence 不再是 tool 名清单：指标节点带 identity（metric / exchange / query_shape / source），
    计算节点带 operator。旧脚本读 ev["tool"] 恒为 None，所有基于工具名的断言都会静默失效，
    表现为「期望 X 工具，实际调用 []」的批量误报，或反过来一路 PASS。
  - 因此判定口径从「调了哪个工具」改成「有没有真的取到期望的指标 + 形态 + 期数」。

关于拒答判定：
  - 旧实现用关键词扫答案，会把「数据已给到 + 顺带说明某项暂不支持」误判成拒答。
    典型是资金费率：10 期已结算费率全部返回，答案补一句「当前未结算/预测费率不提供」，
    全局关键词扫描直接判 FAIL。
  - 现在按结构判断：期望数据到位就算作答，答案里的否定句只是补充说明，不再作为判据；
    只有一条期望数据都没取到时，才去看答案有没有把原因说清楚。
  - 本来就 contact 不到数据、属于能力缺口的用例，用 gap 话术元组断言「必须披露缺口」，
    而不是看答案里有没有「不支持」就算完。

expect 的写法：
  - 单个 spec 或 spec 组成的 tuple。tuple 内是「全部都要满足」；
    每个 spec 只要命中任意一条 evidence row 就算满足。
  - M("metric", "shape", count=N, exchange="binance")  构造一条指标期望。
  - M_ANY(spec1, spec2)  两种口径都算对（如区间涨跌幅 vs 交易所官方 24h 口径）。
  - {"operator": "difference"}  断言某个算子被执行。

expect_refuse 的取值：
  - False               期望取到数据并作答
  - True                期望不作答、不产生取数（越界请求）
  - ("词A", "词B")      期望明确说明能力缺口（命中任一即可）；不断言是否取数

用法：
    python3 probe_features.py                    # 全量
    python3 probe_features.py only:B1,C1         # 指定用例
    python3 probe_features.py group:B            # 指定分组

产物：
    回归测试/功能测试-YYYY-MM-DD.md
    回归测试/raw/功能测试-YYYY-MM-DD.json
"""

import json
import os
import re
import sys
import time
import urllib.request
import uuid
from datetime import datetime, timezone, timedelta

BASE = "https://cex.hrscrm.net"
ASK = BASE + "/api/ask"
TRACE = BASE + "/trace.html?id="
# 新接口：含两次 LLM 调用的完整 prompt 与 response，排查 planner 决策时用它
DETAIL = BASE + "/api/trace_detail/"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
# 产物继续落在 importants/回归测试/，保持与历史数据同目录
HERE = os.path.join(REPO_ROOT, "importants", "回归测试")
TIMEOUT = 180


def head_commit():
    """取当前 HEAD 短哈希，避免报告里写死旧的 commit 造成误导。"""
    try:
        import subprocess
        out = subprocess.run(["git", "-C", REPO_ROOT, "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True, timeout=10)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    except Exception:
        pass
    return "unknown"


# 只在「一条期望数据都没取到」时才去看答案说了什么：用来区分
# 「明确说明能力缺口」（可接受）与「答不上来也不说原因」（真失败）。
GAPISH = ("无法", "不支持", "暂不", "不能", "未接入", "没有", "尚不", "超出", "不提供", "无")

# planner 未产出 plan 且未给具体原因时的兜底话术，属系统故障信号。
# 用正则匹配——文案改过版，写死整句会静默失效。
FALLBACK_RE = re.compile(r"(无法可靠生成查询计划|没有能够可靠回答此问题的查询计划|本次未执行查询)")


# ---------------- 断言构造 ----------------

def M(metric, shape=None, **kw):
    """一条指标期望：M("funding.rate_settled", "recent_n", count=10, exchange="binance")"""
    spec = {"metric": metric}
    if shape:
        spec["shape"] = shape
    spec.update(kw)
    return spec


def OP(operator):
    """一条算子期望：OP("difference")"""
    return {"operator": operator}


def M_ANY(*specs):
    """多种口径都算对：M_ANY(M("price.change_pct","range_statistic"), ...)"""
    return {"__any__": list(specs)}


def normalize_expect(expect):
    if not expect:
        return []
    return list(expect) if isinstance(expect, (list, tuple)) else [expect]


def metric_rows(d):
    """把 /api/ask 的 evidence 压成统一的断言行。

    新证据形如 {"node_id","type","identity"/"operator","ok","data"}：
      - type=metric      -> identity 里有 metric / exchange / query_shape / unit / source
      - type=calculation -> operator 是算子名
    旧的 ev["tool"] 已不存在，这里不再依赖它。
    """
    rows = []
    for ev in d.get("evidence") or []:
        ident = ev.get("identity") or {}
        data = ev.get("data") or {}
        ok = ev.get("ok")
        if ident or ev.get("type") == "metric":
            rows.append({
                "kind": "metric",
                "metric": ident.get("metric") or data.get("metric"),
                "exchange": ident.get("exchange") or data.get("exchange"),
                "shape": ident.get("query_shape") or data.get("query_shape"),
                "unit": ident.get("unit") or data.get("unit"),
                "operator": None,
                "count": data.get("requested_count"),
                "ok": ok,
                "data": data,
            })
        else:
            rows.append({
                "kind": "calc",
                "metric": None,
                "exchange": None,
                "shape": None,
                "unit": None,
                "operator": ev.get("operator"),
                "count": None,
                "ok": ok,
                "data": data,
            })
    return rows


def row_label(r):
    if r["kind"] == "calc":
        return "op:%s%s" % (r["operator"] or "?", "" if r["ok"] else "(fail)")
    s = "%s@%s" % (r["metric"] or "?", r["shape"] or "?")
    if r["exchange"]:
        s += ":%s" % r["exchange"]
    if r["count"]:
        s += "×%s" % r["count"]
    if not r["ok"]:
        s += "(fail)"
    return s


def has_value(r):
    """这条证据有没有真的产出可用数值：标量看 value，序列/期数看 samples。"""
    if not r["ok"]:
        return False
    data = r["data"] or {}
    if isinstance(data.get("value"), (int, float)):
        return True
    samples = data.get("samples")
    return isinstance(samples, list) and len(samples) > 0


def spec_ok(row, spec):
    """spec 里给出的键全部相等才算命中；列表表示任一即可。"""
    if "__any__" in spec:
        return any(spec_ok(row, s) for s in spec["__any__"])
    for key, want in spec.items():
        got = row.get(key)
        if key == "count":
            if got is None or int(got) != int(want):
                return False
        elif isinstance(want, (list, tuple, set)):
            if got not in want:
                return False
        elif got != want:
            return False
    return True


def gap_words(ans):
    return [k for k in GAPISH if k in ans]


def plan_diag(tid):
    """失败用例根因：从 /api/trace_detail 读 PLAN / PLAN_COMPILED。看 pipeline 比读答案快。"""
    try:
        d = json.loads(urllib.request.urlopen(DETAIL + str(tid), timeout=30).read())
    except Exception as e:
        return "trace_detail 读取失败: %s" % e
    notes = []
    for e in d.get("events") or []:
        if e.get("event_type") in ("PLAN", "PLAN_COMPILED"):
            if e.get("error"):
                notes.append("%s error=%s" % (e["event_type"], str(e["error"])[:120]))
            else:
                out = e.get("output_json")
                if out:
                    notes.append("%s=%s" % (e["event_type"], str(out)[:120]))
    return " | ".join(notes[:3]) or "无 PLAN 事件（可能未进入规划）"


def ask(q, cid=None, retries=2):
    t0 = time.time()
    last = None
    for i in range(retries + 1):
        try:
            req = urllib.request.Request(
                ASK,
                data=json.dumps({"conversationId": cid or str(uuid.uuid4()), "question": q}).encode(),
                headers={"Content-Type": "application/json"},
            )
            d = json.loads(urllib.request.urlopen(req, timeout=TIMEOUT).read())
            d["_wall"] = round(time.time() - t0, 1)
            d["_q"] = q
            return d
        except Exception as e:
            last = e
            time.sleep(3 * (i + 1))
    return {"_error": str(last), "_wall": round(time.time() - t0, 1), "_q": q}


def trim(o):
    out = {}
    for k, v in (o or {}).items():
        if isinstance(v, list) and len(v) > 8:
            out[k] = "[%d 条] 首=%s 尾=%s" % (len(v), str(v[0])[:60], str(v[-1])[:60])
        else:
            out[k] = v
    return out


def judge(tag, q, expect, refuse, d):
    """返回 (verdict, 说明列表)。verdict ∈ PASS / FAIL / INFO"""
    if d.get("_error"):
        return "FAIL", ["请求失败: %s" % d["_error"][:60]]
    ans = (d.get("answer") or "").replace("\n", " ")
    # planner 兜底话术 = plan 没产出且没说清原因。这是系统故障不是能力边界，直接判 FAIL：
    # B5 资金费率就败在这——plan 因 recent_n 缺 count 解析失败，答案却写成"不支持"。
    if FALLBACK_RE.search(ans):
        return "FAIL", ["命中 planner 兜底话术（未产出 plan 且未给原因，属系统故障）"]
    rows = metric_rows(d)
    notes = []

    clauses = normalize_expect(expect)
    # 无断言的用例（G/H 组的"看它怎么做"型）标记为 INFO，避免一律判 PASS 造成虚绿
    verdict = "PASS" if (clauses or refuse is not None) else "INFO"

    # ---- 1) 结构化：期望的取数是不是真的到位 ----
    missing, empty = [], []
    for spec in clauses:
        hit = [r for r in rows if spec_ok(r, spec)]
        if not hit:
            missing.append(spec)
        elif not any(has_value(r) for r in hit):
            empty.append(spec)
    if missing:
        verdict = "FAIL"
        notes.append("未取到期望的 %s；实际 %s"
                     % (describe_expect(missing), describe_rows(rows)))
    if empty:
        verdict = "FAIL"
        notes.append("已规划但没产出可用数值: %s" % describe_expect(empty))

    # ---- 2) 拒答 / 缺口：只有数据没到位时才看答案措辞 ----
    delivered = clauses and not missing and not empty
    if refuse is True:
        if rows and any(r["ok"] for r in rows):
            notes.append("期望不作答，但产生了取数: %s" % describe_rows(rows))
            verdict = "FAIL"
    elif isinstance(refuse, (list, tuple)):
        hit = [p for p in refuse if p in ans]
        if not hit:
            notes.append("应明确说明能力缺口（命中任一: %s）" % "/".join(refuse[:6]))
            verdict = "FAIL"
    elif refuse is False and clauses:
        if delivered:
            # 数据到位即可；答案里补充说明某项不提供属于正常披露，不算拒答
            pass
        else:
            gap = gap_words(ans)
            if gap:
                notes.append("期望作答但未取到所需数据，答案措辞: %s" % "、".join(gap[:4]))
            else:
                notes.append("期望作答：既未取到 %s，答案也未说明原因" % describe_expect(clauses))
            verdict = "FAIL"

    return verdict, notes


def describe_expect(specs):
    labels = []
    for s in specs:
        if "__any__" in s:
            labels.append("(" + " 或 ".join(short_spec(x) for x in s["__any__"]) + ")")
        else:
            labels.append(short_spec(s))
    return " + ".join(labels) or "-"


def short_spec(s):
    if "operator" in s:
        return "op:%s" % s["operator"]
    metric = s.get("metric") or "?"
    if isinstance(metric, (list, tuple)):
        metric = "/".join(metric)
    label = "%s@%s" % (metric, s.get("shape") or "任意形态")
    if s.get("exchange"):
        label += ":%s" % s["exchange"]
    if s.get("count"):
        label += "×%s" % s["count"]
    return label


def describe_rows(rows):
    return ", ".join(row_label(r) for r in rows) or "无证据"


# 资金费率：目录只有「已结算」口径，当前未结算/预测费率两所口径不同不接入，
# 必须披露这个边界，不能用已结算费率冒充"当前费率"。
FUNDING_GAP = ("未结算", "已结算", "结算费率", "预测", "不提供")
# 逐笔成交明细未迁移：应该说明能力缺口，而不是编数据。
TRADE_DETAIL_GAP = ("成交明细", "逐笔", "未接入", "暂不", "不支持", "不提供")
# 历史费率的区间统计与累计值不支持（变周期口径未核清）。
FUNDING_RANGE_GAP = ("累计", "区间", "历史费率", "暂不", "不支持", "不能")


# (tag, question, expect, expect_refuse, 备注)
# expect = None 表示不校验取数；expect_refuse 见文件头说明
CASES = [
    # ---------- A 组：当前快照基线，确认基本功能没被重构打破 ----------
    ("A1 快照-当前价", "币安上的 BTC 现在多少钱？",
     M("price.last", "snapshot"), False, "快照基线"),
    ("A2 快照-资金费率", "当前 BTC 的资金费率是多少？下次什么时候结算？",
     M("funding.rate_settled", "recent_n"), FUNDING_GAP,
     "只有已结算口径；当前未结算/预测费率必须说明不提供"),
    ("A3 快照-盘口", "现在盘口买卖盘不平衡度是多少？上方压单重不重？",
     M("orderbook.imbalance", "snapshot"), False,
     "不平衡度可答；挂单量未接入，压单重量答不出属正常"),
    ("A4 快照-持仓量", "BTC 当前持仓量是多少？",
     M("oi.quantity", "snapshot"), False, "快照"),
    ("A5 快照-标记价", "标记价和现货指数价的基差现在是多少？",
     (M("mark.price", "snapshot"), M("index.price", "snapshot")), False,
     "基差 = mark - index，需要两个快照指标"),
    ("A6 近期成交样本", "最近 20 笔成交里，主动买入的多还是卖出的多？",
     None, TRADE_DETAIL_GAP, "逐笔明细未迁移，应说明能力缺口"),

    # ---------- B 组：滚动窗口 ----------
    ("B1 滚动-过去6小时涨跌", "币安 BTC-USDT 过去 6 小时的涨跌幅是多少？",
     M("price.change_pct", "range_statistic"), False, "rolling_window；终点非整点"),
    # 24h 滚动：交易所官方口径 time-independent，两种解法都算对
    ("B2 滚动-过去24小时涨跌", "币安 BTC-USDT 过去 24 小时的涨跌幅是多少？",
     M_ANY(M("price.change_pct", "range_statistic"),
           M("price.change_pct_24h", "official_24h")), False, "rolling_window"),
    ("B3 滚动-过去1小时涨跌", "币安 BTC-USDT 最近 1 小时的涨跌幅是多少？",
     M("price.change_pct", "range_statistic"), False, "rolling_window"),
    ("B4 滚动-过去6小时K线", "币安 BTC-USDT 过去 6 小时的 5 分钟 K 线有哪些？",
     M(["price.open", "price.close", "price.high", "price.low"], "time_series"), False, "历史序列"),

    # ---------- C 组：自然周期 ----------
    ("C1 自然日-昨天涨跌", "币安 BTC-USDT 昨天一天的涨跌幅是多少？",
     M("price.change_pct", "range_statistic"), False, "calendar_period day -1"),
    ("C2 自然日-昨天高低价", "昨天 BTC-USDT 的最高价和最低价分别是多少？",
     (M("price.high", "range_statistic"), M("price.low", "range_statistic")), False,
     "calendar_period day -1"),
    ("C3 自然周-上周表现", "币安 BTC-USDT 上周整体是涨还是跌？",
     M("price.change_pct", "range_statistic"), False, "calendar_period week -1"),
    # 本月至今在细粒度下会超 K 线预算 —— 这是设计限制而非缺陷，
    # 工具需明确说明原因并给出替代口径，故按 INFO 观察。
    ("C4 本月至今", "币安 BTC-USDT 这个月到现在的涨跌幅是多少？",
     None, None, "extent=to_request_time；可能超 K 线预算，看是否给出明确限制与替代建议"),
    ("C5 昨天下午", "昨天下午 BTC-USDT 的走势怎么样？",
     M_ANY(M("price.change_pct", "range_statistic"),
           M(["price.open", "price.close"], "time_series")), False, "segment=afternoon"),

    # ---------- D 组：相对日期时段 / 绝对日期范围 ----------
    ("D1 相对-昨天15点到17点", "昨天下午 3 点到 5 点，BTC-USDT 涨了多少？",
     M("price.change_pct", "range_statistic"), False, "relative_day_range"),
    ("D2 绝对-9月1日到3日", "9 月 1 日到 9 月 3 日，BTC-USDT 的涨跌幅是多少？",
     M("price.change_pct", "range_statistic"), False, "absolute_range"),

    # ---------- E 组：区间统计 / 序列 / 最近 N 期 ----------
    # 资金费率的标准解法：已结算费率 + recent_n + count=10。
    # 判定看「有没有按这个口径取到 10 期」，而不是答案里有没有出现"不支持"。
    ("E1 费率-最近10期", "BTC-USDT 最近 10 期的资金费率分别是多少？",
     M("funding.rate_settled", "recent_n", count=10), False, "最近 N 期；count=10"),
    ("E2 费率-过去一周累计/均值", "过去一周 BTC-USDT 的资金费率均值是多少？正负各多少期？",
     None, FUNDING_RANGE_GAP, "历史费率区间序列与累计值不支持，应说明而非编造"),
    ("E3 持仓量-昨天序列", "昨天 BTC-USDT 的持仓量是怎么变化的？",
     M("oi.quantity", "time_series"), False, "历史序列"),
    ("E4 持仓量-昨天变化率", "昨天一天 BTC-USDT 的持仓量变化了多少？",
     M("oi.change_pct", "range_statistic"), False, "区间统计"),
    ("E5 成交流-昨天主动买卖占比", "昨天 BTC-USDT 的主动买入量和主动卖出量占比各是多少？",
     M("taker.buy_ratio", "range_statistic"), False, "区间统计：taker 流"),
    # 单交易所单边占比：数据量约为 E5 的一半，用来判断 E5 失败是预算问题还是口径问题
    ("E7 成交流-币安昨天买方占比", "昨天币安 BTC-USDT 主动买入量占比多少？",
     M("taker.buy_ratio", "range_statistic"), False, "区间统计：单交易所单边占比"),
    ("E6 标记价-昨天区间", "昨天 BTC-USDT 的标记价涨跌幅是多少？",
     M("mark.change_pct", "range_statistic"), False, "区间统计"),

    # ---------- F 组：跨所比较：两所都要取到，缺比较算子时需说明 ----------
    ("F1 跨所-1小时", "对比币安和 OKX 上 BTC-USDT 最近 1 小时的走势，哪个涨得更多？",
     (M("price.change_pct", "range_statistic", exchange="binance"),
      M("price.change_pct", "range_statistic", exchange="okx")), False,
     "同区间跨所：两所都要有指标"),
    ("F2 跨所-24小时", "对比币安和 OKX 上 BTC-USDT 最近 24 小时的涨跌幅？",
     (M(["price.change_pct", "price.change_pct_24h"], exchange="binance"),
      M(["price.change_pct", "price.change_pct_24h"], exchange="okx")), False,
     "同区间跨所：区间口径或官方 24h 口径均可"),

    # ---------- G 组：边界与限制（应自行拒绝或说明，不得给错数据） ----------
    ("G1 限制-不支持计价币", "拿币安上面的 BTC-USDC 上周的总交易额，跟上上周做对比。",
     None, None, "quote_asset=USDC 应被拒绝"),
    ("G2 限制-不支持粒度", "币安 BTC-USDT 过去 2 小时的 1 分钟 K 线是多少？",
     None, None, "interval 仅 5m/15m/1h"),
    ("G3 限制-币安持仓量超期", "币安 BTC-USDT 两个月前的持仓量是多少？",
     None, None, "币安仅保留 30 天"),
    ("G4 限制-直达未来区间", "今天一整天 BTC-USDT 的涨跌幅是多少？",
     None, None, "区间未走完，应说明实际覆盖范围且不给全天数据"),
    ("G5 链上-交易分析",
     "帮我分析这笔以太坊交易：0x321674b0c3ade0814ebee8477505314b8ea3c606d7e87b8a7348c02fc262e108",
     None, None, "链上查询不在指标目录内，不断言作答"),
    ("G6 越界-非web3", "介绍一下亚历山大大帝", None, True, "领域外应拒答"),

    # ---------- H 组：历史上被拒的 case，回归验证 ----------
    ("H1 回归-今日零点至今成交额", "币安 BTC-USDT 今天从零点到现在的总成交额是多少？",
     M("trade.turnover", "range_statistic"), False, "曾被 Guard 拒；现应可答或明确限制"),
    ("H2 回归-过去6小时涨跌幅", "币安 BTC-USDT 过去 6 小时的涨跌幅是多少？",
     M("price.change_pct", "range_statistic"), False, "曾被 Guard 误杀"),
    ("H3 回归-费率累计24h", "OKX 上 BTC-USDT 过去 24 小时的资金费率累计是多少？",
     None, FUNDING_RANGE_GAP, "费率累计不支持，应说明而非用价格涨跌幅冒充"),
    ("H4 回归-USDT上周vs上上周", "币安 BTC-USDT 上周的总成交额跟上上周相比变化多少？",
     M("trade.turnover", "range_statistic"), False, "周期对比，关注是否给错数据"),
]


def render(results, when):
    L = []
    L.append("# CEXPilot 功能测试 —— %s\n" % when)
    L.append("> 针对指标化架构（Planner 声明指标+形态，evidence 走 identity/operator）的功能测试。")
    L.append("> 判定口径：有没有真的取到期望的指标+形态+期数；能不能答的答了、该说明的缺口说明了。")
    L.append("> 答案里的否定句不再单独判 FAIL——只要期望数据到位，补充性的能力披露是可接受的。\n")
    L.append("- 线上：%s ｜ 分支 HEAD 对应 commit `%s`" % (BASE, head_commit()))
    L.append("- 执行：%s（北京时间）\n" % when)

    npass = sum(1 for r in results if r[6] == "PASS")
    L.append("**总计 %d 条：PASS %d ／ FAIL %d ／ INFO %d**\n"
             % (len(results), npass,
                sum(1 for r in results if r[6] == "FAIL"),
                sum(1 for r in results if r[6] == "INFO")))

    L.append("## 总览\n")
    L.append("| 用例 | 期望 | 实际取数 | 耗时 | 判定 |")
    L.append("| --- | --- | --- | --- | --- |")
    for tag, q, ex, er, memo, d, verdict, notes, _ in results:
        rows = metric_rows(d)
        L.append("| %s | %s | %s | %ss | %s |" % (
            tag, memo, describe_rows(rows)[:60], d.get("_wall") or "-",
            "✅" if verdict == "PASS" else ("❌" if verdict == "FAIL" else "ℹ️")))
    L.append("")

    L.append("---\n")
    for tag, q, ex, er, memo, d, verdict, notes, _ in results:
        L.append("## %s\n" % tag)
        L.append("**Q：** %s\n" % q)
        L.append("- 备注：%s ｜ 判定：**%s**" % (memo, verdict))
        if d.get("_error"):
            L.append("- 请求失败：%s\n" % d["_error"])
            continue
        L.append("- 服务端耗时：%s ms ｜ 端到端：%s s ｜ toolCalls=%s ｜ 期望：%s"
                 % (d.get("durationMs"), d.get("_wall"), d.get("toolCalls"),
                    describe_expect(normalize_expect(ex))))
        if d.get("traceId"):
            L.append("- trace：%s%s ｜ 详情：%s%s" % (TRACE, d["traceId"], DETAIL, d["traceId"]))
        if notes:
            L.append("- ⚠️ %s" % "；".join(notes))
        if verdict == "FAIL" and d.get("traceId"):
            L.append("- 根因：%s" % plan_diag(d["traceId"]))
        if isinstance(er, (list, tuple)):
            L.append("- 缺口话术要求：%s" % "/".join(er[:8]))
        rows = metric_rows(d)
        if rows:
            L.append("\n**取数结果（节选）：**\n")
            L.append("```json")
            for r in rows:
                L.append("// %s ok=%s" % (row_label(r), r["ok"]))
                L.append(json.dumps(trim(r["data"]), ensure_ascii=False, indent=2)[:1600])
            L.append("```\n")
        else:
            L.append("\n**取数结果：** 无\n")
        ans = d.get("answer") or ""
        L.append("**answer：**\n")
        L.append("```text")
        L.append(ans.strip()[:2200])
        L.append("```\n")
        gap = gap_words(ans)
        if gap:
            L.append("> 答案含否定/缺口措辞（仅作提示，不作为判据）：%s\n" % "、".join(gap[:6]))
        L.append("---\n")
    return "\n".join(L)


def main():
    filt = None
    grp = None
    for a in sys.argv[1:]:
        if a.startswith("only:"):
            filt = a[5:]
        elif a.startswith("group:"):
            grp = a[6:]

    cases = CASES
    if grp:
        cases = [c for c in CASES if re.match(r"^%s\d" % grp.strip().upper(), c[0])]
    if filt:
        keys = [k.strip() for k in filt.split(",")]
        cases = [c for c in cases if any(k.lower() in c[0].lower() for k in keys)]
    if not cases:
        print("没有匹配的用例")
        return

    print("### 功能测试：%d 条用例（串行）" % len(cases))
    results = []
    for tag, q, ex, er, memo in cases:
        d = ask(q)
        verdict, notes = judge(tag, q, ex, er, d)
        results.append((tag, q, ex, er, memo, d, verdict, notes, None))
        ans = (d.get("answer") or "").replace("\n", " ")
        print("[%-24s] %s | %ss | %s | %s" % (
            tag, verdict, d.get("_wall"), describe_rows(metric_rows(d))[:40], ans[:110]))
        if notes:
            print("      ⚠️ %s" % "；".join(notes)[:200])
        sys.stdout.flush()
        time.sleep(0.8)

    now = datetime.now(timezone(timedelta(hours=8)))
    when = now.strftime("%Y-%m-%d %H:%M")
    stamp = now.strftime("%Y-%m-%d")

    md = render(results, when)
    md_path = os.path.join(HERE, "功能测试-%s.md" % stamp)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(md)

    raw = [{
        "tag": t, "question": q, "memo": memo, "verdict": v, "notes": n,
        "expect": normalize_expect(ex), "expect_refuse": er if not isinstance(er, tuple) else list(er),
        "toolCalls": d.get("toolCalls"), "durationMs": d.get("durationMs"),
        "wallSec": d.get("_wall"), "traceId": d.get("traceId"),
        "traceUrl": (TRACE + str(d.get("traceId"))) if d.get("traceId") else None,
        "traceDetailUrl": (DETAIL + str(d.get("traceId"))) if d.get("traceId") else None,
        "evidence": d.get("evidence"), "answer": d.get("answer"), "error": d.get("_error"),
    } for t, q, ex, er, memo, d, v, n, _ in results]
    raw_dir = os.path.join(HERE, "raw")
    os.makedirs(raw_dir, exist_ok=True)
    raw_path = os.path.join(raw_dir, "功能测试-%s.json" % stamp)
    with open(raw_path, "w", encoding="utf-8") as f:
        json.dump(raw, f, ensure_ascii=False, indent=2)

    npass = sum(1 for r in results if r[6] == "PASS")
    print("\nPASS %d / %d" % (npass, len(results)))
    print("报告: %s" % md_path)
    print("原始: %s" % raw_path)


if __name__ == "__main__":
    main()
