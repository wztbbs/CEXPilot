#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
T1 离线标定 —— 绕开业务系统，直打 LLM 接口，标定延迟公式的三个系数。

    duration_ms ≈ C + a × prompt_tokens + b × completion_tokens

为什么需要它：
    业务侧测出来的 a、b 是「拟合出来的」，不是「测出来的」——
    输入 token 和输出 token 在真实 query 里高度相关（问题越复杂，两头都大），
    共线性会让 a 被 b 吃掉。本脚本用正交网格把两者分开：
    输入长度和输出长度各自独立变化，互不牵连。

做法：
    - 输入侧：把一段固定 filler 文本重复 R 遍塞进 prompt，R 分若干档。
    - 输出侧：要求模型把一段固定 block 原样重复 K 遍，K 分若干档。
    - (R, K) 构成二维网格，每格跑 n 次，用三元最小二乘解出 C / a / b。

用法：
    python3 llm_bench.py                          # 默认 4×4 网格，每格 3 次，约 5~8 分钟
    python3 llm_bench.py --n 1 --pt 0,16,64 --ct 1,4,16   # 快速版，约 1 分钟
    python3 llm_bench.py --base-url https://dashscope.aliyuncs.com/compatible-mode/v1  # 国内节点
    python3 llm_bench.py --enable-thinking        # 顺带测思考模式

输出：
    回归测试/llmbench-<tag>-<时间戳>.csv   每次调用明细
    终端：逐格实测均值 + 三元拟合结果（含 R²、各系数标准误与 t 值）

判读要点：
  - a（prefill 单价）通常极小，t 值可能 < 2 → 说明在本网格下测不出来，
    不要硬报数字，报告成「上限」。
  - b（decode 单价）是主力，应该非常显著。
  - C 是每次调用的固定开销，含 RTT + 排队 + 握手。
    同一脚本换 --base-url 跑两遍，C 的差就是节点代价的独立验证。
