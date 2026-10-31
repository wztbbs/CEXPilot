"""纯离线验证：所有网络入口默认封锁；不会运行线上脚本 main。"""
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

PATH = Path(__file__).resolve().parents[1] / 'run_regression.py'
spec = importlib.util.spec_from_file_location('common_regression', PATH)
r = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = r
spec.loader.exec_module(r)


def metric(node='m', name='price.last', value=100, market='perpetual', exchange='binance', shape='snapshot', **data):
    unit = 'ratio' if name == 'funding.rate_settled' else 'quote'
    return dict(node_id=node, type='metric', ok=True,
                identity=dict(metric=name, exchange=exchange, query_shape=shape, unit=unit,
                              instrument=dict(market_type=market, base='BTC', quote='USDT')),
                data=dict(value=value, as_of='2026-10-30 00:00:00', **data))


def calc(node, op, inputs, value, **data):
    return dict(node_id=node, type='calculation', operator=op, ok=True,
                input_references=dict(input=inputs), data=dict(value=str(value), **data))


def response(evidence=(), answer='本次查询结果', **extra):
    return dict(evidence=list(evidence), answer=answer, type='query', toolCalls=len(evidence),
                _startedMs=1793404800000, _endedMs=1793404801000, _absoluteDate='2026-10-28', **extra)


def trace(plan=None):
    return dict(trace=dict(status='SUCCESS'), final_plan=plan or dict(metrics=[{}]))


