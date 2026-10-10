#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
时间窗口能力探针 —— 用于记录「用户问了工具取不到的时间范围」时，系统的表现。

背景：
  QueryCapabilityGuard 上线前后各跑一次，用来对比：
    - 上线前：工具取错范围的数据，answer 指鹿为马（最危险）
    - 上线后：明确拒绝，answer 说明取不到

用法：
    python3 probe_timewindow.py              # 跑内置用例
    python3 probe_timewindow.py -w 2         # 并发（不推荐，线上并发能力弱）
    python3 probe_timewindow.py only:T1,T3   # 只跑指定用例

产物：
    回归测试/时间窗口探针-YYYY-MM-DD.md      人读报告
    回归测试/raw/时间窗口探针-YYYY-MM-DD.json 原始响应（供写文章时引用原话）
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
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
# 产物继续落在 importants/回归测试/，保持与历史数据同目录
HERE = os.path.join(REPO_ROOT, "importants", "回归测试")
TIMEOUT = 180

# 判定用的关键词：命中即认为系统「明确拒绝了」
REFUSE_KEYS = (
    "无法", "不支持", "不能", "暂不", "没有", "未提供", "无法提供", "无法查询",
    "超出", "不在", "仅支持", "只支持", "只能", "无法获取", "无法计算", "无法得出",
    "能力范围", "当前版本", "尚未", "抱歉",
)

# 预期：这些 query 都应该被挡住（时间窗口未建设）
CASES = [
    ("T1 上周vs上上周成交额",
     "拿币安上面的 BTC-USDC 上周的总交易额，跟上上周做对比。", True),
    ("T2 今日零点到现在的成交额",
     "币安 BTC-USDT 今天从零点到现在的总成交额是多少？", True),
    ("T3 过去6小时涨跌幅",
     "币安 BTC-USDT 过去 6 小时的涨跌幅是多少？", True),
    ("T4 过去24h资金费率累计",
     "OKX 上 BTC-USDT 过去 24 小时的资金费率累计是多少？", True),
    ("T5 两所1小时走势对比",
     "对比币安和 OKX 上 BTC-USDT 最近 1 小时的走势，哪个交易所涨得更多？", False),
    # ---- 对照组：这些是明确在支持范围内的窗口，用来检验 Guard 有没有误杀 ----
    ("C1 对照-24h涨跌幅(应作答)",
     "币安 BTC-USDT 最近 24 小时的涨跌幅是多少？", False),
    ("C2 对照-4h涨跌幅(应作答)",
     "币安 BTC-USDT 过去 4 小时的涨跌幅是多少？", False),
    ("C3 对照-1h单所(应作答)",
     "币安 BTC-USDT 最近 1 小时的涨跌幅是多少？", False),
    ("C4 对照-两所24h对比(应作答)",
     "对比币安和 OKX 上 BTC-USDT 最近 24 小时的走势，哪个涨得更多？", False),
    ("C5 对照-USDT上周vs上上周(纯时间窗口)",
     "币安 BTC-USDT 上周的总成交额，跟上上周相比变化多少？", True),
    # ---- 基准组：完全不含时间窗口，用来确认 Guard 有没有波及基本功能 ----
    ("B1 基准-当前价(应作答)", "币安上的 BTC 现在多少钱？", False),
    ("B2 基准-资金费率(应作答)", "当前 BTC 的资金费率是多少？", False),
    ("B3 基准-盘口(应作答)", "现在盘口买卖盘不平衡度是多少？", False),
    ("B4 基准-持仓量(应作答)", "BTC 的持仓量是多少？", False),
]


def ask(q, cid=None, retries=2):
    """带重试。线上偶发 SSL EOF / 连接重置，不重试会在归档里留下空洞。"""
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


def trim(o, keep=("units",)):
    """压缩 evidence，方便打印。"""
    out = {}
    for k, v in (o or {}).items():
        if isinstance(v, list) and len(v) > 6:
            out[k] = "[%d 条] 首=%s 尾=%s" % (len(v), v[0], v[-1])
        else:
            out[k] = v
    return out


def looks_refused(ans):
    return [k for k in REFUSE_KEYS if k in ans]