"""

import argparse
import csv
import json
import os
import statistics as st
import time
import urllib.error
import urllib.request

# 线上 LLM 配置（海外 endpoint）。换国内节点用 --base-url 覆盖。
BASE_URL = os.environ.get(
    "LLM_NORMAL_BASE_URL", "https://dashscope-us.aliyuncs.com/compatible-mode/v1")
API_KEY = os.environ.get("LLM_NORMAL_API_KEY", "sk-127f56563dc94575a2e4619d48e001e7")
MODEL = os.environ.get("LLM_NORMAL_MODEL", "qwen-plus")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
# 产物继续落在 importants/回归测试/，保持与历史数据同目录
DEFAULT_OUTDIR = os.path.join(REPO_ROOT, "importants", "回归测试")

# filler：只用来把输入 token 撑到目标长度，内容无意义
FILLER = (
    "这是一段用于性能测试的填充文本，本身不包含任何语义信息，"
    "作用是让请求的可测量输入长度稳定地落在预定档位上。"
    "文本采用常见的书面语表达，句式平实，词汇分布接近自然语言，"
    "以便分词结果与真实业务 prompt 保持在同一个量级上。"
    "重复出现的目的是构造可控的输入长度梯度，而不是传递内容。"
)

# block：要求模型原样重复它，用来把输出 token 撑到目标长度
BLOCK = "行情快照已生成，标的为 BTC/USDT，交易所为币安，时间戳已对齐。"

SYS = "你是一个严格遵循指令的复读器。只输出被要求重复的内容，不要添加任何说明。"


def call(base_url, api_key, model, user, max_tokens, extra=None, timeout=300):
    body = {
        "model": model,
        "messages": [{"role": "system", "content": SYS},
                     {"role": "user", "content": user}],
        "temperature": 0,
        "max_tokens": max_tokens,
        "stream": False,
    }
    if extra:
        body.update(extra)
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=data,
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + api_key},
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return None, (time.time() - t0) * 1000, "HTTP %d %s" % (e.code, e.read().decode()[:200])
    except Exception as e:
        return None, (time.time() - t0) * 1000, repr(e)[:200]
    return d, (time.time() - t0) * 1000, None


def solve(A, b):
    """高斯消元解线性方程组。"""
    n = len(A)
    M = [list(A[i]) + [b[i]] for i in range(n)]
    for i in range(n):
        p = max(range(i, n), key=lambda r: abs(M[r][i]))
        M[i], M[p] = M[p], M[i]
        if abs(M[i][i]) < 1e-12:
            return None
        for r in range(i + 1, n):
            f = M[r][i] / M[i][i]
            for c in range(i, n + 1):
                M[r][c] -= f * M[i][c]
    x = [0.0] * n
    for i in range(n - 1, -1, -1):
        s = M[i][n] - sum(M[i][c] * x[c] for c in range(i + 1, n))
        x[i] = s / M[i][i]
    return x


def ols3(rows):
    """rows: [(dur, pt, ct)] → (C, a, b, R², [se_C, se_a, se_b])"""
    n = len(rows)
    if n < 5:
        return None
    X = [[1.0, float(r[1]), float(r[2])] for r in rows]
    y = [float(r[0]) for r in rows]
    XtX = [[sum(X[k][i] * X[k][j] for k in range(n)) for j in range(3)] for i in range(3)]
    Xty = [sum(X[k][i] * y[k] for k in range(n)) for i in range(3)]
    beta = solve(XtX, Xty)
    if beta is None:
        return None
    pred = [beta[0] + beta[1] * X[k][1] + beta[2] * X[k][2] for k in range(n)]
    ybar = st.mean(y)
    ssr = sum((y[k] - pred[k]) ** 2 for k in range(n))
    sst = sum((v - ybar) ** 2 for v in y)
    r2 = 1 - ssr / sst if sst else 0.0
    dof = n - 3
    sigma2 = ssr / dof if dof > 0 else 0.0
    # cov = sigma2 * inv(XtX)：对单位矩阵逐列求解
    inv_cols = []
    for j in range(3):
        e = [1.0 if i == j else 0.0 for i in range(3)]
        col = solve(XtX, e)
        if col is None:
            return None
        inv_cols.append(col)
    se = []
    for i in range(3):
        var = sigma2 * inv_cols[i][i]
        se.append(max(var, 0.0) ** 0.5)
    return beta[0], beta[1], beta[2], r2, se


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="t1", help="输出文件标识")
    ap.add_argument("--n", type=int, default=3, help="每格采样次数（不含预热）")
    # filler 单次约 85 token，block 单次约 24 token（qwen-plus 实测）
    ap.add_argument("--pt", default="0,16,64,160", help="filler 重复次数档位（控制输入 token）")
    ap.add_argument("--ct", default="1,4,16,32", help="block 重复次数档位（控制输出 token）")
    ap.add_argument("--gap", type=float, default=1.0, help="两次调用间隔秒数")
    ap.add_argument("--base-url", default=BASE_URL)
    ap.add_argument("--api-key", default=API_KEY)
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--enable-thinking", action="store_true", help="透传 enable_thinking=true")
    ap.add_argument("--outdir", default=DEFAULT_OUTDIR)
    a = ap.parse_args()

    pt_lv = [int(x) for x in a.pt.split(",") if x.strip()]
    ct_lv = [int(x) for x in a.ct.split(",") if x.strip()]
    extra = {"enable_thinking": True} if a.enable_thinking else None

    print("T1 离线标定   model=%s   url=%s" % (a.model, a.base_url))
    print("网格：输入档 filler×%s × 输出档 block×%s，每格 n=%d，合计 %d 次调用"
          % (pt_lv, ct_lv, a.n, len(pt_lv) * len(ct_lv) * a.n))
    if not a.api_key:
        raise SystemExit("缺少 api-key：用 --api-key 或环境变量 LLM_NORMAL_API_KEY")

    def prompt(r, k):
        parts = []
        if r:
            parts.append("参考资料：\n" + (FILLER * r))
        if k <= 0:
            parts.append("请只回复一个字：好")
        else:
            parts.append("下面给出一段文本，请把它原样重复 %d 遍，"
                         "每遍之间用一个换行分隔。不要添加任何其他内容。\n---\n%s"
                         % (k, BLOCK))
        return "\n\n".join(parts)

    # 预热：首次调用含 DNS、TLS 握手、服务端冷启动，丢弃
    print("\n[预热] ", end="", flush=True)
    _, d0, e0 = call(a.base_url, a.api_key, a.model, prompt(0, 1), 120, extra)
    print("%.0fms %s" % (d0, ("ERR " + e0) if e0 else "ok"))
    if e0:
        raise SystemExit("预热失败，请检查 base-url / api-key / model")
    time.sleep(a.gap)

    rows = []
    for rep in range(a.n):
        for r in pt_lv:
            for k in ct_lv:
                want = k * 60 + 200
                d, dur, err = call(a.base_url, a.api_key, a.model, prompt(r, k), want, extra)
                if err:
                    print("  [ERR] filler×%d block×%d -> %s" % (r, k, err))
                    time.sleep(a.gap)
                    continue
                try:
                    ch = d["choices"][0]["message"]["content"]
                    u = d.get("usage") or {}
                    pt = u.get("prompt_tokens") or 0
                    ct = u.get("completion_tokens") or 0
                    fin = d["choices"][0].get("finish_reason")
                except Exception as ex:
                    print("  [ERR] 解析失败 %s" % ex)
                    time.sleep(a.gap)
                    continue
                actual = ch.count(BLOCK) if k > 0 else (1 if ch.strip() else 0)
                ok = (actual == k) if k > 0 else True
                rows.append(dict(tag=a.tag, rep=rep, filler=r, block=k,
                                 dur=round(dur, 1), pt=pt, ct=ct,
                                 repeats=actual, ok=ok, finish=fin))
                print("  [%d/%d] filler×%-3d block×%-3d  pt=%-6d ct=%-5d dur=%-7.0fms %s"
                      % (len(rows), len(pt_lv) * len(ct_lv) * a.n, r, k, pt, ct, dur,
                         "" if ok else "!! 实际重复 %d 遍" % actual))
                time.sleep(a.gap)

    os.makedirs(a.outdir, exist_ok=True)
    ts = time.strftime("%Y%m%d-%H%M")
    out = os.path.join(a.outdir, "llmbench-%s-%s.csv" % (a.tag, ts))
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["tag", "rep", "filler", "block", "dur",
                                          "pt", "ct", "repeats", "ok", "finish"])
        w.writeheader()
        w.writerows(rows)

    good = [r for r in rows if r["ok"]]
    print("\n=== 逐格实测（%.0f 次有效 / %d 次总调用）===" % (len(good), len(rows)))
    print("%-14s %9s %8s %10s" % ("格 (filler×block)", "输入token", "输出token", "耗时均值"))
    for r in pt_lv:
        for k in ct_lv:
            g = [x for x in good if x["filler"] == r and x["block"] == k]
            if not g:
                continue
            print("  %-12s %9.0f %8.0f %9.0fms"
                  % ("%d×%d" % (r, k), st.mean(x["pt"] for x in g),
                     st.mean(x["ct"] for x in g), st.mean(x["dur"] for x in g)))

    f = ols3([(r["dur"], r["pt"], r["ct"]) for r in good])
    print("\n=== 三元拟合  duration_ms = C + a × 输入token + b × 输出token ===")
    if not f:
        print("  样本不足或矩阵奇异，无法拟合")
    else:
        C, av, bv, r2, se = f
        print("  C 常数项      = %8.1f ms      (se %.1f)" % (C, se[0]))
        print("  a 输入单价    = %8.4f ms/token (se %.4f, t=%.2f)" %
              (av, se[1], av / se[1] if se[1] else 0))
        print("  b 输出单价    = %8.4f ms/token (se %.4f, t=%.2f)" %
              (bv, se[2], bv / se[2] if se[2] else 0))
        print("  R²            = %.4f   (n=%d)" % (r2, len(good)))
        print()
        if se[1] and abs(av / se[1]) < 2:
            print("  ! a 的 t 值 < 2：输入项在本网格下【测不出来】，")
            print("    只能报上限 a ≤ %.4f ms/token（= a + 2×se），不要当成实测值引用。"
                  % (av + 2 * se[1]))
        if se[2] and abs(bv / se[2]) < 2:
            print("  ! b 也不显著，样本可能太少，加大 --n 重跑。")
        print("\n  交叉用法：换 --base-url 再跑一遍，比较两次的 C，"
              "就是节点/链路代价的独立验证（不依赖业务系统）。")

    print("\nCSV: %s" % out)


if __name__ == "__main__":
    main()
