#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统一单轮 common 回归：40 条，含现货、时间窗口及九种算子。

这是线上端到端测试，只有明确要求执行时才运行！导入模块和本地单测不发请求。
用法：python3 scripts/run_regression.py [only:A1,E1] [group:E] [-w 2]
      python3 scripts/run_regression.py --base http://localhost:8082 --list
默认串行；筛选只执行所选 case，无额外冒烟/追问/监控/自动重试。
产物：importants/回归测试/common-YYYY-MM-DD.md 与 raw/common-YYYY-MM-DD.json。
同日运行前自动备份旧产物。全部 case 完成后，以开始/结束时间调用缓存统计接口。
"""
import argparse
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation, localcontext
from pathlib import Path

TZ = timezone(timedelta(hours=8))
HERE = Path(__file__).resolve().parents[1] / 'importants' / '回归测试'
SUITE_VERSION = 'common-v2-40'
FALLBACK = re.compile(r'无法可靠生成查询计划|没有能够可靠回答此问题的查询计划|本次未执行查询|澄清表单生成失败')
REFERENCE = re.compile(r'^\{\{([^{}]+)\}\}$')


@dataclass(frozen=True)
class Case:
    id: str
    question: str
    metrics: tuple = ()
    operators: tuple = ()
    # (operator, input slot, expected metric index); input 必须引用该取数节点，不能靠相同数字蒙混。
    links: tuple = ()
    gaps: tuple = ()
    reject: bool = False
    no_calculations: bool = False


def M(metric, exchange='binance', shape='snapshot', market='perpetual', base='BTC', **extra):
    return dict(metric=metric, exchange=exchange, shape=shape, market=market, base=base, **extra)


def funding(exchange='binance', count=10):
    return M('funding.rate_settled', exchange, 'recent_n', count=count)


def turnover(exchange='binance', market='perpetual', window='yesterday'):
    return M('trade.turnover', exchange, 'range_statistic', market, window=window)


def build_cases():
    """日期占位符直到实际发问前才展开；此函数不访问网络。"""
    return [
        Case('A1', '币安的比特币现在多少钱？', (M('price.last'),), no_calculations=True),
        Case('A2', 'OKX ETH/USDT 永续当前持仓量是多少？', (M('oi.quantity', 'okx', base='ETH'),)),
        Case('A3', '币安 BTC/USDT 永续当前买卖一价差和盘口不平衡度分别是多少？',
             (M('orderbook.spread'), M('orderbook.imbalance'))),
        Case('A4', '币安 BTC/USDT 永续当前标记价、指数价分别是多少？',
             (M('mark.price'), M('index.price')), no_calculations=True),
        Case('A5', '币安 BTC/USDT 永续最近10期已结算资金费率分别是多少？', (funding(),), no_calculations=True),
        Case('A6', '币安 BTC/USDT 永续最近一小时的主动买入量、主动卖出量和买入量占比分别是多少？',
             tuple(M(x, shape='range_statistic', window='rolling1h') for x in
                   ('taker.buy_volume', 'taker.sell_volume', 'taker.buy_ratio'))),
        Case('A7', '币安 BTC/USDT 永续当前价格、官方24小时成交量和成交额分别是多少？',
             (M('price.last'), M('trade.volume_24h', shape='official_24h'), M('trade.turnover_24h', shape='official_24h'))),
        Case('B1', '币安 BTC/USDT 永续按本次请求时刻回溯，最近6小时涨跌幅是多少？',
             (M('price.change_pct', shape='range_statistic', window='rolling6h'),)),
        Case('B2', '按上海时区，币安 BTC/USDT 永续昨天一整天的最高价和最低价分别是多少？',
             tuple(M(x, shape='range_statistic', window='yesterday') for x in ('price.high', 'price.low'))),
        Case('B3', '按上海时区，币安 BTC/USDT 永续上周一整周的成交额是多少？', (turnover(window='lastweek'),)),
        Case('B4', '按上海时区，昨天15点到17点，币安 BTC/USDT 永续的价格涨跌幅和持仓量变化率分别是多少？',
             tuple(M(x, shape='range_statistic', window='yesterday15-17') for x in ('price.change_pct', 'oi.change_pct'))),
        Case('B5', '查询币安 BTC/USDT 永续在 {date} 08:00到10:00的涨跌幅，按上海时区。',
             (M('price.change_pct', shape='range_statistic', window='absolute'),)),
        Case('B6', '列出币安 BTC/USDT 永续最近一小时已完结的5分钟K线收盘价。',
             (M('price.close', shape='time_series', window='rolling1h', interval='5m'),), no_calculations=True),
        Case('C1', '币安 BTC/USDT 永续最近10期已结算资金费率的等权平均是多少？',
             (funding(),), ('avg',), (('avg', 'collection', 0),)),
        Case('C2', '币安 BTC/USDT 永续最近10期已结算资金费率合计是多少？',
             (funding(),), ('sum',), (('sum', 'collection', 0),)),
        Case('C3', '币安 BTC/USDT 永续最近10期已结算资金费率最高、最低分别是多少，发生在哪一期？',
             (funding(),), ('max', 'min'), (('max', 'collection', 0), ('min', 'collection', 0))),
        Case('C4', '比较币安和OKX的 BTC/USDT 永续最近3期已结算资金费率均值，哪家更高？',
             (funding(count=3), funding('okx', 3)), ('avg', 'avg', 'compare')),
        Case('C5', '币安减OKX，BTC/USDT 永续昨天成交额相差多少USDT？',
             (turnover(), turnover('okx')), ('difference',), (('difference', 'left', 0), ('difference', 'right', 1))),
        Case('C6', '币安 BTC/USDT 永续昨天成交额是OKX同期的多少倍？',
             (turnover(), turnover('okx')), ('ratio',), (('ratio', 'left', 0), ('ratio', 'right', 1))),
        Case('C7', '币安 BTC/USDT 永续昨天成交额比前天增长或下降了百分之多少？',
             (turnover(), turnover(window='daybefore')), ('relative_change',),
             (('relative_change', 'current', 0), ('relative_change', 'baseline', 1))),
        Case('C8', '币安 BTC/USDT 永续昨天的价格区间收益率，按实际观测时长、365天基准做简单年化是多少？',
             (M('price.change_pct', shape='range_statistic', window='yesterday'),), ('annualize',),
             (('annualize', 'rate', 0),)),
        Case('D1', '币安 BTC/USDT 永续昨天成交额，占币安与OKX同期成交额合计的百分之多少？',
             (turnover(), turnover('okx')), ('sum', 'ratio'), (('ratio', 'left', 0),)),
        Case('E1', '币安 BTC/USDT 现货现在多少钱？', (M('price.last', market='spot'),)),
        Case('E2', 'OKX ETH/USDT 现货现在多少钱？', (M('price.last', 'okx', market='spot', base='ETH'),)),
        Case('E3', 'OKX BTC/USDT 现货当前价格、官方24小时成交量和成交额分别是多少？',
             (M('price.last', 'okx', market='spot'),) + tuple(M(x, 'okx', 'official_24h', 'spot') for x in ('trade.volume_24h', 'trade.turnover_24h'))),
        Case('E4', '币安 BTC/USDT 现货最近6小时最高价、最低价分别是多少？',
             tuple(M(x, shape='range_statistic', market='spot', window='rolling6h') for x in ('price.high', 'price.low'))),
        Case('E5', 'OKX BTC/USDT 现货昨天一整天成交额是多少？', (turnover('okx', 'spot'),)),
        Case('E6', '币安 BTC/USDT 现货当前买一、卖一、价差和盘口不平衡度分别是多少？',
             tuple(M(x, market='spot') for x in ('orderbook.best_bid', 'orderbook.best_ask', 'orderbook.spread', 'orderbook.imbalance'))),
        Case('E7', 'OKX BTC/USDT 现货当前买一、卖一和价差分别是多少？',
             tuple(M(x, 'okx', market='spot') for x in ('orderbook.best_bid', 'orderbook.best_ask', 'orderbook.spread'))),
        Case('E8', '币安 BTC/USDT 现货最近一小时主动买入量、主动卖出量和买入量占比分别是多少？',
             tuple(M(x, shape='range_statistic', market='spot', window='rolling1h') for x in
                   ('taker.buy_volume', 'taker.sell_volume', 'taker.buy_ratio'))),
        Case('E9', '按上海时区，列出OKX BTC/USDT 现货昨天15点到17点，每根5分钟K线的收盘价，并计算等权平均。',
             (M('price.close', 'okx', 'time_series', 'spot', window='yesterday15-17', interval='5m'),),
             ('avg',), (('avg', 'collection', 0),)),
        Case('E10', '币安 BTC/USDT 永续最新成交价减去同所现货最新成交价，相差多少USDT？',
             (M('price.last'), M('price.last', market='spot')), ('difference',),
             (('difference', 'left', 0), ('difference', 'right', 1))),
        Case('F1', '币安 BTC/USDT 现货当前资金费率是多少？', reject=True, gaps=(('现货',), ('资金费率', 'funding'))),
        Case('F2', 'OKX BTC/USDT 现货的持仓量、标记价和指数价分别是多少？', reject=True,
             gaps=(('现货',), ('持仓', '未平仓', 'OI'), ('标记', 'mark'), ('指数', 'index'))),
        Case('F3', 'OKX BTC/USDT 现货最近一小时主动买入量占比是多少？', reject=True,
             gaps=(('OKX',), ('现货',), ('主动买', '买入', 'taker'))),
        Case('F4', '币安 BTC/USDT 永续过去一周的资金费率均值是多少？', reject=True,
             gaps=(('资金费率', 'funding'), ('时间窗口', '时间区间', '一周', '按时间'))),
        Case('F5', '币安 BTC/USDC 现货昨天成交额是多少？', reject=True, gaps=(('USDC', 'USDT', '计价', '报价'),)),
        Case('F6', '列出币安 BTC/USDT 现货最近一小时的1分钟K线。', reject=True, gaps=(('1分钟', '1 分钟', '1m', '粒度', '周期'),)),
        Case('F7', '介绍一下亚历山大大帝。', reject=True, gaps=(('加密', '行情', '领域', '范围'),)),
        Case('F8', '帮我分析这笔以太坊交易：0x321674b0c3ade0814ebee8477505314b8ea3c606d7e87b8a7348c02fc262e108',
             reject=True, gaps=(('链上', '交易分析', '交易查询', '交易哈希', '以太坊交易'),)),
    ]


CASES = build_cases()


def number(value):
    if isinstance(value, bool) or value is None:
        raise ValueError('不是数值')
    result = Decimal(str(value))
    if not result.is_finite():
        raise ValueError('不是有限数值')
    return result


def equal_number(actual, expected):
    actual, expected = number(actual), number(expected)
    return abs(actual - expected) <= max(Decimal('1e-28'), abs(expected) * Decimal('1e-28'))


def rows(response):
    result = []
    for ev in response.get('evidence') or []:
        identity, data = ev.get('identity') or {}, ev.get('data') or {}
        instrument = identity.get('instrument') or data.get('instrument') or {}
        result.append(dict(ev, metric=identity.get('metric') or data.get('metric'),
                           exchange=identity.get('exchange') or data.get('exchange'),
                           shape=identity.get('query_shape') or data.get('query_shape'),
                           market=instrument.get('market_type'), base=instrument.get('base'),
                           instrument=instrument, unit=identity.get('unit') or data.get('unit'), data=data))
    return result


def metric_matches(row, spec):
    return all(row.get(key) == value for key, value in spec.items()
               if key not in ('window', 'count', 'interval'))


def parse_time(value):
    if not isinstance(value, str):
        raise ValueError('缺少时间')
    dt = datetime.fromisoformat(value)
    return dt.replace(tzinfo=TZ) if dt.tzinfo is None else dt


def window_bounds(window, started_ms, absolute_date):
    now = datetime.fromtimestamp(started_ms / 1000, TZ)
    midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
    if window.startswith('rolling'):
        return None, timedelta(hours=int(window[7:-1]))
    if window in ('yesterday', 'daybefore'):
        start = midnight - timedelta(days=1 if window == 'yesterday' else 2)
        return start, start + timedelta(days=1)
    if window == 'lastweek':
        end = midnight - timedelta(days=midnight.weekday())
        return end - timedelta(days=7), end
    if window == 'yesterday15-17':
        return midnight - timedelta(days=1) + timedelta(hours=15), midnight - timedelta(days=1) + timedelta(hours=17)
    if window == 'absolute':
        day = datetime.fromisoformat(absolute_date).replace(tzinfo=TZ)
        return day + timedelta(hours=8), day + timedelta(hours=10)
    raise ValueError('未知窗口 ' + window)


def expected_unit(row):
    """目录层 unit 是语义（base/quote/percent/ratio），证据层已由
    MetricQueryResolver.resolveUnit 具体化为币种：base→基础币、quote→USDT。
    判据必须贴着证据层口径，否则所有价格/成交量用例都会假 FAIL。"""
    metric = row['metric']
    semantic = ('percent' if metric in ('price.change_pct', 'price.change_pct_24h', 'oi.change_pct')
                else 'ratio' if metric in ('funding.rate_settled', 'orderbook.imbalance', 'taker.buy_ratio')
                else 'base' if metric in ('oi.quantity', 'trade.volume', 'trade.volume_24h', 'taker.buy_volume', 'taker.sell_volume')
                else 'quote')
    if semantic == 'base':
        return (row['instrument'] or {}).get('base')
    if semantic == 'quote':
        # resolveUnit 对 quote 固定返回 USDT（第 240 行另已断言报价币为 USDT）。
        return 'USDT'
    return semantic


def metric_errors(row, spec, response):
    errors, data = [], row['data']
    if not row.get('ok'):
        return ['DATA_ERROR: ' + str(row.get('error') or data.get('error') or '取数失败')]
    samples = data.get('samples')
    if samples is None:
        try:
            number(data.get('value'))
        except (ValueError, InvalidOperation):
            errors.append('DATA_ERROR: 未产出有效数值')
    else:
        if not samples or any(not isinstance(s, dict) or 'time' not in s or 'value' not in s for s in samples):
            errors.append('DATA_ERROR: 样本缺失或结构错误')
        else:
            try:
                for sample in samples:
                    number(sample['value'])
                times = [parse_time(s.get('time_iso') or s['time']) for s in samples]
                if times != sorted(set(times)):
                    errors.append('样本时间重复或未升序')
            except (ValueError, InvalidOperation, TypeError):
                errors.append('DATA_ERROR: 样本数值或时间无效')
    if data.get('statistics_omitted') or data.get('truncated') or data.get('coverage', {}).get('closed_part_complete') is False:
        errors.append('DATA_ERROR: 数据缺失/覆盖不完整，不能作为完整结果')
    if 'count' in spec and (data.get('requested_count') != spec['count'] or data.get('actual_count') != spec['count']
                            or data.get('sample_complete') is not True or len(samples or []) != spec['count']):
        errors.append('DATA_ERROR: 最近N期数量不匹配或未取齐')
    metric = row['metric']
    expected = expected_unit(row)
    if row['unit'] != expected:
        errors.append('指标单位不匹配，期望 ' + expected + '，实际 ' + str(row['unit']))
    if metric == 'oi.change_pct' and (data.get('sample_selection') != 'boundary_values'
                                     or not data.get('start_sample_at') or not data.get('end_sample_at')):
        errors.append('OI变化率缺少两端采样依据')
    if row['instrument'].get('quote') != 'USDT' or (row['market'] == 'spot' and 'settle' in row['instrument']):
        errors.append('市场/报价币/结算币错误')
    if 'interval' in spec:
        if data.get('candle_interval') != spec['interval']:
            errors.append('K线粒度不匹配')
        if samples and spec['interval'] == '5m':
            try:
                if any((parse_time(sample['time']) + timedelta(minutes=5)).timestamp() * 1000 > response['_startedMs'] + 2000 for sample in samples):
                    errors.append('包含请求时尚未完结的K线')
            except (KeyError, TypeError, ValueError):
                errors.append('无法核验K线完结时间')
    if row.get('metric') in ('price.close', 'price.open', 'price.high', 'price.low') and row.get('shape') == 'time_series' and not data.get('candle_interval'):
        errors.append('K线序列缺少 candle_interval')
    if row['market'] == 'spot' and row['exchange'] == 'binance' and row['metric'].startswith('orderbook.'):
        if data.get('as_of') is not None or not data.get('collected_at') or data.get('timestamp_source') != 'local_collection':
            errors.append('币安现货盘口时间来源错误')
    if row['shape'] in ('snapshot', 'official_24h') and not (row['market'] == 'spot' and row['exchange'] == 'binance' and metric.startswith('orderbook.')):
        try:
            parse_time(data.get('as_of'))
        except (TypeError, ValueError):
            errors.append('快照缺少有效的数据时间 as_of')
    if 'window' in spec:
        try:
            requested = data['requested_range']
            if requested.get('timezone') != 'Asia/Shanghai':
                errors.append('查询时区不是 Asia/Shanghai')
            start, end = parse_time(requested['start_inclusive']), parse_time(requested['end_exclusive'])
            expected_start, expected_end = window_bounds(spec['window'], response['_startedMs'], response['_absoluteDate'])
            if expected_start is None:
                end_ms = end.timestamp() * 1000
                if end - start != expected_end or not response['_startedMs'] - 2000 <= end_ms <= response['_endedMs'] + 2000:
                    errors.append('滚动窗口长度/请求截止点不匹配')
            elif start != expected_start or end != expected_end:
                errors.append('自然周期/指定区间边界不匹配')
        except (KeyError, ValueError, TypeError):
            errors.append('缺少或无法解析 requested_range')
    return errors


def resolve(value, nodes):
    if isinstance(value, str) and (match := REFERENCE.fullmatch(value)):
        parts = match[1].split('.')
        current = nodes[parts[0]]
        for part in parts[1:]:
            current = current[int(part)] if isinstance(current, list) else current[part]
        return current
    if isinstance(value, list):
        return [resolve(item, nodes) for item in value]
    if isinstance(value, dict):
        return {key: resolve(item, nodes) for key, item in value.items()}
    return value


def referenced_ids(value):
    if isinstance(value, str) and (match := REFERENCE.fullmatch(value)):
        return {match[1].split('.')[0]}
    if isinstance(value, (dict, list)):
        return set().union(*(referenced_ids(v) for v in (value.values() if isinstance(value, dict) else value)))
    return set()


def input_of(row):
    return (row.get('input_references') or {}).get('input', {})


def calculation_errors(row, all_rows):
    """用本次原始输入重算，不以算子输出自身的 left/right 等字段充当真值。"""
    op, data = row.get('operator'), row['data']
    nodes = {r['node_id']: {'data': r['data']} for r in all_rows}
    raw = input_of(row)
    if not referenced_ids(raw):
        return ['算子没有引用真实取数/计算节点']
    value = resolve(raw, nodes)
    with localcontext() as ctx:
        ctx.prec = 70
        if op in ('avg', 'sum', 'min', 'max'):
            if value.get('kind') == 'values':
                source = value['values']; values = [number(x) for x in source]
            else:
                source = value['collection']
                column = value.get('columns', []).index(value['field']) if 'columns' in value else None
                values = [number(x[column] if column is not None else x[value['field']]) for x in source]
            expected = {'sum': lambda: sum(values), 'avg': lambda: sum(values) / len(values),
                        'min': lambda: min(values), 'max': lambda: max(values)}[op]()
            if data.get('count') != len(values):
                return ['聚合元素个数错误']
            if op in ('min', 'max'):
                index = values.index(expected)
                if data.get('index') != index or (value.get('kind') == 'field' and data.get('item') != source[index]):
                    return ['极值索引/命中行错误']
        elif op in ('compare', 'difference', 'ratio'):
            left, right = number(value['left']), number(value['right'])
            if op == 'compare':
                expected = Decimal((left > right) - (left < right))
                if data.get('relation') != ('greater' if expected > 0 else 'less' if expected < 0 else 'equal'):
                    return ['compare 关系错误']
            else:
                expected = left - right if op == 'difference' else left / right
        elif op == 'relative_change':
            baseline = number(value['baseline'])
            expected = (number(value['current']) - baseline) / baseline
        elif op == 'annualize':
            if value.get('basis') != 'holding_return' or value.get('method') != 'simple' or value.get('rate_unit') != 'percent':
                return ['年化口径错误']
            seconds = number(value['period']['value']) * {'second': 1, 'minute': 60, 'hour': 3600, 'day': 86400}[value['period']['unit']]
            if value.get('year_days', 365) != 365:
                return ['年化不是365天基准']
            expected = number(value['rate']) / 100 * 365 * 86400 / seconds
        else:
            return ['未知算子 ' + str(op)]
        errors = [] if equal_number(data.get('value'), expected) else ['算子数值与独立重算不一致']
        if op in ('ratio', 'relative_change', 'annualize') and not equal_number(data.get('percent'), expected * 100):
            errors.append('算子 percent 与 value 不一致')
        return errors


def judge(case, response, trace):
    """纯函数，返回 verdict/notes；不做任何网络访问。"""
    errors = []
    if response.get('_error'):
        return 'FAIL', ['REQUEST_ERROR: ' + response['_error']]
    if trace.get('_error'):
        return 'FAIL', ['TRACE_ERROR: ' + trace['_error']]
    answer = response.get('answer') or ''
    if not answer.strip() or FALLBACK.search(answer):
        errors.append('回答为空或命中规划失败兜底')
    if response.get('busy') or response.get('type', 'query') != 'query':
        errors.append('请求忙碌或错误路由到非 query')
    if (response.get('planning') or {}).get('status') == 'NEEDS_CLARIFICATION' or response.get('clarify'):
        errors.append('单轮条件完整，不应要求补充信息')
    if (trace.get('trace') or {}).get('status') != 'SUCCESS':
        errors.append('trace 未成功完成')
    evidence = rows(response)
    plan = trace.get('final_plan')
    attempts = trace.get('planner_attempts') or []
    if case.reject:
        if evidence or plan is not None or response.get('toolCalls', 0) != 0:
            errors.append('能力边界请求产生了计划/取数，可能静默替换要求')
        if not attempts or attempts[-1].get('result') not in ('NO_PLAN', 'OUT_OF_DOMAIN'):
            errors.append('不是明确的能力拒绝/出域，不能把澄清或错误当作通过')
        if not any(word in answer for word in ('不支持', '未接入', '无法', '不能', '仅支持', '不提供', '尚未', '范围', '不属于')):
            errors.append('未说明能力限制')
        for alternatives in case.gaps:
            if not any(word.lower() in answer.lower() for word in alternatives):
                errors.append('缺少具体缺口说明：' + '/'.join(alternatives))
        return ('FAIL' if errors else 'PASS'), errors
    if not isinstance(plan, dict) or not plan.get('metrics'):
        errors.append('缺少最终可执行计划')
    matches = []
    for spec in case.metrics:
        candidates = [row for row in evidence if row.get('metric') and metric_matches(row, spec)]
        valid = [row for row in candidates if not metric_errors(row, spec, response)]
        matches.append(valid)
        if not valid:
            reason = [e for row in candidates for e in metric_errors(row, spec, response)]
            errors.append('缺少符合要求的指标 ' + str(spec) + ('；' + '；'.join(reason) if reason else ''))
    for row in evidence:
        if row.get('metric') and not any(metric_matches(row, spec) and not metric_errors(row, spec, response) for spec in case.metrics):
            # 指标确实存在但没通过校验时，报「指标不可接受」并带上原因；否则才是市场/指标多余。
            reason = next((e for spec in case.metrics if metric_matches(row, spec)
                           for e in metric_errors(row, spec, response)), None)
            errors.append(('指标存在但不满足用例要求：' if reason else '额外/错误市场的指标：')
                          + str((row['metric'], row['market'], row['exchange']))
                          + ('；' + reason if reason else ''))
        if row.get('reused'):
            errors.append('独立会话不应复用历史证据')
    calculations = [row for row in evidence if row.get('operator')]
    for op in set(case.operators):
        if sum(r.get('operator') == op and r.get('ok') for r in calculations) < case.operators.count(op):
            errors.append('缺少成功算子 ' + op)
    if case.no_calculations and calculations:
        errors.append('只要求原始指标，却产生额外计算')
    try:
        for row in calculations:
            if not row.get('ok'):
                errors.append('DATA_ERROR: 算子执行失败 ' + str(row.get('error')))
            else:
                errors.extend(row['operator'] + ': ' + e for e in calculation_errors(row, evidence))
        for op, slot, index in case.links:
            target_ids = {r['node_id'] for r in matches[index]}
            if not any(referenced_ids(input_of(r).get(slot)) == target_ids for r in calculations if r['operator'] == op):
                errors.append(op + '.' + slot + ' 未引用要求的指标/市场/时间分支')
        if case.id == 'C4':
            avgs = [r for r in calculations if r['operator'] == 'avg']
            avg_ids = set()
            for match in matches:
                targets = {r['node_id'] for r in match}
                hit = [r for r in avgs if referenced_ids(input_of(r).get('collection')) == targets]
                if not hit:
                    errors.append('每家交易所必须各自聚合')
                avg_ids.update(r['node_id'] for r in hit)
            if not any(referenced_ids(input_of(r)) == avg_ids and len(avg_ids) == 2 for r in calculations if r['operator'] == 'compare'):
                errors.append('compare 未引用两家均值')
        if case.id == 'D1':
            metrics = {r['node_id'] for match in matches for r in match}
            sums = [r for r in calculations if r['operator'] == 'sum' and referenced_ids(input_of(r)) == metrics]
            if not any(referenced_ids(input_of(r).get('right')) == {s['node_id']} for r in calculations if r['operator'] == 'ratio' for s in sums):
                errors.append('ratio 分母不是两所合计的 sum 结果')
        if case.id == 'C8':
            targets = {r['node_id'] for match in matches for r in match}
            for row in calculations:
                if row['operator'] == 'annualize':
                    period = input_of(row).get('period', {}).get('value')
                    if referenced_ids(period) != targets or not str(period).endswith('.data.observation_seconds}}') or input_of(row).get('period', {}).get('unit') != 'second':
                        errors.append('年化周期不是同一指标的实际观测秒数')
    except (KeyError, ValueError, TypeError, InvalidOperation, ArithmeticError) as exc:
        errors.append('计算输入/引用不可核验：' + str(exc))
    # 保留单位护栏，避免把真实行情幅度或合法高精度小数误判为程序错误。
    for row in evidence:
        if row.get('metric') == 'taker.buy_ratio' and row.get('ok') and row['data'].get('value') is not None:
            if not 0 <= number(row['data']['value']) <= 1:
                errors.append('主动买入占比超出0～1')
        if (row.get('metric') or '').startswith('trade.') and row['unit'] not in ('base', 'quote'):
            errors.append('成交量/成交额单位错误')
    if case.id in ('A7', 'E3') and all(matches):
        try:
            price, volume, amount = [number(match[0]['data']['value']) for match in matches]
            if price <= 0 or volume <= 0 or not price / 10 <= amount / volume <= price * 10:
                errors.append('成交额/成交量与现价量级矛盾，疑似单位或字段解析错误')
        except (ValueError, ArithmeticError):
            errors.append('成交额单位交叉检查缺少有效数值')
    return ('FAIL' if errors else 'PASS'), errors


class Client:
    def __init__(self, base, timeout=180):
        self.base, self.timeout = base.rstrip('/'), timeout

    def request(self, path, body=None):
        request = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(),
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            return json.loads(response.read(), parse_float=Decimal)

    def run_case(self, case):
        started = time.time_ns() // 1_000_000
        now = datetime.fromtimestamp(started / 1000, TZ)
        absolute_date = (now - timedelta(days=3)).date().isoformat()
        question = case.question.replace('{date}', absolute_date)
        conversation = str(uuid.uuid4())
        response, trace = {}, {}
        try:
            # 无 cookie 共享；每条显式使用独立ID，不继承其它用例的上下文。不自动重发 POST。
            response = self.request('/api/ask', dict(conversationId=conversation, parentTurnId='', question=question,
                                                    timezone='Asia/Shanghai', isRealUser=False))
            if not isinstance(response, dict):
                raise ValueError('ask 返回的不是 JSON 对象')
        except Exception as exc:
            response = {'_error': str(exc)}
        ended = time.time_ns() // 1_000_000
        response.update(_startedMs=started, _endedMs=ended, _absoluteDate=absolute_date)
        if response.get('traceId'):
            try:
                trace = self.request('/api/trace_detail/' + urllib.parse.quote(response['traceId'], safe=''))
            except Exception as exc:
                trace = {'_error': str(exc)}
        else:
            trace = {'_error': '响应没有 traceId'}
        try:
            verdict, notes = judge(case, response, trace)
        except Exception as exc:
            verdict, notes = 'FAIL', ['JUDGE_ERROR: ' + repr(exc)]
        return dict(id=case.id, question=question, expected=asdict(case), conversationId=conversation,
                    response=response, trace=trace, verdict=verdict, notes=notes,
                    durationSeconds=round((ended - started) / 1000, 3))

    def cache_stats(self, start, end):
        # ask_trace.created_at 是秒精度；扩展到完整秒，避免首条 ask 因截秒落在 start 之前。
        query_start = start // 1000 * 1000
        query_end = max(query_start + 1000, (end + 999) // 1000 * 1000)
        parameters = dict(startTime=query_start, endTime=query_end)
        try:
            data = self.request('/api/stats/cache?' + urllib.parse.urlencode(parameters))
            if not all(key in data for key in ('promptTokens', 'cachedTokens', 'cacheHitPercent', 'measuredCalls', 'unknownCalls')):
                raise ValueError('缓存统计接口返回字段不完整')
            return dict(parameters=parameters, data=data)
        except Exception as exc:
            return dict(parameters=parameters, error=str(exc))


def cache_summary(cache):
    scope = '全站时间窗口，包含同期其它请求；非本批次独占统计'
    if cache.get('error'):
        return '缓存统计不可用：' + cache['error'] + '（用例结果已保留）；' + scope
    data = cache['data']
    rate = data['cacheHitPercent']
    percent = '无有效用量，比例未知' if rate is None else str(rate) + '%'
    return ('缓存命中率：%s；cached=%s / prompt=%s；有效调用=%s，未知调用=%s；%s' %
            (percent, data['cachedTokens'], data['promptTokens'], data['measuredCalls'], data['unknownCalls'], scope))


def run_cases(client, cases, workers=1, clock_ms=lambda: time.time_ns() // 1_000_000):
    start = clock_ms()
    print('开始时间戳=%d' % start, flush=True)
    results = []
    interrupted = False
    try:
        if workers == 1:
            for case in cases:
                result = client.run_case(case)
                results.append(result)
                print_result(result)
        else:
            # 每个线程请求独立会话；按所选case顺序保存、打印，避免共享会话干扰。
            executor = ThreadPoolExecutor(max_workers=workers)
            futures = [executor.submit(client.run_case, case) for case in cases]
            try:
                for future in futures:
                    result = future.result()
                    results.append(result)
                    print_result(result)
            finally:
                # 中断时取消尚未开始的case；已经发送的请求等待完成再记录结束时间。
                executor.shutdown(wait=True, cancel_futures=True)
    except KeyboardInterrupt:
        interrupted = True
        print('执行被中断，将保存已收集结果。')
    finally:
        end = clock_ms()  # 所有已等待的请求结束后记录，缓存查询本身不计入运行时间。
    passed = sum(row['verdict'] == 'PASS' for row in results)
    print('用例完成：PASS %d / %d，FAIL %d' % (passed, len(results), len(results)-passed))
    cache = client.cache_stats(start, end)
    print('开始时间戳=%d，结束时间戳=%d' % (start, end))
    print(cache_summary(cache))
    return dict(suiteVersion=SUITE_VERSION, startTime=start, endTime=end, base=client.base,
                selectedCases=[c.id for c in cases], interrupted=interrupted, results=results, cache=cache)


def print_result(result):
    print('[%s] %s %.3fs %s' % (result['id'], result['verdict'], result['durationSeconds'], '；'.join(result['notes'])[:250]), flush=True)


def select_cases(cases, only=None, group=None):
    ids = {x.strip().upper() for x in (only or '').split(',') if x.strip()}
    groups = {x.strip().upper() for x in (group or '').split(',') if x.strip()}
    known = {c.id for c in cases}
    if ids - known:
        raise ValueError('不存在的用例：' + ','.join(sorted(ids - known)))
    if groups - set('ABCDEF'):
        raise ValueError('分组仅支持 A～F')
    return [c for c in cases if (not ids or c.id in ids) and (not groups or c.id[0] in groups)]


def backup_reports(md_path, raw_path):
    previous = None
    if raw_path.exists():
        try:
            previous = json.loads(raw_path.read_text(), parse_float=Decimal)
        except (ValueError, OSError):
            pass
    suffix = '.backup-' + datetime.now(TZ).strftime('%H%M%S') + '-' + uuid.uuid4().hex[:6]
    for path in (md_path, raw_path):
        if path.exists():
            shutil.copy2(path, path.with_name(path.stem + suffix + path.suffix))
    return previous


def verdict_changes(run, previous):
    if not previous or previous.get('suiteVersion') != SUITE_VERSION:
        return ['无同版本上一轮报告可对照。']
    old = {r['id']: r for r in previous.get('results', [])}
    changes = []
    for row in run['results']:
        before = old.get(row['id'])
        if before and before['verdict'] != row['verdict']:
            note = row['id'] + ': ' + before['verdict'] + ' → ' + row['verdict']
            if row['verdict'] == 'PASS':
                note += '（本轮计划/证据已校验，算子已独立重算；仍需人工复核答案语义与原始行情）'
            changes.append(note)
    return changes or ['共同用例的 verdict 无变化。']


def render(run, changes):
    results = run['results']
    passed = sum(r['verdict'] == 'PASS' for r in results)
    lines = ['# CEXPilot common 回归', '', '- 版本：' + SUITE_VERSION,
             '- 服务：' + run['base'],
             '- 执行状态：%s；计划%d条，收集%d条' % ('已中断' if run['interrupted'] else '已完成', len(run['selectedCases']), len(results)),
             '- 开始时间戳：' + str(run['startTime']), '- 结束时间戳：' + str(run['endTime']),
             '- PASS %d / %d；FAIL %d' % (passed, len(results), len(results)-passed),
             '- ' + cache_summary(run['cache']), '', '## 与上一轮比较', ''] + ['- '+s for s in changes]
    lines += ['', '## 用例结果', '', '| ID | 问题 | 判定 | 秒 | 说明 |', '| --- | --- | --- | --- | --- |']
    def cell(value):
        return str(value).replace('|', '\\|').replace('\n', ' ')
    for row in results:
        lines.append('| %s | %s | %s | %s | %s |' % tuple(cell(x) for x in
                     (row['id'], row['question'], row['verdict'], row['durationSeconds'], '；'.join(row['notes']))))
    for row in results:
        lines += ['', '## '+row['id'], '', row['question'], '', '- '+row['verdict']]
        if row['response'].get('traceId'):
            lines += ['- [trace]('+run['base']+'/trace.html?id='+urllib.parse.quote(row['response']['traceId'])+')']
        lines += [''] + ['- '+note for note in row['notes']]
        answer = row['response'].get('answer') or row['response'].get('_error') or ''
        lines += ['', '```text', str(answer).replace('```', '｀｀｀'), '```']
    return '\n'.join(lines)+'\n'


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    # 兼容已有 only:A1,E1 / group:E 写法；精确匹配ID，避免 E1 顺带命中 E10。
    args = [('--only='+x[5:] if x.startswith('only:') else '--group='+x[6:] if x.startswith('group:') else x) for x in args]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default=os.environ.get('CEXPILOT_BASE', 'https://cex.hrscrm.net'))
    parser.add_argument('--only'); parser.add_argument('--group')
    parser.add_argument('-w', '--workers', type=int, default=1)
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--list', action='store_true', help='只列用例，不访问服务、不生成报告')
    options = parser.parse_args(args)
    if not 1 <= options.workers <= 8 or options.timeout <= 0:
        parser.error('workers 必须为1～8，timeout 必须为正数')
    try:
        cases = select_cases(CASES, options.only, options.group)
    except ValueError as exc:
        parser.error(str(exc))
    if not cases:
        parser.error('没有匹配的用例')
    if options.list:
        for case in cases:
            print(case.id, case.question)
        return 0
    stamp = datetime.now(TZ).strftime('%Y-%m-%d')
    md_path, raw_path = HERE / ('common-'+stamp+'.md'), HERE / 'raw' / ('common-'+stamp+'.json')
    raw_path.parent.mkdir(parents=True, exist_ok=True)
    previous = backup_reports(md_path, raw_path)
    run = run_cases(Client(options.base, options.timeout), cases, options.workers)
    changes = verdict_changes(run, previous)
    run['verdictChanges'] = changes
    raw_path.write_text(json.dumps(run, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    md_path.write_text(render(run, changes), encoding='utf-8')
    for change in changes:
        print(change)
    print('报告：', md_path, '\n原始：', raw_path)
    return 1 if run['interrupted'] or any(r['verdict'] != 'PASS' for r in run['results']) else 0


if __name__ == '__main__':
    sys.exit(main())