# 每次跑完人工更新这一段；重跑脚本会带着最新结论一起输出。
ANALYSIS = """## 执行摘要（2026-09-23 首轮）

QueryCapabilityGuard 上线后首轮实测，**14 条里 9 条不符预期**。分三个层级：

### 1. 目标场景成立 ✅（T1 / T2 / T3 / T4 / C5）

用户问了工具取不到的时间范围，系统明确拒绝，且**没有拿默认窗口的数据冒充答案**——
这是 Guard 的设计目的，达成了。拒绝话术还带一句「不能改用默认窗口 / 不能用最近若干条记录代替」，
把"为什么不给个近似值"也说明了，这点比单纯说"不支持"好。

### 2. Guard 误杀：支持范围内的窗口也被拒 ⚠️（T5 / C1 / C2 / C3 / C4）

`get_klines` / `compare_exchanges` 明确支持 1h / 4h / 24h，但实测全被拒：
planner 正确提取出 `duration=1h / 4h / 24h`、`time_scope=rolling_window`，
Guard 仍然判 CAPABILITY_REFUSED。**怀疑白名单未生效，规则退化成「duration 非 null 即拒绝」。**

### 3. 更严重：完全不含时间窗口的查询也被拒 🚨（B1 / B2 / B3 / B4）

「币安上的 BTC 现在多少钱？」「当前 BTC 的资金费率是多少？」「盘口不平衡度」「持仓量」
——全部返回「当前没有能够可靠回答此问题的查询计划，暂时无法处理。」（`tools=0`，复测两轮稳定）。
这几条**不含任何时间范围**，与 Guard 的目标场景无关，属于连带故障。
根因方向：`nodes` 为 null 时走了空计划兜底；需要确认是 planner 被新 prompt 引导成不出计划，
还是 Guard 前置把请求挡了。**上线前建议优先定位这一条。**

### 4. 附带发现：planner 的 reply 被丢弃（与既有 P0-1 同源）

T1 / C2 / C5 的 planner 明明给出了更好的拒绝话术，例如：

> 当前版本不支持查询指定时间窗口（如过去 4 小时）的涨跌幅，仅支持 ticker 自带的滚动 24 小时数据。

但最终 answer 用的是 Guard 的通用模板，planner 的 reply 被丢掉了。
而且 T1 的模板理由**打偏了**——用户问的是时间窗口，模板回的是
「仅支持 USDT 本位永续合约」（因为命中了 BTC-USDC 这个现货交易对），
用户会误以为换成 USDT 就能答，实际仍然不行。

---

**写文章可用**：第 1 组是「不支持的时候是什么样子」的正面素材（有 trace 可回放）。
第 2、3 组建议修完再补一轮，作为「误杀是怎么被发现并收紧的」的过程素材。"""


def run(cases, workers=1):
    results = []
    for tag, q, expect_refuse in cases:
        d = ask(q)
        results.append((tag, q, expect_refuse, d))
        time.sleep(1.0)
    return results


