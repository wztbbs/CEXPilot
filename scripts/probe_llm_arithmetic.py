#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LLM 算术探针 —— 给模型一个 List<Object>，让它算某个 double 属性的平均值，看它算不算得准。

为什么要这个脚本：
    文章的「第一部分」要给读者一个直观感受：大模型算数不靠谱。
    光讲道理没用，得有能跑的实验，而且读者自己按计算器就能验证。
    这里用最贴近 CEXPilot 线上 payload 的形态做实验：工具返回一串 List<Object>，
    对象里有若干个属性，其中目标属性是 double，让它求平均值。

四个自变量：
    1. scene   —— 数值形态（K线收盘价 / 资金费率 / 持仓量 / 净流入有正有负 / 成交额大数）
    2. n       —— 列表长度（默认 5 / 10 / 20 / 50）
    3. attrs   —— 对象干扰属性 lean(2个) vs rich(6个，含另一个同量级 double)
    4. arm     —— blind(直接给 JSON) vs cot(要求逐步累加再给 JSON)

因变量：
    avg 的误差（分 EXACT / NEAR / WRONG 三档）、count 数对不对、
    同一输入重复多次答案是否一致（不一致 = 不可控）。

数据用固定 seed 生成 ⇒ 换模型、换参数跑出来的数据完全相同，可以横向对比。

产物：
    回归测试/llm-arith-probe-<时间戳>.csv     每次调用的原始输出
    回归测试/LLM算术探针-<日期>.md            汇总报告（含可直接贴进文章的最小复现案例）

用法：
    python3 probe_llm_arithmetic.py                    # 全量（场景×长度×属性×臂×repeat）
    python3 probe_llm_arithmetic.py --repeat 3         # 每格重复 3 次（看一致性）
    python3 probe_llm_arithmetic.py --n 10,20          # 只跑这两个长度
    python3 probe_llm_arithmetic.py only:funding       # 只跑 tag 含 funding 的场景
    python3 probe_llm_arithmetic.py --mini             # 只跑一个 N=10 的最小案例，打印全文
    python3 probe_llm_arithmetic.py -w 4               # 4 并发（默认 3）
