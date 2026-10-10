#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
固定 query 重复采样脚本 —— 给耗时 A/B 的中间档用。

为什么不用 run_regression.py：
  34 条不同用例的耗时跨度 1.4s ~ 15s，方差远大于连接池、跨境 RTT 这类
  1~2 秒的 delta，会被淹没。中间档必须用「同一句话跑 N 次」。
  ①⑤ 两个端点（大 delta + 需要质量护栏）仍用 run_regression.py 全量。

用法：
    python3 ab_run.py --tag case5 --n 7
    python3 ab_run.py --tag case4 --n 7 --q "币安上的 ETH 现在多少钱？" --q "ETH 最近24小时走势怎么样"

输出：
    回归测试/ab-<tag>-<时间戳>.csv     每次调用的明细
    终端打印：端到端 wall 统计 + 分调用类型的 token/耗时 + 延迟公式拟合

判读要点：
  - 池化 / 节点 这两类优化不改 token，只改公式的「常数项」。
    对比各档的常数项即可，比对比总耗时干净得多。
  - 关思考 / prompt 瘦身 改的是 token 项，看斜率和 token 均值。
"""

import argparse
import csv
import json
import os
import statistics as st
import time
import urllib.request
import uuid

BASE = "https://cex.hrscrm.net"
ASK = BASE + "/api/ask"
TRACE = BASE + "/api/trace/"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
# 产物继续落在 importants/回归测试/，保持与历史数据同目录
DEFAULT_OUTDIR = os.path.join(REPO_ROOT, "importants", "回归测试")

DEFAULT_Q = [
    "币安上的 ETH 现在多少钱？",
    "ETH 最近24小时的走势怎么样",
]


def ask(q, conv=None, timeout=180):
    conv = conv or str(uuid.uuid4())
    body = json.dumps({"conversationId": conv, "question": q}).encode()
    req = urllib.request.Request(ASK, data=body, headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode())
    except Exception as e:
        return None, round(time.time() - t0, 2), repr(e)[:160]
    return d, round(time.time() - t0, 2), None


def fetch_trace(tid, tries=6, gap=1.5):
    """trace 落库有延迟，失败就重试几轮。"""
    for i in range(tries):
        try:
            with urllib.request.urlopen(TRACE + tid, timeout=30) as r:
                return json.loads(r.read().decode())
        except Exception:
            if i < tries - 1:
                time.sleep(gap)
    return None


def llm_calls(t):
    """取出 trace 里所有 LLM_CALL 事件，附带 attempt。"""
    out = []
    for e in (t or {}).get("events", []):
        if e.get("event_type") != "LLM_CALL":
            continue
        att = 1
        try:
            att = json.loads(e.get("input_json") or "{}").get("attempt", 1)
        except Exception:
            pass
        out.append(dict(name=e.get("name"), dur=e.get("duration_ms"),
                        pt=e.get("prompt_tokens") or 0, ct=e.get("completion_tokens") or 0,
                        attempt=att, err=e.get("error")))
    return out


def fit(rows):
    """duration_ms ~ 常数 + 输出token；返回 (常数, 斜率, R²)。普通最小二乘。"""
    ys = [r[0] for r in rows]
    xs = [r[1] for r in rows]
    n = len(rows)
    if n < 3:
        return None
    mx, my = st.mean(xs), st.mean(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None
    b = sum((xs[i] - mx) * (ys[i] - my) for i in range(n)) / sxx
    a = my - b * mx
    pred = [a + b * x for x in xs]
    ybar = my
    ss_res = sum((ys[i] - pred[i]) ** 2 for i in range(n))
    ss_tot = sum((y - ybar) ** 2 for y in ys)
    r2 = 1 - ss_res / ss_tot if ss_tot else 0.0
    return a, b, r2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True, help="本档标识，如 case4 / case5")
    ap.add_argument("--n", type=int, default=7, help="每句有效采样次数（不含预热）")
    ap.add_argument("--q", action="append", default=[], help="采样用的 query，可重复传")
    ap.add_argument("--gap", type=float, default=1.5, help="两次请求之间的间隔秒数")
    ap.add_argument("--outdir", default=DEFAULT_OUTDIR)
    a = ap.parse_args()

    qs = a.q or DEFAULT_Q
    rows, walls, excluded = [], [], []

    for qi, q in enumerate(qs):
        # 预热 1 次，丢弃：首次调用含类加载、连接池初始化、DNS
        ask(q)
        time.sleep(a.gap)
        for k in range(a.n):
            d, wall, err = ask(q)
            if err:
                print("  [ERR] %s -> %s" % (q[:20], err))
                continue
            tid = d.get("traceId") or d.get("trace_id")
            t = fetch_trace(tid) if tid else None
            calls = llm_calls(t)
            bad = [c for c in calls if (c["attempt"] or 1) > 1]
            if bad:
                excluded.append((q, tid, len(bad)))
            good = [c for c in calls if c["dur"] and (c["attempt"] or 1) == 1]
            for c in good:
                rows.append(dict(tag=a.tag, q=qi, query=q, round=k, name=c["name"],
                                 dur=c["dur"], pt=c["pt"], ct=c["ct"]))
            walls.append((qi, q, wall, (t or {}).get("duration_ms"), (t or {}).get("tool_calls")))
            print("  [%d/%d] %-24s wall=%.2fs  调用=%d" % (k + 1, a.n, q[:24], wall, len(good)))
            time.sleep(a.gap)

    os.makedirs(a.outdir, exist_ok=True)
    ts = time.strftime("%Y%m%d-%H%M")
    out = os.path.join(a.outdir, "ab-%s-%s.csv" % (a.tag, ts))
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["tag", "q", "query", "round", "name", "dur", "pt", "ct"])
        w.writeheader()
        w.writerows(rows)

    print("\n=== %s 汇总 ===" % a.tag)
    print("有效 LLM 调用 %d 次，端到端样本 %d 条，含 repair 被剔除 %d 条" % (len(rows), len(walls), len(excluded)))
    print("\n[端到端 wall 秒]")
    for qi in range(len(qs)):
        xs = sorted(w[2] for w in walls if w[0] == qi)
        if xs:
            print("  %-26s n=%d 均值=%.2f 中位=%.2f min=%.2f max=%.2f" %
                  (qs[qi][:26], len(xs), st.mean(xs), st.median(xs), xs[0], xs[-1]))

    print("\n[分调用类型]")
    for name in sorted(set(r["name"] for r in rows)):
        g = [r for r in rows if r["name"] == name]
        print("  %-12s n=%2d dur均值=%6.0fms  输入token=%6.0f  输出token=%5.0f" %
              (name, len(g), st.mean([r["dur"] for r in g]),
               st.mean([r["pt"] for r in g]), st.mean([r["ct"] for r in g])))

    print("\n[延迟公式拟合  duration_ms = 常数 + 斜率 × 输出token]")
    for name in sorted(set(r["name"] for r in rows)):
        g = [(r["dur"], r["ct"]) for r in rows if r["name"] == name]
        f = fit(g)
        if f:
            print("  %-12s 常数=%7.0fms  斜率=%6.2f ms/token  R²=%.4f" % (name, f[0], f[1], f[2]))
    fall = fit([(r["dur"], r["ct"]) for r in rows])
    if fall:
        print("  %-12s 常数=%7.0fms  斜率=%6.2f ms/token  R²=%.4f" % ("全部", fall[0], fall[1], fall[2]))
        print("\n  常数项就是「每次调用的固定开销」——池化与节点的变化看这一行。")

    print("\nCSV: %s" % out)


if __name__ == "__main__":
    main()
