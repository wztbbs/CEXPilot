package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.kline.*;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.market.tool.GetKlinesTool;
import com.cexpilot.market.tool.GetMarketStatisticsTool;
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

/** 类型化 Provider + 假查询结果；旧 Tool 仅作为兼容性对照，不进入新执行链。 */
class KlineMetricProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-09-28T00:00:00Z");
    private static final RequestContext CONTEXT = new RequestContext(ZoneOffset.UTC, START.plusSeconds(86400));
    private static final TimeRange RANGE = new TimeRange(START, START.plusSeconds(7200), ZoneOffset.UTC);
    private static final Map<KlineMetric,String> FIELDS = Map.of(KlineMetric.OPEN,"open",KlineMetric.CLOSE,"close",
            KlineMetric.HIGH,"high",KlineMetric.LOW,"low",KlineMetric.CHANGE_PCT,"change_pct",
            KlineMetric.VOLUME,"volume",KlineMetric.TURNOVER,"quote_volume");

    private static ObjectNode args() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"exchange":"binance","symbol":"BTC","interval":"1h",
                 "time":{"type":"calendar_period","unit":"day","offset":-1,"segment":"full","extent":"full_period"}}
                """);
    }
    private static MetricQuery query(KlineMetric selector, String shape) throws Exception {
        var instrument=JSON.createObjectNode().put("market_type","perpetual").put("base","BTC").put("quote","USDT").put("settle","USDT");
        var binding=new MetricBinding("m1","test.metric","binance",shape,instrument,
                selector==KlineMetric.VOLUME?"BTC":selector==KlineMetric.CHANGE_PCT?"percent":"USDT",
                "kline",selector,"test-v1");
        return new MetricQuery(binding,TimeSpecParser.parse(args().get("time")),CandleInterval.parse("1h"),false);
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
        var service=service(data(true,false));
        var q=query(selector,"range_statistic");
        var result=new KlineMetricProvider(service).query(q,CONTEXT);
        var scalar=assertInstanceOf(MetricResult.Scalar.class,result);
        assertEquals(new BigDecimal("7200.000"),scalar.observationSeconds());
        var actual=MetricResultJson.write(q.binding(),result);
        verify(service,times(1)).query(CONTEXT.userZone(),q.time(),CONTEXT.requestTime(),Exchange.BINANCE,"BTC",q.interval(),false);
        var old=new GetMarketStatisticsTool(mock(MarketDataService.class),service).execute(args(),new ToolContext("test",null,CONTEXT.userZone(),CONTEXT.requestTime()));
        assertTrue(old.ok(),old.error());
        assertEquals(old.data().path("statistics").get(FIELDS.get(selector)),actual.get("value"));
        assertEquals(old.data().get("coverage"),actual.get("coverage"));
        assertEquals(old.data().get("requested_range"),actual.get("requested_range"));
        assertEquals(old.data().at("/statistics/actual_range"),actual.get("actual_range"));
        assertEquals("kline",actual.at("/source/provider").asText());
        assertFalse(actual.path("source").has("tool"));
        assertFalse(actual.has("statistics"));
    }

    @ParameterizedTest @CsvSource({"OPEN,1","HIGH,2","LOW,3","CLOSE,4","VOLUME,5"})
    void seriesUsesTypedCandlesAndMatchesLegacyProjection(KlineMetric selector,int column) throws Exception {
        var service=service(data(true,false));var q=query(selector,"time_series");
        var result=new KlineMetricProvider(service).query(q,CONTEXT);
        var series=assertInstanceOf(MetricResult.Series.class,result);
        assertEquals(START,series.samples().get(0).time());
        var actual=MetricResultJson.write(q.binding(),result);
        var old=new GetKlinesTool(mock(MarketDataService.class),service).execute(args(),new ToolContext("t",null,CONTEXT.userZone(),CONTEXT.requestTime()));
        for(int i=0;i<2;i++) {
            assertEquals(old.data().path("candles").get(i).get(column),actual.path("samples").get(i).get("value"));
            assertEquals(old.data().path("candles").get(i).get(0),actual.path("samples").get(i).get("time"));
        }
        assertFalse(actual.has("candles"));assertFalse(actual.has("value"));
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
        q=new MetricQuery(q.binding(),q.time(),null,true);
        var value=new KlineMetricProvider(service(result)).query(q,CONTEXT);
        assertSame(coverage,value.metadata().coverage());assertSame(requestedRange,value.metadata().requestedRange());
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
        assertDoesNotThrow(()->new MetricProviderRegistry(List.of(provider),catalog));
    }
}