class OfflineTest(unittest.TestCase):
    def setUp(self):
        self.network = patch.object(r.urllib.request, 'urlopen', side_effect=AssertionError('离线测试禁止访问网络'))
        self.network.start()
        self.addCleanup(self.network.stop)

    def test_suite_has_exactly_40_cases_with_agreed_groups_and_all_operators(self):
        self.assertEqual(len(r.CASES), 40)
        self.assertEqual(len({c.id for c in r.CASES}), 40)
        self.assertEqual({g: sum(c.id.startswith(g) for c in r.CASES) for g in 'ABCDEF'},
                         dict(A=7, B=6, C=8, D=1, E=10, F=8))
        self.assertEqual({op for c in r.CASES for op in c.operators},
                         {'sum', 'avg', 'min', 'max', 'compare', 'difference', 'ratio', 'relative_change', 'annualize'})
        self.assertFalse(any('监控' in c.question for c in r.CASES))

    def test_filter_is_exact_and_never_runs_hidden_cases(self):
        self.assertEqual([c.id for c in r.select_cases(r.CASES, 'E1')], ['E1'])
        self.assertEqual(len(r.select_cases(r.CASES, group='E')), 10)
        self.assertEqual(r.select_cases(r.CASES, 'E1', 'A'), [])
        with self.assertRaises(ValueError):
            r.select_cases(r.CASES, 'E100')

    def test_spot_cannot_pass_with_perpetual_evidence(self):
        case = next(c for c in r.CASES if c.id == 'E1')
        self.assertEqual(r.judge(case, response([metric(market='spot')]), trace())[0], 'PASS')
        self.assertEqual(r.judge(case, response([metric()]), trace())[0], 'FAIL')
        self.assertEqual(r.judge(case, response([metric(market='spot', value=None)]), trace())[0], 'FAIL')

    def test_zero_is_valid_and_calculations_accept_decimal_strings(self):
        case = r.Case('X', 'q', (r.M('price.last'),), ('difference',))
        data = [metric(value=0), calc('c', 'difference', dict(left='{{m.data.value}}', right=0), '0')]
        self.assertEqual(r.judge(case, response(data), trace())[0], 'PASS')
        data[-1]['data']['value'] = '9'
        self.assertEqual(r.judge(case, response(data), trace())[0], 'FAIL')

    def test_reference_direction_is_checked_even_when_wrong_direction_math_is_correct(self):
        case = next(c for c in r.CASES if c.id == 'E10')
        values = [metric('p', value=110), metric('s', value=100, market='spot')]
        wrong = calc('c', 'difference', dict(left='{{s.data.value}}', right='{{p.data.value}}'), '-10')
        good = calc('c', 'difference', dict(left='{{p.data.value}}', right='{{s.data.value}}'), '10')
        self.assertEqual(r.judge(case, response(values+[wrong]), trace())[0], 'FAIL')
        self.assertEqual(r.judge(case, response(values+[good]), trace())[0], 'PASS')

    def test_all_aggregate_operators_are_recomputed_from_input_and_extreme_item_checked(self):
        m = metric(value=None, samples=[dict(time='2026-10-30 00:00:00', value=Decimal('0.00000000001')),
                                        dict(time='2026-10-30 08:00:00', value=Decimal('0.00000000003'))])
        inputs = dict(kind='field', collection='{{m.data.samples}}', field='value')
        for op, value in [('sum', '0.00000000004'), ('avg', '0.00000000002'), ('min', '0.00000000001'), ('max', '0.00000000003')]:
            extra = dict(count=2)
            if op in ('min', 'max'):
                index = 0 if op == 'min' else 1
                extra.update(index=index, item=m['data']['samples'][index])
            row = calc('c', op, inputs, value, **extra)
            self.assertEqual(r.calculation_errors(r.rows(response([row]))[0], r.rows(response([m, row]))), [])
            row['data']['value'] = '100'
            self.assertTrue(r.calculation_errors(r.rows(response([row]))[0], r.rows(response([m, row]))))

    def test_binary_operators_and_annualization_recompute(self):
        m = metric(value=2)
        tests = [('compare', dict(left='{{m.data.value}}', right=1), '1', dict(relation='greater')),
                 ('ratio', dict(left='{{m.data.value}}', right=4), '.5', dict(percent='50')),
                 ('relative_change', dict(current='{{m.data.value}}', baseline=4), '-.5', dict(percent='-50')),
                 ('annualize', dict(basis='holding_return', method='simple', rate='{{m.data.value}}', rate_unit='percent',
                                    period=dict(value=1, unit='day')), '7.3', dict(percent='730'))]
        for op, inputs, value, extra in tests:
            row = calc('c', op, inputs, value, **extra)
            self.assertEqual(r.calculation_errors(r.rows(response([row]))[0], r.rows(response([m, row]))), [])

    def test_compound_dag_requires_sum_as_denominator(self):
        # 用独立窗口规格省去本测试无关的时间元数据。
        case = r.Case('D1', 'q', (r.M('price.last'), r.M('price.last', 'okx')), ('sum', 'ratio'), (('ratio', 'left', 0),))
        values = [metric('b', value=30), metric('o', value=70, exchange='okx')]
        total = calc('s', 'sum', dict(kind='values', values=['{{b.data.value}}', '{{o.data.value}}']), 100, count=2)
        good = calc('r', 'ratio', dict(left='{{b.data.value}}', right='{{s.data.value}}'), '.3', percent='30')
        wrong = calc('r', 'ratio', dict(left='{{b.data.value}}', right=100), '.3', percent='30')
        self.assertEqual(r.judge(case, response(values+[total, good]), trace())[0], 'PASS')
        self.assertEqual(r.judge(case, response(values+[total, wrong]), trace())[0], 'FAIL')

    def test_two_exchange_means_must_feed_comparison_from_different_branches(self):
        case = next(c for c in r.CASES if c.id == 'C4')
        def rates(node, exchange, values):
            return metric(node, 'funding.rate_settled', None, exchange=exchange, shape='recent_n',
                          samples=[dict(time='2026-10-30 %02d:00:00' % (i*8), value=value) for i,value in enumerate(values)],
                          requested_count=3, actual_count=3, sample_complete=True)
        b = rates('b', 'binance', [1, 2, 3]);o = rates('o', 'okx', [4, 5, 6])
        avg1 = calc('a1', 'avg', dict(kind='field', collection='{{b.data.samples}}', field='value'), 2, count=3)
        avg2 = calc('a2', 'avg', dict(kind='field', collection='{{o.data.samples}}', field='value'), 5, count=3)
        compare = calc('c', 'compare', dict(left='{{a1.data.value}}', right='{{a2.data.value}}'), -1, relation='less')
        self.assertEqual(r.judge(case, response([b, o, avg1, avg2, compare]), trace())[0], 'PASS')
        avg2['input_references']['input']['collection'] = '{{b.data.samples}}'
        avg2['data']['value'] = '2';compare['data'].update(value='0', relation='equal')
        self.assertEqual(r.judge(case, response([b, o, avg1, avg2, compare]), trace())[0], 'FAIL')

    def test_annualization_must_use_same_observation_seconds_not_an_equal_constant(self):
        case = next(c for c in r.CASES if c.id == 'C8')
        m = metric(name='price.change_pct', value=2, shape='range_statistic', observation_seconds=86400,
                   requested_range=dict(start_inclusive='2026-10-30 00:00:00', end_exclusive='2026-10-31 00:00:00', timezone='Asia/Shanghai'))
        m['identity']['unit'] = 'percent'
        a = calc('c', 'annualize', dict(basis='holding_return', method='simple', rate='{{m.data.value}}', rate_unit='percent',
                                       period=dict(value='{{m.data.observation_seconds}}', unit='second')), '7.3', percent='730')
        d = response([m,a]);d['_startedMs'] = int(datetime(2026,10,31,10,tzinfo=r.TZ).timestamp()*1000)
        self.assertEqual(r.judge(case, d, trace())[0], 'PASS')
        a['input_references']['input']['period']['value'] = 86400
        self.assertEqual(r.judge(case, d, trace())[0], 'FAIL')

    def test_boundary_requires_reason_not_empty_plan_or_generic_failure(self):
        case = next(c for c in r.CASES if c.id == 'F1')
        t = dict(trace=dict(status='SUCCESS'), final_plan=None, planner_attempts=[dict(result='NO_PLAN')])
        d = response(answer='现货不支持资金费率查询。')
        self.assertEqual(r.judge(case, d, t)[0], 'PASS')
        d['answer'] = '暂时无法可靠生成查询计划，本次未执行查询。'
        self.assertEqual(r.judge(case, d, t)[0], 'FAIL')
        d['answer'] = '请补充信息'
        t['planner_attempts'][0]['result'] = 'NEEDS_CLARIFICATION'
        self.assertEqual(r.judge(case, d, t)[0], 'FAIL')

    def test_window_checks_natural_day_rolling_and_incomplete_data(self):
        now = datetime(2026, 10, 31, 10, 0, tzinfo=r.TZ)
        ms = int(now.timestamp()*1000)
        row = metric(name='price.close', value=100, shape='range_statistic',
                     requested_range=dict(start_inclusive='2026-10-30 00:00:00', end_exclusive='2026-10-31 00:00:00', timezone='Asia/Shanghai'))
        d = response([row]);d['_startedMs'] = ms;d['_endedMs'] = ms+1000
        spec = r.M('price.close', shape='range_statistic', window='yesterday')
        self.assertEqual(r.metric_errors(r.rows(d)[0], spec, d), [])
        row['data']['requested_range']['start_inclusive'] = '2026-10-30 01:00:00'
        self.assertTrue(r.metric_errors(r.rows(response([row]))[0], spec, d))
        row['data']['requested_range'].update(start_inclusive='2026-10-31 04:00:00', end_exclusive='2026-10-31 10:00:00')
        spec['window'] = 'rolling6h'
        self.assertEqual(r.metric_errors(r.rows(response([row]))[0], spec, d), [])
        row['data']['coverage'] = dict(closed_part_complete=False)
        self.assertTrue(r.metric_errors(r.rows(response([row]))[0], spec, d))

    def test_non_kline_series_need_no_candle_interval(self):
        m = metric(name='oi.quantity', value=None, shape='time_series', samples=[dict(time='2026-10-30 00:00:00', value=1)])
        m['identity']['unit'] = 'base'
        d = response([m])
        self.assertEqual(r.metric_errors(r.rows(d)[0], r.M('oi.quantity', shape='time_series'), d), [])

    def test_binance_spot_orderbook_requires_collection_time_without_asof(self):
        m = metric(name='orderbook.spread', market='spot', collected_at='2026-10-30 00:00:00', timestamp_source='local_collection')
        m['data'].pop('as_of')
        d = response([m]);spec = r.M('orderbook.spread', market='spot')
        self.assertEqual(r.metric_errors(r.rows(d)[0], spec, d), [])
        m['data']['as_of'] = '2026-10-30 00:00:00'
        self.assertTrue(r.metric_errors(r.rows(response([m]))[0], spec, d))

    def test_spot_ticker_units_use_price_as_independent_magnitude_anchor(self):
        case = next(c for c in r.CASES if c.id == 'E3')
        values = [metric('p', market='spot', exchange='okx', value=60000),
                  metric('v', name='trade.volume_24h', market='spot', exchange='okx', shape='official_24h', value=100),
                  metric('t', name='trade.turnover_24h', market='spot', exchange='okx', shape='official_24h', value=6000000)]
        values[1]['identity']['unit'] = 'base'
        self.assertEqual(r.judge(case, response(values), trace())[0], 'PASS')
        values[1]['data']['value'] = 6000000
        values[2]['data']['value'] = 100
        self.assertEqual(r.judge(case, response(values), trace())[0], 'FAIL')

    def test_client_marks_script_uses_fresh_conversations_and_no_post_retry(self):
        client = r.Client('http://fake')
        bodies = []
        def fake(path, body=None):
            bodies.append(body)
            raise TimeoutError('timeout')
        client.request = fake
        for case in r.CASES[:2]:
            self.assertEqual(client.run_case(case)['verdict'], 'FAIL')
        self.assertEqual(len(bodies), 2)
        self.assertNotEqual(bodies[0]['conversationId'], bodies[1]['conversationId'])
        self.assertFalse(bodies[0]['isRealUser'])
        self.assertEqual(bodies[0]['timezone'], 'Asia/Shanghai')
        self.assertEqual(bodies[0]['parentTurnId'], '')

    def test_runner_records_timestamps_and_queries_cache_once_after_all_cases(self):
        events = []
        class FakeClient:
            base = 'http://fake'
            def run_case(self, case):
                events.append(case.id)
                return dict(id=case.id, verdict='FAIL', notes=['synthetic'], durationSeconds=0)
            def cache_stats(self, start, end):
                events.append((start, end))
                return dict(data=dict(cacheHitPercent=Decimal('88'), promptTokens=100, cachedTokens=88, measuredCalls=1, unknownCalls=0))
        ticks = iter([1234, 5678])
        with contextlib.redirect_stdout(io.StringIO()) as output:
            run = r.run_cases(FakeClient(), r.CASES[:2], clock_ms=lambda: next(ticks))
        self.assertEqual(events, ['A1', 'A2', (1234, 5678)])
        self.assertEqual((run['startTime'], run['endTime']), (1234, 5678))
        self.assertIn('88%', output.getvalue())
        self.assertIn('全站时间窗口', output.getvalue())

    def test_cache_range_handles_second_precision_and_api_failure_is_not_zero(self):
        client = r.Client('http://fake')
        paths = []
        def fake(path, body=None):
            paths.append(path)
            return dict(cacheHitPercent=None, promptTokens=0, cachedTokens=0, measuredCalls=0, unknownCalls=3)
        client.request = fake
        cache = client.cache_stats(1234, 5678)
        self.assertEqual(cache['parameters'], dict(startTime=1000, endTime=6000))
        self.assertEqual(client.cache_stats(1000, 2000)['parameters']['endTime'], 2000)
        self.assertIn('startTime=1000', paths[0])
        self.assertIn('比例未知', r.cache_summary(cache))
        client.request = lambda *args: (_ for _ in ()).throw(ValueError('404 endpoint not deployed'))
        self.assertIn('不可用', r.cache_summary(client.cache_stats(1234, 5678)))

    def test_partial_report_and_cache_error_are_preserved_in_markdown_and_json(self):
        run = dict(suiteVersion=r.SUITE_VERSION, base='http://fake', startTime=1234, endTime=5678,
                   selectedCases=['A1','A2'], interrupted=True, cache=dict(error='endpoint unavailable'),
                   results=[dict(id='A1', question='问题', verdict='FAIL', notes=['synthetic'],
                                 durationSeconds=0, response=dict(answer='测试答案'))])
        md = r.render(run, ['无上一轮'])
        self.assertIn('已中断；计划2条，收集1条', md)
        self.assertIn('缓存统计不可用', md)
        restored = json.loads(json.dumps(run, ensure_ascii=False, default=str))
        self.assertEqual(restored['startTime'], 1234)
        self.assertEqual(restored['cache']['error'], 'endpoint unavailable')

    def test_backup_copies_both_artifacts_and_comparison_only_uses_same_suite(self):
        with tempfile.TemporaryDirectory() as directory:
            md, raw = Path(directory)/'a.md', Path(directory)/'a.json'
            old = dict(suiteVersion=r.SUITE_VERSION, results=[dict(id='A1', verdict='FAIL')])
            md.write_text('original');raw.write_text(json.dumps(old))
            self.assertEqual(r.backup_reports(md, raw), old)
            self.assertEqual(md.read_text(), 'original')
            self.assertEqual(len(list(Path(directory).glob('*.backup-*'))), 2)
            self.assertIn('FAIL → PASS', r.verdict_changes(dict(results=[dict(id='A1', verdict='PASS')]), old)[0])


if __name__ == '__main__':
    unittest.main()
