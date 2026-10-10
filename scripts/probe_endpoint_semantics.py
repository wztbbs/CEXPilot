#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
交易所端点「边界语义」探针。

用途
----
新增数据源时，不要照抄同族接口的 -1/+1 写法，先跑这个脚本把契约测出来。
方法：固定区间一端，另一端依次取 边界值-1ms / 边界值 / 边界值+1ms，观察首根末根，
即可反推出该接口的：参数模型、timestamp 语义、端点开闭、是否网格取整。

用法
----
    python3 probe_endpoint_semantics.py              # 全量扫描所有已登记端点
    python3 probe_endpoint_semantics.py binance_klines okx_oi   # 只跑指定端点
    python3 probe_endpoint_semantics.py --list        # 列出端点 id

输出
----
每个端点打印一张小表，并给出「要从 [start, end) 拿到正确的点，该怎么传参」的结论。
"""

import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone, timedelta

BINANCE = "http://43.160.213.63:18080"   # 币安代理
OKX = "http://43.160.213.63:18081"       # OKX 代理（需 UA，否则 Cloudflare 1010）
UA = "Mozilla/5.0"
TZ8 = timezone(timedelta(hours=8))

# 测试区间取「昨天 12:00~13:00」：一定是已走完的历史，不会受未收盘影响。
YESTERDAY = (datetime.now(TZ8) - timedelta(days=1)).date()


def ms(day, h=0, m=0):
    return int(datetime(day.year, day.month, day.day, h, m, tzinfo=TZ8).timestamp() * 1000)


def fmt(v):
    return datetime.fromtimestamp(v / 1000, TZ8).strftime("%m-%d %H:%M:%S") if v is not None else "-"


def http(url, headers=None):
    req = urllib.request.Request(url)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    return json.loads(urllib.request.urlopen(req, timeout=90).read())


# ---------------------------------------------------------------- 端点登记
# 每个端点需要：拼 URL 的函数、从返回里取 timestamp 的函数。
# 参数统一给 (start_ms, end_ms, interval_code)，由 spec 决定怎么映射到接口参数。

def _b_klines(start, end, bar):
    return f"{BINANCE}/fapi/v1/klines?symbol=BTCUSDT&interval={bar}&startTime={start}&endTime={end}&limit=500"


def _b_oi(start, end, bar):
    return f"{BINANCE}/futures/data/openInterestHist?symbol=BTCUSDT&period={bar}&startTime={start}&endTime={end}&limit=500"


def _b_taker(start, end, bar):
    return f"{BINANCE}/futures/data/takerlongshortRatio?symbol=BTCUSDT&period={bar}&startTime={start}&endTime={end}&limit=500"


def _o_candles(start, end, bar):
    # OKX 游标制：before = 下界（返回更新的），after = 上界（返回更早的）
    return (f"{OKX}/api/v5/market/candles?instId=BTC-USDT-SWAP&bar={bar}"
            f"&before={start}&after={end}&limit=100")


def _o_hist_candles(start, end, bar):
    return (f"{OKX}/api/v5/market/history-candles?instId=BTC-USDT-SWAP&bar={bar}"
            f"&before={start}&after={end}&limit=100")


def _o_oi(start, end, bar):
    return (f"{OKX}/api/v5/rubik/stat/contracts/open-interest-history?instId=BTC-USDT-SWAP"
            f"&period={bar}&begin={start}&end={end}&limit=100")


def _o_taker(start, end, bar):
    return (f"{OKX}/api/v5/rubik/stat/taker-volume-contract?instId=BTC-USDT-SWAP"
            f"&period={bar}&begin={start}&end={end}&limit=100")


def _o_funding(start, end, bar):
    return (f"{OKX}/api/v5/public/funding-rate-history?instId=BTC-USDT-SWAP"
            f"&before={start}&after={end}&limit=100")


def _plain_first(x):        # [ts, ...]
    return int(x[0])


def _ts_field(x):           # 币安 /futures/data/* 返回 {"timestamp": ...}
    return int(x["timestamp"])


def _funding_time(x):       # {"fundingTime": ...}
    return int(x["fundingTime"])


ENDPOINTS = [
    # id, 说明, 拼 URL, 取 ts, 交易所, 默认粒度, 是否要 UA
    ("binance_klines", "币安 /fapi/v1/klines", _b_klines, _plain_first, "binance", "5m", False),
    ("binance_oi", "币安 /futures/data/openInterestHist", _b_oi, _ts_field, "binance", "5m", False),
    ("binance_taker", "币安 /futures/data/takerlongshortRatio", _b_taker, _ts_field, "binance", "5m", False),
    ("okx_candles", "OKX /market/candles（游标）", _o_candles, _plain_first, "okx", "5m", True),
    ("okx_hist_candles", "OKX /market/history-candles（游标）", _o_hist_candles, _plain_first, "okx", "5m", True),
    ("okx_oi", "OKX rubik open-interest-history", _o_oi, _plain_first, "okx", "5m", True),
    ("okx_taker", "OKX rubik taker-volume-contract", _o_taker, _plain_first, "okx", "5m", True),
    ("okx_funding", "OKX /public/funding-rate-history（游标）", _o_funding, _funding_time, "okx", None, True),
]

# OKX code 字段非 0 表示业务错误
_OKX_ERROR = lambda r: r.get("code") != "0"


def fetch(spec, start, end, bar):
    _, _, build, pick, exchange, _, need_ua = spec
    try:
        raw = http(build(start, end, bar), {"User-Agent": UA} if need_ua else None)
    except urllib.error.HTTPError as e:
        return None, "HTTP %d" % e.code
    except Exception as e:  # noqa: BLE001
        return None, repr(e)[:60]
    if exchange == "okx":
        if _OKX_ERROR(raw):
            return None, "code=%s msg=%s" % (raw.get("code"), raw.get("msg"))
        raw = raw.get("data") or []
    if not raw:
        return [], None
    return sorted(pick(x) for x in raw), None


# 固定周期序列（资金费率 8h 一期）用 5m 窗口扫不到，单独给窗口
WINDOW_OVERRIDE = {
    "okx_funding": lambda day: (ms(day - timedelta(days=2)), ms(day)),
}


def probe(spec, day, bar_override=None):
    """固定区间一端、扫描另一端，返回扫描结果。"""
    sid, desc, _, _, _, def_bar, _ = spec
    bar = bar_override or def_bar or "5m"
    if sid in WINDOW_OVERRIDE:
        start, end = WINDOW_OVERRIDE[sid](day)
    else:
        start, end = ms(day, 12, 0), ms(day, 13, 0)
    rows = []

    # 下界扫描：end 固定为 end，start 侧取 start-1 / start / start+1
    for label, s in [("start-1ms", start - 1), ("start", start), ("start+1ms", start + 1)]:
        ts, err = fetch(spec, s, end, bar)
        rows.append((f"下界 {label}", ts, err))
    # 上界扫描：start 固定为 start-1ms（保证下界不干扰），end 侧取 end-1 / end / end+1
    for label, e in [("end-1ms", end - 1), ("end", end), ("end+1ms", end + 1)]:
        ts, err = fetch(spec, start - 1, e, bar)
        rows.append((f"上界 {label}", ts, err))

    return sid, desc, start, end, rows


def summarize(sid, start, end, rows):
    """从扫描结果反推语义。返回结论字符串列表。

    判据刻意取得最笨：直接看「边界那一根」在不在结果里，而不是比较相邻两档的
    首/末根是否相同 —— 后者在两端不对称（下界相同=闭、上界相同=开），极易写反。
    """
    out = []
    got = {r[0]: (r[1] or []) for r in rows}

    # 下界闭 ⇔ 传 start 时，start 那根（12:00）仍在结果里
    lo_closed = start in got.get("下界 start", [])
    # 上界闭 ⇔ 传 end 时，end 那根（13:00）仍在结果里
    hi_closed = end in got.get("上界 end", [])

    out.append("下界：传 start=%s → %s（%s）"
               % (fmt(start), "含" if lo_closed else "不含", "闭" if lo_closed else "开"))
    out.append("上界：传 end=%s → %s（%s）"
               % (fmt(end), "含" if hi_closed else "不含", "闭" if hi_closed else "开"))

    # 网格吸附：必须用 start+1ms 判，不能用 start-1ms。
    # 因为闭区间下 start-1ms 的首根天然等于 start，会误报成"有吸附"。
    # start+1ms 若首根仍是 start → 接口把 12:00:00.001 吸附回了 12:00（存在吸附）。
    lo_plus = got.get("下界 start+1ms") or []
    snapped = bool(lo_plus) and lo_plus[0] == start
    if not lo_plus:
        out.append("取整：无数据，无法判定")
    elif snapped:
        out.append("取整：start+1ms 的首根仍是 %s → 存在网格吸附（floor 到粒度）" % fmt(start))
    else:
        out.append("取整：start+1ms 的首根是 %s → 不做网格吸附" % fmt(lo_plus[0]))

    n_mid = len(got.get("下界 start") or [])
    out.append("窗口内实测 %d 根" % n_mid)

    if lo_closed and hi_closed:
        out.append("⇒ 原生语义 [start, end] 两端闭：取 [start,end) 必须自行过滤 t < end")
    elif not lo_closed and not hi_closed:
        out.append("⇒ 原生语义 (start, end) 两端开：取 [start,end) 需把下界左移 1ms")
    else:
        out.append("⇒ 混合语义（一端闭一端开），需单独建模")
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    if "--list" in sys.argv:
        for sid, desc, *_ in ENDPOINTS:
            print("%-18s %s" % (sid, desc))
        return
    targets = [e for e in ENDPOINTS if (not args or e[0] in args)]
    if not targets:
        print("未知端点：%s（用 --list 查看）" % args)
        return 1

    day = YESTERDAY
    print("测试区间：%s 12:00:00 ~ 13:00:00 (UTC+8)，粒度 5m，期望 12 根 12:00..12:55"
          % day.isoformat())
    print("交易所代理：币安 %s ｜ OKX %s" % (BINANCE, OKX))
    print()

    for spec in targets:
        sid, desc, start, end, rows = probe(spec, day)
        print("=" * 78)
        print("%s  ——  %s" % (sid, desc))
        print("-" * 78)
        for label, ts, err in rows:
            if err:
                print("  %-14s -> %s" % (label, err))
            elif ts:
                print("  %-14s -> n=%3d  首=%s  末=%s" % (label, len(ts), fmt(ts[0]), fmt(ts[-1])))
            else:
                print("  %-14s -> n=  0  (空)" % label)
        print("  结论：")
        for line in summarize(sid, start, end, rows):
            print("    - " + line)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
