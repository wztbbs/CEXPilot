#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
时间理解探针 —— 让 LLM 直接把「模糊时间表达」换算成具体区间，看它能不能算对。

为什么需要它：
    文章第 (2) 部分要回答「为什么时间窗口不能交给模型算」。
    不能只讲道理，要有实测：同一个模型、同一批表达，在有/无时间锚点两种条件下各跑 N 次，
    和代码按同一份规则算出的真值比对。

两臂对照：
    A 臂 blind   —— 不告诉模型今天几号，让它自己换算（等于让模型当钟表）
    B 臂 anchored —— 在 system 里注入「现在是 2026-09-25 21:48:11 周五 UTC+8」，其余不变
    两臂的差别就是「时间锚点」这一个变量的贡献。

题目：
    覆盖 TimeSpec 的 4 种结构 + 一个「未来判断」（对应 C5 那个 case：
    模型不知道今天几号，把昨天下午的数据判成未来时间而拒答）。

输出：
    回归测试/llm-time-probe-<时间戳>.csv   每次调用的原始输出
    终端：逐题命中率 + 错例

用法：
    python3 probe_llm_time.py                 # 默认每臂 5 次
    python3 probe_llm_time.py --n 3           # 快速版
    python3 probe_llm_time.py --model qwen-plus