"""

import argparse
import csv
import json
import math
import os
import random
import re
import statistics
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field as dc_field
from datetime import datetime, timedelta, timezone

BASE_URL = os.environ.get(
    "LLM_NORMAL_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
API_KEY = os.environ.get("LLM_NORMAL_API_KEY", "sk-bae038fba269406d850a17bf9481f95c")
MODEL = os.environ.get("LLM_NORMAL_MODEL", "qwen-plus")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
# 产物继续落在 importants/回归测试/，保持与历史数据同目录
OUT_DIR = os.path.join(REPO_ROOT, "importants", "回归测试")

SEED = 20260928
BASE_TIME = datetime(2026, 9, 27, 0, 0, 0, tzinfo=timezone(timedelta(hours=8)))


# ------------------------------------------------------------------ 数据：固定 seed 生成

def _rnd(rng, lo, hi, digits):
    return round(rng.uniform(lo, hi), digits)


@dataclass
class Scene:
    key: str            # 用于 only: 过滤
    title: str          # 报告里的中文名
    target: str         # 目标属性名
    unit: str
    note: str
    lo: float
    hi: float
    digits: int
    # rich 模式下塞在旁边的干扰字段（另一个 double），刻意取同量级
    distract: tuple = dc_field(default=("volume", 1.0, 9999.0, 3))

    def values(self, n):
        rng = random.Random("%s-%d" % (self.key, n))
        return [_rnd(rng, self.lo, self.hi, self.digits) for _ in range(n)]


SCENES = [
    Scene("kline", "K线收盘价", "close", "USDT",
          "60000~70000 的整数感小数，2 位小数 —— 最像『人能算』的一类",
          60000.0, 70000.0, 2, distract=("volume", 1.0, 9999.0, 3)),
    Scene("funding", "资金费率", "funding_rate", "",
          "0.0001 量级、8 位小数 —— 值极小且小数位长，最容易丢精度",
          -0.0005, 0.0030, 8, distract=("mark_price", 60000.0, 70000.0, 2)),
    Scene("oi", "持仓量", "open_interest", "BTC",
          "1e4~1e6、3 位小数 —— 位数多",
          10000.0, 1000000.0, 3, distract=("close", 60000.0, 70000.0, 2)),
    Scene("flow", "净流入", "net_flow", "BTC",
          "-8000~+8000 有正有负 —— 符号处理与抵消",
          -8000.0, 8000.0, 4, distract=("buy_volume", 0.0, 50000.0, 3)),
    Scene("amount", "成交额", "quote_volume", "USDT",
          "1e8~9e8、2 位小数 —— 大数求和，最容易丢位",
          100000000.0, 900000000.0, 2, distract=("open_interest", 10000.0, 999999.0, 3)),
]


def build_objects(scene, values, rich):
    """拼出和线上工具 payload 同形的 List<Object>。目标属性在中间，旁边挨着一个同量级 double。"""
    rng = random.Random("%s-rich-%d" % (scene.key, len(values)))
    out = []
    for i, v in enumerate(values):
        t = BASE_TIME + timedelta(minutes=15 * i)
        ts = t.strftime("%Y-%m-%d %H:%M:%S")
        if not rich:
            obj = {"open_time": ts, scene.target: v}
        else:
            dn, dlo, dhi, ddig = scene.distract
            dv = _rnd(rng, dlo, dhi, ddig)
            obj = {
                "symbol": "BTCUSDT",
                "interval": "15m",
                "open_time": ts,
                dn: dv,
                scene.target: v,
                "trade_count": rng.randint(1000, 99999),
                "closed": True,
            }
        out.append(obj)
    return out


def compact_json(objs):
    return json.dumps(objs, ensure_ascii=False, separators=(",", ":"))


# ------------------------------------------------------------------ 提问

def system_prompt(scene, arm):
    head = (
        "你是数据计算助手。用户会给一段 JSON 数组，请你计算其中所有记录"
        " `%s` 字段（单位 %s）的算术平均值：总和 ÷ 记录数。\n"
        "平均值必须精确计算，不要估算、不要取整、不要用抽样代替。"
        % (scene.target, scene.unit or "无")
    )
    tail = (
        '只输出一行 JSON，不要任何解释文字、不要 markdown 代码块：\n'
        '{"count":<整数>,"sum":<数字>,"avg":<数字>}'
    )
    if arm == "blind":
        return head + "\n" + tail
    # cot：要求先把每一项列出来逐步累加，再给结论
    cot = (
        "\n请按以下步骤做：\n"
        "1. 逐行列出每条记录的 `%s` 值（保持原值，不要改写精度）；\n"
        "2. 逐行给出「累加到第 k 条」的累计和；\n"
        "3. 核对记录数；\n"
        "4. 最后一步输出一行 JSON：\n"
        '{"count":<整数>,"sum":<数字>,"avg":<数字>}'
        % scene.target
    )
    return head + cot


def user_prompt(scene, objs, arm):
    # 注意：这里刻意不告诉模型一共有几条。真实工具返回的 JSON 数组也不带计数，
    # 「数清楚有几条」本身就是求和的一部分——数错条数是最常见的败因之一。
    return ("数据如下：\n%s\n\n请计算 `%s` 的算术平均值。"
            % (compact_json(objs), scene.target))


# ------------------------------------------------------------------ 调用

def call(system, user, max_tokens=4000, timeout=180, retries=2):
    body = {"model": MODEL,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": user}],
            "temperature": 0, "max_tokens": max_tokens, "stream": False}
    data = json.dumps(body).encode("utf-8")
    url = BASE_URL.rstrip("/") + "/chat/completions"
    last_err = None
    for attempt in range(retries + 1):
        req = urllib.request.Request(
            url, data=data,
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + API_KEY})
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                d = json.loads(r.read().decode("utf-8"))
            return d["choices"][0]["message"]["content"].strip(), (time.time() - t0) * 1000, None
        except urllib.error.HTTPError as e:
            last_err = "HTTP %d %s" % (e.code, e.read().decode("utf-8")[:300])
        except Exception as e:
            last_err = repr(e)[:300]
        time.sleep(1.5 * (attempt + 1))
    return None, 0.0, last_err


NUM = r"[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?"


def extract_result(text):
    """从回答里抠最后那个 {...}，优先取带 avg 的那个。"""
    if not text:
        return None
    cands = re.findall(r"\{[^{}]*\}", text, re.S)
    best = None
    for c in reversed(cands):
        try:
            o = json.loads(c)
        except Exception:
            continue
        if isinstance(o, dict) and "avg" in o:
            best = o
            break
        if best is None:
            best = o
    if best is None:
        # 退化：抓 avg= 这种写法
        m = re.search(r'"?avg"?\s*[:=]\s*(%s)' % NUM, text)
        if m:
            return {"avg": float(m.group(1))}
        return None
    return best


def to_float(x):
    try:
        if isinstance(x, (int, float)):
            return float(x)
        return float(str(x).replace(",", "").strip())
    except Exception:
        return None


# ------------------------------------------------------------------ 判分

def grade(ans, truth, count, n, values):
    """对比误差。容差按数据的量级 scale 定，避免不同场景口径不一致。

    四种结局要分开看，因为它们意味着完全不同的失败：
        UNPARSED —— 没按格式答 / 答非所问（连题都没接住）
        WRONG    —— 答了但数值错（算不对）
        NEAR     —— 数值接近但不精确（≈5 位有效数字，看着像对了）
        EXACT    —— 完全一致
    """
    if ans is None:
        return "UNPARSED", None, None
    scale = max(abs(v) for v in values) or 1.0
    err = abs(ans - truth)
    if err <= scale * 1e-9:
        verdict = "EXACT"
    elif err <= scale * 1e-5:
        verdict = "NEAR"
    else:
        verdict = "WRONG"
    rel = err / (abs(truth) if abs(truth) > scale * 1e-6 else scale)
    cnt_ok = (count == n)
    return verdict, rel, cnt_ok


# ------------------------------------------------------------------ 执行

def run_one(scene, n, rich, arm, rep, max_tokens=None):
    if max_tokens is None:
        # cot 臂要逐条列出 + 逐步累加，token 随 N 增长；blind 臂只出一个 JSON
        max_tokens = 6000 + 60 * n if arm == "cot" else 800
    values = scene.values(n)
    objs = build_objects(scene, values, rich)
    truth_sum = math.fsum(values)
    truth_avg = truth_sum / n
    sys_p = system_prompt(scene, arm)
    usr_p = user_prompt(scene, objs, arm)
    text, dur, err = call(sys_p, usr_p, max_tokens=max_tokens)
    res = extract_result(text)
    ans = to_float((res or {}).get("avg"))
    got_sum = to_float((res or {}).get("sum"))
    got_cnt = (res or {}).get("count")
    try:
        got_cnt = int(got_cnt) if got_cnt is not None else None
    except Exception:
        got_cnt = None
    verdict, rel, cnt_ok = grade(ans, truth_avg, got_cnt, n, values)
    return {
        "scene": scene.key, "title": scene.title, "target": scene.target,
        "n": n, "attrs": "rich" if rich else "lean", "arm": arm, "rep": rep,
        "truth_sum": truth_sum, "truth_avg": truth_avg,
        "ans_sum": got_sum, "ans_avg": ans, "ans_count": got_cnt,
        "verdict": verdict, "rel_err": rel, "count_ok": cnt_ok,
        "duration_ms": dur, "error": err,
        "prompt_user": usr_p, "raw": (text or "")[:4000],
    }


# 结局的严重程度排序：越靠后越糟
RANK = {"EXACT": 0, "NEAR": 1, "WRONG": 2, "UNPARSED": 3, "ERROR": 4}


def fmt_num(x, dig=6):
    if x is None:
        return "-"
    return ("%." + str(dig) + "g") % x


# ------------------------------------------------------------------ 产物

CSV_COLS = ["scene", "title", "target", "n", "attrs", "arm", "rep",
            "truth_sum", "truth_avg", "ans_sum", "ans_avg", "ans_count",
            "verdict", "rel_err", "count_ok", "duration_ms", "error", "raw"]


def dump_csv(rows, path):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(CSV_COLS)
        for r in rows:
            w.writerow([r.get(c) for c in ("scene", "title", "target", "n", "attrs", "arm", "rep")] +
                       [r.get(c) for c in ("truth_sum", "truth_avg", "ans_sum", "ans_avg", "ans_count")] +
                       [r.get("verdict"), r.get("rel_err"), r.get("count_ok"),
                        "%.0f" % (r.get("duration_ms") or 0), r.get("error") or "",
                        (r.get("raw") or "").replace("\n", " \\n ")[:2000]])


def load_rows_from_csv(path):
    """从 CSV 恢复 rows。喂给模型的输入不用存——数据是固定 seed 生成的，按 (scene,n,attrs) 重建即可。"""
    scene_by_key = {s.key: s for s in SCENES}
    out = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            for c in ("truth_sum", "truth_avg", "ans_sum", "ans_avg", "rel_err", "duration_ms"):
                r[c] = float(r[c]) if r[c] not in ("", None) else None
            r["n"] = int(r["n"])
            r["rep"] = int(r["rep"])
            r["ans_count"] = int(r["ans_count"]) if r["ans_count"] not in ("", None) else None
            r["count_ok"] = r["count_ok"] in ("True", "true", "1")
            r["raw"] = (r.get("raw") or "").replace(" \\n ", "\n")
            sc = scene_by_key.get(r["scene"])
            if sc:
                objs = build_objects(sc, sc.values(r["n"]), r["attrs"] == "rich")
                r["prompt_user"] = user_prompt(sc, objs, r["arm"])
            else:
                r["prompt_user"] = ""
            out.append(r)
    return out


def group_cells(rows):
    g = {}
    for r in rows:
        g.setdefault((r["scene"], r["n"], r["attrs"], r["arm"]), []).append(r)
    for v in g.values():
        v.sort(key=lambda x: (RANK.get(x["verdict"], 9), -(x["rel_err"] or 0)), reverse=False)
    return g


def _spread(cell):
    """同一个格子里，多次答案之间的最大相对差距。差距越大，"不可控"这件事越刺眼。"""
    vals = [r["ans_avg"] for r in cell if r["ans_avg"] is not None]
    if len(vals) < 2:
        return 0.0
    truth = cell[0]["truth_avg"]
    denom = abs(truth) if truth else 1.0
    return (max(vals) - min(vals)) / denom


def pick_articles(rows):
    """挑三个最适合写进文章的案例。选例子的标准不是"错得最惨"，而是"读者能自己验"。

        ① 最容易自己验算的 —— N 最小且算错。5 个数，读者按计算器 30 秒就能核对。
        ② 最刺眼的       —— N≤20 且每次都错（稳定错，不是抖动），相对误差最大。
        ③ 最不可控的     —— 同一份数据、temperature=0，两次答案不一样，且 N 尽量小、差距尽量大。
    """
    cells = group_cells(rows)
    wrong_cells = {k: v for k, v in cells.items()
                   if any(r["verdict"] in ("WRONG", "UNPARSED") for r in v)}
    picks, used = [], set()

    if wrong_cells:
        k = min(wrong_cells, key=lambda k: (k[1], -max(r["rel_err"] or 0 for r in wrong_cells[k])))
        picks.append(("① 最容易自己验算：只有 %d 个数，读者按计算器就能核对" % k[1],
                      wrong_cells[k]))
        used.add(k)

    stable = {k: v for k, v in wrong_cells.items()
              if k[1] <= 20 and k not in used
              and all(r["verdict"] in ("WRONG", "UNPARSED") for r in v)}
    if stable:
        k = max(stable, key=lambda k: (max(r["rel_err"] or 0 for r in stable[k]), -k[1]))
        picks.append(("② 最刺眼：%d 个数，两次都错，错得离谱" % k[1], stable[k]))
        used.add(k)

    # ③ 要求 N≥10：5 条数据的抖动说不清是"随机"还是"样本太小"，10 条以上才有说服力。
    #    同长度时按场景定义序取第一个（kline 排最前，读者最好验算）。
    order = {s.key: i for i, s in enumerate(SCENES)}
    incons = {k: v for k, v in cells.items()
              if k not in used and k[1] >= 10
              and len(set(r["ans_avg"] for r in v if r["ans_avg"] is not None)) > 1}
    if incons:
        k = min(incons, key=lambda k: (k[1], order.get(k[0], 99)))
        picks.append(("③ 最不可控：同一份数据、同一个问题，两次答案相差 %.2f%%"
                      % (_spread(incons[k]) * 100), incons[k]))
        used.add(k)

    return picks


def write_report(rows, summary, bads, md_out, model, base_url):
    cells = group_cells(rows)
    incons_cells = [k for k, v in cells.items()
                    if len(set(r["ans_avg"] for r in v if r["ans_avg"] is not None)) > 1]
    ok_total = sum(1 for r in rows if r["verdict"] in ("EXACT", "NEAR"))

    with open(md_out, "w", encoding="utf-8") as f:
        f.write("# LLM 算术探针 —— List<Object> 求 double 属性平均值\n\n")
        f.write("给模型一段 `List<Object>`（每个对象若干属性，目标属性是 double），让它求该属性的算术平均值。\n\n")
        f.write("- 模型：`%s`，temperature=0，端点 `%s`\n" % (model, base_url))
        f.write("- 数据：固定 seed 生成，换模型 / 换参数跑，喂进去的数字完全一致\n")
        f.write("- 判分：`EXACT` 误差 ≤ 量级×1e-9；`NEAR` ≤ 量级×1e-5（约 5 位有效数字）；"
                "`WRONG` 答了但数值错；`UNPARSED` 连格式都没答对\n")
        f.write("- 样本：%d 次调用，%d 个格子（场景×长度×干扰属性×臂）\n\n"
                % (len(rows), len(cells)))

        f.write("## 一句话结论\n\n")
        f.write("**%d/%d 算对（%.0f%%）；%d/%d 个格子在 temperature=0 下两次给出的答案不一样。**\n\n"
                % (ok_total, len(rows), 100.0 * ok_total / max(1, len(rows)),
                   len(incons_cells), len(cells)))

        f.write("## 全量结果\n\n")
        f.write("| 场景 | N | 干扰属性 | 臂 | 最差判定 | ok率 | count 正确率 | 两次答案一致 | 最大相对误差 |\n")
        f.write("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n")
        for sc, n, tag, arm, worst, ok, cnt, inc, rel in summary:
            f.write("| %s(%s) | %d | %s | %s | **%s** | %.0f%% | %.0f%% | %s | %s |\n"
                    % (sc.title, sc.key, n, tag, arm, worst, ok * 100, cnt * 100,
                       "否" if inc else "是", fmt_num(rel, 3)))

        f.write("\n## 两个自变量各自的影响\n\n")
        f.write("| 自变量 | 档位 | ok 率 |\n| --- | --- | --- |\n")
        for arm in ("blind", "cot"):
            sub = [r for r in rows if r["arm"] == arm]
            if sub:
                ok = sum(1 for r in sub if r["verdict"] in ("EXACT", "NEAR"))
                f.write("| 提问方式 | %s（%s） | %.0f%% |\n"
                        % (arm, "直接要结果" if arm == "blind" else "要求逐步累加",
                           100.0 * ok / len(sub)))
        for n in sorted(set(r["n"] for r in rows)):
            sub = [r for r in rows if r["n"] == n and r["arm"] == "blind"]
            if sub:
                ok = sum(1 for r in sub if r["verdict"] in ("EXACT", "NEAR"))
                f.write("| 列表长度 | N=%d（直接要结果） | %.0f%% |\n" % (n, 100.0 * ok / len(sub)))

        f.write("\n## 三个可以直接贴进文章的案例\n\n")
        for label, cell in pick_articles(rows):
            rs = sorted(cell, key=lambda x: RANK.get(x["verdict"], 9), reverse=True)
            head = rs[0]
            f.write("### %s\n\n" % label)
            f.write("场景：`%s`（字段 `%s`），N=%d，%s 干扰属性，%s 臂\n\n"
                    % (head["title"], head["target"], head["n"], head["attrs"], head["arm"]))
            if head.get("prompt_user"):
                f.write("**喂给模型的输入**\n\n```json\n%s\n```\n\n" % head["prompt_user"])
            f.write("| | count | sum | avg |\n| --- | --- | --- | --- |\n")
            f.write("| 代码算的真值 | %d | %.10g | %.10g |\n"
                    % (head["n"], head["truth_sum"], head["truth_avg"]))
            for i, r in enumerate(rs):
                f.write("| 模型第 %d 次 | %s | %s | %s |\n"
                        % (i + 1, r["ans_count"], fmt_num(r["ans_sum"], 10), fmt_num(r["ans_avg"], 10)))
            f.write("\n判定：`%s`，最大相对误差 %s\n\n" % (head["verdict"], fmt_num(head["rel_err"], 4)))
            if head.get("raw"):
                f.write("<details><summary>模型原始输出</summary>\n\n```\n%s\n```\n\n</details>\n\n"
                        % head["raw"][:3000])

        f.write("\n---\n\n失败用例 %d/%d（%.0f%%）。CSV 原始输出见同目录 `llm-arith-probe-*.csv`。\n"
                % (len(bads), len(rows), 100.0 * len(bads) / max(1, len(rows))))


def main():
    global MODEL, BASE_URL, API_KEY
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", default="5,10,20,50", help="列表长度档位，逗号分隔")
    ap.add_argument("--attrs", default="lean,rich", help="属性丰富度：lean / rich / 逗号分隔两者")
    ap.add_argument("--arms", default="blind,cot", help="臂：blind / cot / 逗号分隔两者")
    ap.add_argument("--repeat", type=int, default=2, help="每格重复次数（>=2 才能看一致性）")
    ap.add_argument("-w", "--workers", type=int, default=3, help="并发数")
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--base-url", default=BASE_URL)
    ap.add_argument("--api-key", default=API_KEY)
    ap.add_argument("--mini", action="store_true", help="只跑一个 N=10 的最小案例，打印完整输入输出")
    ap.add_argument("--only", default="", help="只跑 tag 含该串的场景")
    ap.add_argument("--report", default="",
                    help="不调 API，直接从已有 CSV 重新生成 Markdown 报告")
    a = ap.parse_args()
    MODEL, BASE_URL, API_KEY = a.model, a.base_url, a.api_key

    # --report 支持传 basename，自动到 importants/回归测试/ 下查找
    if a.report and not os.path.isabs(a.report) and not os.path.exists(a.report):
        alt = os.path.join(OUT_DIR, a.report)
        if os.path.exists(alt):
            a.report = alt

    ns = [int(x) for x in a.n.split(",") if x.strip()]
    attrs_list = [x.strip() for x in a.attrs.split(",") if x.strip()]
    arms = [x.strip() for x in a.arms.split(",") if x.strip()]
    scenes = [s for s in SCENES if (not a.only or a.only in s.key)]

    # ---------------- 只重出报告（不花钱、不打 API）
    if a.report:
        rows = load_rows_from_csv(a.report)
        bads = [r for r in rows if r["verdict"] in ("WRONG", "UNPARSED", "ERROR")]
        summary = []
        for sc in SCENES:
            for n in sorted(set(r["n"] for r in rows)):
                for tag in ("lean", "rich"):
                    for arm in arms:
                        rs = [r for r in rows if r["scene"] == sc.key and r["n"] == n
                              and r["attrs"] == tag and r["arm"] == arm]
                        if not rs:
                            continue
                        vs = [r["verdict"] for r in rs]
                        ok = sum(1 for v in vs if v in ("EXACT", "NEAR")) / len(vs)
                        cnt = sum(1 for r in rs if r["count_ok"]) / len(rs)
                        avgs = [round(r["ans_avg"], 6) for r in rs if r["ans_avg"] is not None]
                        rel = [r["rel_err"] for r in rs if r["rel_err"] is not None]
                        summary.append((sc, n, tag, arm,
                                        sorted(vs, key=lambda v: RANK.get(v, 9))[-1],
                                        ok, cnt, len(set(avgs)) > 1,
                                        max(rel) if rel else None))
        md_out = a.report.replace(".csv", ".md")
        write_report(rows, summary, bads, md_out, MODEL, BASE_URL)
        print("已从 %s 重新生成报告 → %s" % (a.report, md_out))
        return

    # ---------------- mini 模式：一个可直接贴进文章的完整案例
    if a.mini:
        scene = scenes[0]
        r = run_one(scene, 10, False, "blind", 0)
        print("=== 最小复现案例（可直接贴进文章）===\n")
        print("【系统提示词】\n%s\n" % system_prompt(scene, "blind"))
        print("【用户输入】\n%s\n" % r["prompt_user"])
        print("【模型原始输出】\n%s\n" % (r["raw"] or r["error"]))
        print("【代码算的真值】count=10  sum=%.10g  avg=%.10g" % (r["truth_sum"], r["truth_avg"]))
        print("【模型答案】    count=%s  sum=%s  avg=%s" % (
            r["ans_count"], fmt_num(r["ans_sum"], 10), fmt_num(r["ans_avg"], 10)))
        print("【判定】%s ｜ 相对误差 %s ｜ 耗时 %.0f ms"
              % (r["verdict"], fmt_num(r["rel_err"], 4), r["duration_ms"]))
        return

    # ---------------- 全量
    jobs = []
    for sc in scenes:
        for n in ns:
            for rich in (True, False):
                tag = "rich" if rich else "lean"
                if tag not in attrs_list:
                    continue
                for arm in arms:
                    for rep in range(a.repeat):
                        jobs.append((sc, n, rich, arm, rep))

    print("模型=%s ｜ 端点=%s" % (MODEL, BASE_URL))
    print("用例数=%d ｜ 场景=%s ｜ 长度=%s ｜ 属性=%s ｜ 臂=%s ｜ 每格 %d 次 ｜ 并发 %d"
          % (len(jobs), ",".join(s.key for s in scenes), ns, attrs_list, arms,
             a.repeat, a.workers))
    print("（数据由固定 seed=%d 生成，换模型/换参数跑，喂进去的数字完全一致）\n" % SEED)

    rows = []
    done = 0
    t_start = time.time()
    with ThreadPoolExecutor(max_workers=a.workers) as ex:
        futs = {ex.submit(run_one, *j): j for j in jobs}
        for fu in as_completed(futs):
            sc, n, rich, arm, rep = futs[fu]
            try:
                rows.append(fu.result())
            except Exception as e:
                rows.append({"scene": sc.key, "title": sc.title, "target": sc.target,
                             "n": n, "attrs": "rich" if rich else "lean", "arm": arm,
                             "rep": rep, "truth_sum": None, "truth_avg": None,
                             "ans_sum": None, "ans_avg": None, "ans_count": None,
                             "verdict": "ERROR", "rel_err": None, "count_ok": False,
                             "duration_ms": 0, "error": repr(e)[:200],
                             "prompt_user": "", "raw": ""})
            done += 1
            if done % 10 == 0 or done == len(jobs):
                print("  ... %d/%d  已用时 %.0fs" % (done, len(jobs), time.time() - t_start))

    rows.sort(key=lambda r: (r["scene"], r["n"], r["attrs"], r["arm"], r["rep"]))

    # ---------------- 控制台汇总
    print("\n" + "=" * 92)
    print("汇总：每格取 %d 次中最差的一次； ok = EXACT 或 NEAR 的比例" % a.repeat)
    print("=" * 92)
    print("%-9s %-6s %-5s %-6s | %-8s | %-8s | %-9s | %-8s | %s"
          % ("场景", "N", "属性", "臂", "avg 判定", "ok率", "count对", "一致", "相对误差"))
    print("-" * 92)

    def cell(rs):
        vs = [r["verdict"] for r in rs]
        ok = sum(1 for v in vs if v in ("EXACT", "NEAR")) / len(vs)
        cnt = sum(1 for r in rs if r["count_ok"]) / len(rs)
        avgs = [round(r["ans_avg"], 6) for r in rs if r["ans_avg"] is not None]
        consist = len(set(avgs)) <= 1
        rel = [r["rel_err"] for r in rs if r["rel_err"] is not None]
        worst = sorted(vs, key=lambda v: RANK.get(v, 9))[-1]
        return worst, ok, cnt, (not consist), (max(rel) if rel else None)

    summary = []
    for sc in scenes:
        for n in ns:
            for rich in (True, False):
                tag = "rich" if rich else "lean"
                if tag not in attrs_list:
                    continue
                for arm in arms:
                    rs = [r for r in rows
                          if r["scene"] == sc.key and r["n"] == n
                          and r["attrs"] == tag and r["arm"] == arm]
                    if not rs:
                        continue
                    worst, ok, cnt, incons, rel = cell(rs)
                    summary.append((sc, n, tag, arm, worst, ok, cnt, incons, rel))
                    print("%-9s %-6d %-5s %-6s | %-8s | %-8s | %-9s | %-8s | %s"
                          % (sc.key, n, tag, arm, worst,
                             "%.0f%%" % (ok * 100),
                             "%.0f%%" % (cnt * 100),
                             "不一致" if incons else "一致",
                             fmt_num(rel, 3)))
        print("-" * 92)

    # ---------------- 错例
    bads = [r for r in rows if r["verdict"] in ("WRONG", "UNPARSED", "ERROR")]
    print("\n失败用例 %d/%d（%.0f%%）："
          % (len(bads), len(rows), 100.0 * len(bads) / max(1, len(rows))))
    shown = set()
    for r in bads:
        k = (r["scene"], r["n"], r["attrs"], r["arm"])
        if k in shown:
            continue
        shown.add(k)
        detail = ""
        if r["verdict"] == "UNPARSED":
            detail = "(未按要求输出 JSON)"
        elif r["ans_count"] != r["n"]:
            detail = "(count 也数错: %s≠%d)" % (r["ans_count"], r["n"])
        print("  [%s N=%d %s %s] 真值 avg=%.10g  模型给 %s  %s"
              % (r["scene"], r["n"], r["attrs"], r["arm"],
                 r["truth_avg"] or 0,
                 fmt_num(r["ans_avg"], 10) if r["ans_avg"] is not None else "无",
                 detail))

    # ---------------- 产物
    os.makedirs(OUT_DIR, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d-%H%M")
    csv_out = os.path.join(OUT_DIR, "llm-arith-probe-%s.csv" % ts)
    dump_csv(rows, csv_out)
    print("\n原始输出 → %s" % csv_out)

    md_out = os.path.join(OUT_DIR, "LLM算术探针-%s.md" % datetime.now().strftime("%Y-%m-%d"))
    write_report(rows, summary, bads, md_out, MODEL, BASE_URL)
    print("汇总报告 → %s" % md_out)


if __name__ == "__main__":
    main()
