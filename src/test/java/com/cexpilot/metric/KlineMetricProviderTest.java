package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.kline.*;
import com.cexpilot.market.markprice.MarkPriceQueryService;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.oi.OiQueryService;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.market.taker.TakerVolumeQueryService;
import com.cexpilot.runtime.*;
import com.cexpilot.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 类型化 Provider + 假查询结果；期望值为手算常量，不再依赖对照实现。 */
class KlineMetricProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-09-28T00:00:00Z");
    private static final RequestContext CONTEXT = new RequestContext(ZoneOffset.UTC, START.plusSeconds(86400));
    private static final TimeRange RANGE = new TimeRange(START, START.plusSeconds(7200), ZoneOffset.UTC);
    /** 两根蜡烛的手算期望值：open 取首根、close 取末根、high/low 取极值、volume/turnover 为两根之和。 */
    private static final Map<KlineMetric,String> EXPECTED_STATISTICS = Map.of(KlineMetric.OPEN,"100",
            KlineMetric.CLOSE,"110",KlineMetric.HIGH,"112",KlineMetric.LOW,"98",KlineMetric.CHANGE_PCT,"10",
            KlineMetric.VOLUME,"5",KlineMetric.TURNOVER,"530");

    private static ObjectNode args() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"exchange":"binance","symbol":"BTC","interval":"1h",
                 "time":{"type":"calendar_period","unit":"day","offset":-1,"segment":"full","extent":"full_period"}}
                """);
    }
    private static TimeRangeQuery query(KlineMetric selector, String shape) throws Exception {
        return query(selector, shape, TimeSpecParser.parse(args().get("time")), "1h");
    }
    private static TimeRangeQuery query(KlineMetric selector, String shape, TimeSpec time, String interval) {
        var instrument=JSON.createObjectNode().put("market_type","perpetual").put("base","BTC").put("quote","USDT").put("settle","USDT");
        var binding=new MetricBinding("m1","test.metric","binance",shape,instrument,
                selector==KlineMetric.VOLUME?"BTC":selector==KlineMetric.CHANGE_PCT?"percent":"USDT",
                "kline",selector,"test-v1");
        return new TimeRangeQuery(binding,time,interval,false);
    }
    private static KlineQueryResult data(boolean complete, boolean quoteMissing) {
        var candles=List.of(
                new Candle(START.toEpochMilli(),new BigDecimal("100"),new BigDecimal("105"),new BigDecimal("98"),new BigDecimal("104"),new BigDecimal("2"),new BigDecimal("200"),true),
                new Candle(START.plusSeconds(3600).toEpochMilli(),new BigDecimal("104"),new BigDecimal("112"),new BigDecimal("103"),new BigDecimal("110"),new BigDecimal("3"),quoteMissing?null:new BigDecimal("330"),true));
        var request=new KlineQueryRequest(Exchange.BINANCE,"BTC",CandleInterval.parse("1h"),RANGE,false);
        var coverage=new SeriesCoverage(2,2,complete?List.of():List.of(START.toEpochMilli()),List.of(),List.of(),false,false,null,RANGE.endExclusive().toEpochMilli(),false);
        return new KlineQueryResult(request,request,candles,coverage);
    }
    private static KlineQueryService service(KlineQueryResult result) {
        var service=mock(KlineQueryService.class);
        when(service.query(any(),any(),any(),any(),anyString(),any(),anyBoolean())).thenReturn(result);
        return service;
    }

    @ParameterizedTest @EnumSource(KlineMetric.class)
    void scalarMetricsMatchExistingDomainStatisticsAndQualityFields(KlineMetric selector) throws Exception {
        var service = service(data(true, false));
        var q = query(selector, "range_statistic");
        var result = new KlineMetricProvider(service).query(q, CONTEXT);
        var scalar = assertInstanceOf(MetricResult.Scalar.class, result);
        assertEquals(new BigDecimal("7200.000"), scalar.observationSeconds());
        var actual = MetricResultJson.write(q.binding(), result);
        verify(service, times(1)).query(CONTEXT.userZone(), q.time(), CONTEXT.requestTime(), Exchange.BINANCE, "BTC",
                CandleInterval.parse(q.intervalCode()), false);
        assertEquals(0, new BigDecimal(EXPECTED_STATISTICS.get(selector)).compareTo(actual.get("value").decimalValue()));
        assertTrue(actual.at("/coverage/range_complete").asBoolean());
        assertEquals("2026-09-28 00:00:00", actual.at("/requested_range/start_inclusive").asText());
        assertEquals("2026-09-28 02:00:00", actual.at("/actual_range/end_exclusive").asText());
        assertEquals("kline", actual.at("/source/provider").asText());
        assertEquals(selector.name(), actual.at("/source/selector").asText());
        assertFalse(actual.has("statistics"));
    }

    @ParameterizedTest @CsvSource({"OPEN,100,104", "HIGH,105,112", "LOW,98,103", "CLOSE,104,110", "VOLUME,2,3"})
    void seriesUsesTypedCandlesWithoutExposingRawRows(KlineMetric selector, String first, String second) throws Exception {
        var service = service(data(true, false));
        var q = query(selector, "time_series");
        var result = new KlineMetricProvider(service).query(q, CONTEXT);
        var series = assertInstanceOf(MetricResult.Series.class, result);
        assertEquals(START, series.samples().get(0).time());
        var actual = MetricResultJson.write(q.binding(), result);
        assertEquals(0, new BigDecimal(first).compareTo(actual.path("samples").get(0).get("value").decimalValue()));
        assertEquals(0, new BigDecimal(second).compareTo(actual.path("samples").get(1).get("value").decimalValue()));
        assertTrue(actual.path("samples").get(0).has("time"));
        assertFalse(actual.has("candles"));
        assertFalse(actual.has("value"));
    }

    @Test void requestZoneDrivesWindowResolutionCoverageAndRendering() throws Exception {
        var zone = ZoneId.of("America/New_York");
        Instant requestTime = Instant.parse("2026-09-24T15:30:00Z");
        KlineSource source = new KlineSource() {
            public Exchange exchange() { return Exchange.BINANCE; }
            public SeriesCapability capability() { return new SeriesCapability(Set.of(CandleInterval.values()), 1500, 4); }
            public FetchResult fetch(KlineQueryRequest r) {
                long step = r.interval().duration().toMillis();
                var rows = new ArrayList<Candle>();
                for (long t = r.range().startInclusive().toEpochMilli();
                     t < r.range().endExclusive().toEpochMilli(); t += step) {
                    rows.add(new Candle(t, BigDecimal.TEN, BigDecimal.valueOf(11), BigDecimal.valueOf(9),
                            BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, true));
                }
                return new FetchResult(rows, null);
            }
        };
        var service = new KlineQueryService(List.of(source), new TimeRangeResolver(Clock.fixed(requestTime, ZoneOffset.UTC)));
        var time = JSON.readTree("{\"type\":\"relative_day_range\",\"timezone\":null,"
                + "\"start\":{\"day_offset\":0,\"time\":\"10:00:00\"},\"end\":{\"day_offset\":0,\"time\":\"11:00:00\"}}");
        var q = query(KlineMetric.CLOSE, "time_series", TimeSpecParser.parse(time), "5m");
        var rendered = MetricResultJson.write(q.binding(),
                new KlineMetricProvider(service).query(q, new RequestContext(zone, requestTime)));
        // 请求时区参与 TimeSpec 消解，结果的窗口渲染与序列时间戳也必须用它，不回落到 UTC。
        assertEquals("America/New_York", rendered.at("/requested_range/timezone").asText());
        assertEquals("2026-09-24 10:00:00", rendered.at("/requested_range/start_inclusive").asText());
        assertEquals("2026-09-24 11:00:00", rendered.at("/requested_range/end_exclusive").asText());
        assertEquals(12, rendered.at("/coverage/expected_count").asInt());
        assertEquals(12, rendered.path("samples").size());
        assertEquals("2026-09-24 10:00:00", rendered.at("/samples/0/time").asText());
    }

    @Test void incompleteRangeOmitsValueButKeepsSeriesAndBlocksCalculation() throws Exception {
        var provider=new KlineMetricProvider(service(data(false,false)));
        var scalar=query(KlineMetric.CLOSE,"range_statistic");
        var result=provider.query(scalar,CONTEXT);
        assertInstanceOf(MetricResult.Omitted.class,result);
        var json=MetricResultJson.write(scalar.binding(),result);
        assertFalse(json.has("value"));assertFalse(json.has("observation_seconds"));
        assertFalse(json.at("/coverage/range_complete").asBoolean());
        assertThrows(IllegalArgumentException.class,()->new com.cexpilot.calculation.AvgTool().validateSource(json));
        var series=assertInstanceOf(MetricResult.Series.class,provider.query(query(KlineMetric.CLOSE,"time_series"),CONTEXT));
        assertEquals(2,series.samples().size());
    }
    @Test void missingQuoteVolumeFailsOnlyTurnoverInsteadOfEstimating() throws Exception {
        var provider=new KlineMetricProvider(service(data(true,true)));
        assertThrows(IllegalArgumentException.class,()->provider.query(query(KlineMetric.TURNOVER,"range_statistic"),CONTEXT));
        assertInstanceOf(MetricResult.Scalar.class,provider.query(query(KlineMetric.CLOSE,"range_statistic"),CONTEXT));
    }
    @Test void unavailableSeriesSelectorsAreRejectedBeforeQuery() throws Exception {
        assertThrows(IllegalArgumentException.class,()->query(KlineMetric.TURNOVER,"time_series"));
        assertThrows(IllegalArgumentException.class,()->query(KlineMetric.CHANGE_PCT,"time_series"));
    }
    @Test void decimalPrecisionSurvivesTheOutputBoundary() throws Exception {
        var q=query(KlineMetric.CLOSE,"range_statistic");
        var original=(MetricResult.Scalar)new KlineMetricProvider(service(data(true,false))).query(q,CONTEXT);
        BigDecimal value=new BigDecimal("12345678901234567890.0000000000123400");
        var json=MetricResultJson.write(q.binding(),new MetricResult.Scalar(original.metadata(),value,original.observationSeconds(),original.actualRange()));
        assertEquals(value,json.get("value").decimalValue());
    }
    @Test void metadataReusesQueryModelsAndPreservesAlignmentAndQualityDetails() throws Exception {
        var original=data(true,false);
        var requestedRange=new TimeRange(START.plusSeconds(60),START.plusSeconds(7100),ZoneOffset.UTC);
        var requested=new KlineQueryRequest(Exchange.BINANCE,"BTC",original.effective().interval(),requestedRange,true);
        var coverage=new SeriesCoverage(2,2,List.of(),List.of(),List.of(),true,true,"page budget",START.plusSeconds(7100).toEpochMilli(),true);
        var result=new KlineQueryResult(requested,original.effective(),original.candles(),coverage);
        var q=query(KlineMetric.CLOSE,"time_series");
        q=new TimeRangeQuery(q.binding(),q.time(),null,true);
        var value=new KlineMetricProvider(service(result)).query(q,CONTEXT);
        var metadata=assertInstanceOf(MetricResult.SeriesMetadata.class,value.metadata());
        assertSame(coverage,metadata.coverage());assertSame(requestedRange,metadata.requestedRange());
        var json=MetricResultJson.write(q.binding(),value);
        assertTrue(json.has("effective_range"));assertEquals("automatic",json.path("interval_source").asText());
        assertTrue(json.at("/coverage/contains_unclosed").asBoolean());assertTrue(json.at("/coverage/dropped_unclosed").asBoolean());
        assertEquals("page budget",json.at("/coverage/abort_reason").asText());
    }
    @Test void contractStillRestrictsReferencesToValueAndSamples() throws Exception {
        var scalar=query(KlineMetric.CLOSE,"range_statistic").binding().outputSchema();
        assertNull(ToolOutputSchema.referenceError(scalar,".data.value"));
        assertNull(ToolOutputSchema.referenceError(scalar,".data.observation_seconds"));
        assertNotNull(ToolOutputSchema.referenceError(scalar,".data.statistics.close"));
        assertNotNull(ToolOutputSchema.referenceError(scalar,".data.coverage.range_complete"));
        var series=query(KlineMetric.CLOSE,"time_series").binding().outputSchema();
        assertNull(ToolOutputSchema.referenceError(series,".data.samples.0.value"));
        assertNotNull(ToolOutputSchema.referenceError(series,".data.value"));
    }
    @Test void startupRejectsMissingOrDuplicateProvider() {
        var catalog=new MetricCatalog(new DefaultResourceLoader());
        assertThrows(IllegalArgumentException.class,()->new MetricProviderRegistry(List.of(),catalog));
        var provider=new KlineMetricProvider(mock(KlineQueryService.class));
        assertThrows(IllegalStateException.class,()->new MetricProviderRegistry(List.of(provider,provider)));
        // 目录含 oi/mark_price/taker/ticker/orderbook/funding 域后，只注册 kline 也视为缺 Provider，启动拒绝
        assertThrows(IllegalArgumentException.class,()->new MetricProviderRegistry(List.of(provider),catalog));
        assertDoesNotThrow(()->new MetricProviderRegistry(List.of(provider,
                new OiMetricProvider(mock(OiQueryService.class), mock(MarketDataService.class)),
                new MarkPriceMetricProvider(mock(MarkPriceQueryService.class), mock(MarketDataService.class)),
                new TakerMetricProvider(mock(TakerVolumeQueryService.class)),
                new TickerMetricProvider(mock(MarketDataService.class)),
                new OrderBookMetricProvider(mock(MarketDataService.class)),
                new FundingMetricProvider(mock(com.cexpilot.market.funding.FundingQueryService.class))),catalog));
    }
}