def render(results, when):
    L = []
    L.append("# 时间窗口能力探针 —— %s\n" % when)
    L.append("> 用途：记录「用户问了工具取不到的时间范围」时系统的表现。")
    L.append("> 这些结果将作为「不支持的时候是什么样子」的素材，用于引出时间窗口设计那篇文章。\n")
    L.append("- 线上地址：%s" % BASE)
    L.append("- 执行时间：%s（北京时间）" % when)
    L.append("- 接口：`POST /api/ask`，判定关键词：%s\n" % "、".join(REFUSE_KEYS[:8]))
    L.append("---\n")
    L.append(ANALYSIS)
    L.append("\n---\n")

    L.append("## 总览\n")
    L.append("| 用例 | 期望 | 实际 | 工具调用 | 服务端耗时 | 结论 |")
    L.append("| --- | --- | --- | --- | --- | --- |")
    for tag, q, expect, d in results:
        if d.get("_error"):
            L.append("| %s | %s | 请求失败 | - | - | %s |" % (tag, "拒绝" if expect else "正常", d["_error"][:30]))
            continue
        ans = (d.get("answer") or "").replace("\n", " ")
        hit = looks_refused(ans)
        actual = "拒绝" if hit else "作答"
        ok = (actual == "拒绝") if expect else (actual == "作答")
        L.append("| %s | %s | %s | %s | %s ms | %s |" % (
            tag, "拒绝" if expect else "作答", actual,
            d.get("toolCalls"), d.get("durationMs") or "-",
            "✅ 符合预期" if ok else "⚠️ 与预期不符"))
    L.append("")

    L.append("---\n")
    for tag, q, expect, d in results:
        L.append("## %s\n" % tag)
        L.append("**Q：** %s\n" % q)
        if d.get("_error"):
            L.append("**请求失败：** %s\n" % d["_error"])
            continue
        tid = d.get("traceId")
        L.append("- 期望：%s" % ("明确拒绝" if expect else "正常作答"))
        L.append("- 工具调用：%s ｜ 服务端耗时：%s ms ｜ 端到端：%s s" % (
            d.get("toolCalls"), d.get("durationMs"), d.get("_wall")))
        if tid:
            L.append("- trace：%s%s" % (TRACE, tid))
        evs = d.get("evidence") or []
        if evs:
            L.append("\n**工具返回（节选）：**\n")
            L.append("```json")
            for ev in evs:
                o = ev.get("data") or {}
                L.append("// tool = %s" % ev.get("tool"))
                L.append(json.dumps(trim(o), ensure_ascii=False, indent=2)[:1200])
            L.append("```\n")
        else:
            L.append("\n**工具返回：** 无（未调用任何工具）\n")
        ans = d.get("answer") or ""
        hit = looks_refused(ans)
        L.append("**最终 answer：**\n")
        L.append("```text")
        L.append(ans.strip()[:2000])
        L.append("```\n")
        if hit:
            L.append("> 命中的拒绝关键词：%s\n" % "、".join(hit))
        L.append("---\n")
    return "\n".join(L)


def main():
    workers = 1
    filt = None
    for a in sys.argv[1:]:
        if a.startswith("-w"):
            workers = int(a[2:])
        elif a.startswith("only:"):
            filt = a[5:]
    cases = CASES
    if filt:
        keys = [k.strip() for k in filt.split(",")]
        cases = [c for c in CASES if any(k.lower() in c[0].lower() for k in keys)]
    if not cases:
        print("没有匹配的用例")
        return

    print("### 时间窗口探针：%d 条用例" % len(cases))
    results = run(cases, workers)
    now = datetime.now(timezone(timedelta(hours=8)))
    when = now.strftime("%Y-%m-%d %H:%M")
    stamp = now.strftime("%Y-%m-%d")

    md = render(results, when)
    md_path = os.path.join(HERE, "时间窗口探针-%s.md" % stamp)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(md)

    raw = [{
        "tag": t, "question": q, "expect_refuse": e,
        "toolCalls": d.get("toolCalls"), "durationMs": d.get("durationMs"),
        "wallSec": d.get("_wall"), "traceId": d.get("traceId"),
        "traceUrl": (TRACE + str(d.get("traceId"))) if d.get("traceId") else None,
        "evidence": d.get("evidence"), "answer": d.get("answer"),
        "error": d.get("_error"),
    } for t, q, e, d in results]
    raw_dir = os.path.join(HERE, "raw")
    os.makedirs(raw_dir, exist_ok=True)
    raw_path = os.path.join(raw_dir, "时间窗口探针-%s.json" % stamp)
    with open(raw_path, "w", encoding="utf-8") as f:
        json.dump(raw, f, ensure_ascii=False, indent=2)

    # 控制台速览
    for tag, q, expect, d in results:
        if d.get("_error"):
            print("[%s] 请求失败: %s" % (tag, d["_error"]))
            continue
        ans = (d.get("answer") or "").replace("\n", " ")
        hit = looks_refused(ans)
        actual = "拒绝" if hit else "作答"
        ok = (actual == "拒绝") if expect else (actual == "作答")
        print("[%s] tools=%s %ss | 期望%s 实际%s | %s" % (
            tag, d.get("toolCalls"), d.get("_wall"),
            "拒绝" if expect else "作答", actual, "OK" if ok else "!! 不符预期"))
        print("    %s" % ans[:200])
        if d.get("traceId"):
            print("    trace: %s%s" % (TRACE, d["traceId"]))

    print("\n报告: %s" % md_path)
    print("原始: %s" % raw_path)


if __name__ == "__main__":
    main()