"""

import argparse
import csv
import json
import os
import re
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

BASE_URL = os.environ.get(
    "LLM_NORMAL_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
API_KEY = os.environ.get("LLM_NORMAL_API_KEY", "sk-bae038fba269406d850a17bf9481f95c")
MODEL = os.environ.get("LLM_NORMAL_MODEL", "qwen-plus")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
# 产物继续落在 importants/回归测试/，保持与历史数据同目录
OUT_DIR = os.path.join(REPO_ROOT, "importants", "回归测试")

TZ8 = timezone(timedelta(hours=8))

# ---------------------------------------------------------------- 真值（由代码算）

def truth():
    """所有真值由本机时钟 + 固定规则算出，和 TimeRangeResolver 的口径保持一致：
    周一起算、区间左闭右开、时段边界 [6,12,18)。"""
    now = datetime.now(TZ8)
    today = now.date()
    monday = today - timedelta(days=today.weekday())       # 本周周一
    last_week_start = monday - timedelta(days=7)
    prev_week_start = monday - timedelta(days=14)
    yesterday = today - timedelta(days=1)
    t = lambda d, h=0, m=0, s=0: int(datetime(d.year, d.month, d.day, h, m, s, tzinfo=TZ8).timestamp() * 1000)
    return {
        "now": now,
        "today_str": today.strftime("%Y-%m-%d"),
        "weekday_cn": "一二三四五六日"[today.weekday()],
        "items": [
            # (编号, 表达, 期望 start, 期望 end, 说明)
            ("Q1", "今天",
             t(today), t(today + timedelta(days=1)),
             "自然日 full_period，左闭右开"),
            ("Q2", "昨天",
             t(yesterday), t(today),
             "offset=-1"),
            ("Q3", "上周",
             t(last_week_start), t(last_week_start + timedelta(days=7)),
             "周一 00:00 到下周一 00:00"),
            ("Q4", "上上周",
             t(prev_week_start), t(prev_week_start + timedelta(days=7)),
             "再往前推一周"),
            ("Q5", "昨天下午 3 点到 5 点",
             t(yesterday, 15, 0), t(yesterday, 17, 0),
             "relative_day_range，不跨天"),
            ("Q6", "本月至今",
             t(today.replace(day=1)), int(now.timestamp() * 1000),
             "to_request_time，终点=现在"),
            ("Q7", "过去 6 小时",
             int((now - timedelta(hours=6)).timestamp() * 1000), int(now.timestamp() * 1000),
             "rolling_window，不按整点取整"),
            ("Q8", "上个月",
             t((today.replace(day=1) - timedelta(days=1)).replace(day=1)),
             t(today.replace(day=1)),
             "自然月，不是 30 天"),
        ],
    }


# ---------------------------------------------------------------- 提问

SYS_BLIND = (
    "你是时间换算助手。把用户给出的时间表达换算成 UTC+8 的具体区间。\n"
    "只输出一行 JSON，不要任何解释、不要 markdown 代码块：\n"
    '{"start":"YYYY-MM-DD HH:mm:ss","end":"YYYY-MM-DD HH:mm:ss"}\n'
    "区间为左闭右开：end 是不包含的那个时刻。"
)

SYS_ANCHORED = (
    "你是时间换算助手。把用户给出的时间表达换算成 UTC+8 的具体区间。\n"
    "只输出一行 JSON，不要任何解释、不要 markdown 代码块：\n"
    '{"start":"YYYY-MM-DD HH:mm:ss","end":"YYYY-MM-DD HH:mm:ss"}\n'
    "区间为左闭右开：end 是不包含的那个时刻。"
)

QUESTION = "时间表达：%s"

# C5 同款：模型不知道今天几号时，能不能判断一个数据时间是否属于未来
FUTURE_Q = (
    "有一条行情数据的时间是 %s（UTC+8）。请判断：这个时间相对于「现在」是过去还是未来？\n"
    "只输出一行 JSON：{\"verdict\":\"past\"|\"future\"}"
)


def call(system, user, max_tokens=200, timeout=120):
    body = {"model": MODEL,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": user}],
            "temperature": 0, "max_tokens": max_tokens, "stream": False}
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL.rstrip("/") + "/chat/completions", data=data,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + API_KEY})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode("utf-8"))
        return d["choices"][0]["message"]["content"].strip(), (time.time() - t0) * 1000, None
    except urllib.error.HTTPError as e:
        return None, (time.time() - t0) * 1000, "HTTP %d %s" % (e.code, e.read().decode()[:200])
    except Exception as e:
        return None, (time.time() - t0) * 1000, repr(e)[:200]


def extract_json(text):
    m = re.search(r"\{.*\}", text or "", re.S)
    if not m:
        return None
    try:
        return json.loads(m.group())
    except Exception:
        return None


def parse_ts(s):
    try:
        return int(datetime.strptime(s.strip(), "%Y-%m-%d %H:%M:%S").replace(tzinfo=TZ8).timestamp() * 1000)
    except Exception:
        return None


def main():
    global MODEL, BASE_URL, API_KEY
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=5, help="每臂每题重复次数")
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--base-url", default=BASE_URL)
    ap.add_argument("--api-key", default=API_KEY)
    a = ap.parse_args()
    MODEL, BASE_URL, API_KEY = a.model, a.base_url, a.api_key

    truth_data = truth()
    now = truth_data["now"]
    anchor = ("现在是 %s（UTC+8，星期%s）。\n"
              % (now.strftime("%Y-%m-%d %H:%M:%S"), truth_data["weekday_cn"]))

    rows = []
    print("模型=%s ｜ 端点=%s ｜ 每臂 %d 次 ｜ 真值基准=%s（周%s）"
          % (MODEL, BASE_URL, a.n, now.strftime("%Y-%m-%d %H:%M:%S"), truth_data["weekday_cn"]))
    print()

    for arm, sys_tpl in (("blind", SYS_BLIND), ("anchored", SYS_ANCHORED)):
        system = (anchor + sys_tpl) if arm == "anchored" else sys_tpl
        print("===== %s 臂 =====" % arm)
        for qid, expr, exp_start, exp_end, note in truth_data["items"]:
            hits, errs, samples = 0, [], []
            for _ in range(a.n):
                text, dur, err = call(system, QUESTION % expr)
                rows.append([arm, qid, expr, "range", dur, (text or err or "")[:200]])
                if err:
                    errs.append(err)
                    continue
                obj = extract_json(text)
                if not obj:
                    errs.append("无法解析: %s" % text[:80])
                    continue
                ps, pe = parse_ts(obj.get("start", "")), parse_ts(obj.get("end", ""))
                if ps is None or pe is None:
                    errs.append("时间格式错误: %s" % obj)
                    continue
                # 容差：rolling / to_request_time 的终点允许 120 秒漂移（模型不可能精确到秒）
                tol = 120_000 if qid in ("Q6", "Q7") else 0
                ok = abs(ps - exp_start) <= tol and abs(pe - exp_end) <= tol
                hits += 1 if ok else 0
                if not ok:
                    samples.append("得 %s ~ %s（应为 %s ~ %s）" % (
                        obj.get("start"), obj.get("end"),
                        datetime.fromtimestamp(exp_start / 1000, TZ8).strftime("%Y-%m-%d %H:%M:%S"),
                        datetime.fromtimestamp(exp_end / 1000, TZ8).strftime("%Y-%m-%d %H:%M:%S")))
            print("  %s %-16s 命中 %d/%d  %s" % (qid, expr, hits, a.n, note))
            for s in samples[:2]:
                print("       错例: %s" % s)
            for e in errs[:1]:
                print("       异常: %s" % e)
        print()

    # 未来判断（C5 同款）
    print("===== 未来判断（C5 同款）=====")
    data_ts = datetime(now.year, now.month, now.day, 15, 0, 0, tzinfo=TZ8) - timedelta(days=1)
    data_str = data_ts.strftime("%Y-%m-%d %H:%M:%S")
    for arm, sys_tpl in (("blind", SYS_BLIND), ("anchored", SYS_ANCHORED)):
        system = (anchor + sys_tpl) if arm == "anchored" else sys_tpl
        hits = 0
        got = []
        for _ in range(a.n):
            text, dur, err = call(system, FUTURE_Q % data_str, max_tokens=60)
            rows.append([arm, "Q9", "未来判断 %s" % data_str, "future", dur, (text or err or "")[:200]])
            if err:
                continue
            obj = extract_json(text)
            v = (obj or {}).get("verdict")
            got.append(v)
            if v == "past":
                hits += 1
        print("  %-8s 判为 past %d/%d ｜ 实际输出: %s" % (arm, hits, a.n, got))
    print()

    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, "llm-time-probe-%s.csv" % now.strftime("%Y%m%d-%H%M"))
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["arm", "qid", "expr", "kind", "duration_ms", "raw"])
        w.writerows(rows)
    print("原始输出已写入 %s" % out)


if __name__ == "__main__":
    main()
