package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.market.*;
import com.cexpilot.market.kline.*;
import com.cexpilot.market.funding.FundingQueryService;
import com.cexpilot.market.model.Candle;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.market.tool.*;
import com.cexpilot.metric.*;
import com.cexpilot.prompt.PromptStore;
import com.cexpilot.runtime.*;
import com.cexpilot.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 新规划 + 真实 Provider/K 线查询服务/覆盖/统计 + 假数据源；旧 Tool 的直接调用围栏仍保留。 */
class ToolCapabilityMigrationTest {
    private static final ObjectMapper MAPPER=JSON;
    private static final Instant NOW=Instant.parse("2026-09-29T12:00:00Z");
    private static KlineSource source(Exchange exchange,boolean missing) {
        return new KlineSource() {
            public Exchange exchange(){return exchange;}
            public SeriesCapability capability(){return new SeriesCapability(Set.of(CandleInterval.values()),1500,4);}
            public FetchResult fetch(KlineQueryRequest query) {
                List<Candle> candles=new ArrayList<>();
                long start=query.range().startInclusive().toEpochMilli(),end=query.range().endExclusive().toEpochMilli();
                assertEquals(Instant.parse("2026-09-28T00:00:00Z").toEpochMilli(),start);
                assertEquals(Instant.parse("2026-09-29T00:00:00Z").toEpochMilli(),end);
                for(long time=start;time<end;time+=query.interval().duration().toMillis()) {
                    if(missing&&time==start)continue;
                    candles.add(new Candle(time,new BigDecimal("100"),new BigDecimal("103"),new BigDecimal("99"),new BigDecimal("102"),
                            BigDecimal.TEN,exchange==Exchange.BINANCE?new BigDecimal("1200"):new BigDecimal("1000"),true));
                }
                return new FetchResult(candles,null);
            }
        };
    }
    @Test void realKlineStatisticsSupportAllScalarMetricsWithCorrectUnits() {
        var market=mock(MarketDataService.class);
        var service=new KlineQueryService(List.of(source(Exchange.BINANCE,false),source(Exchange.OKX,false)),new TimeRangeResolver(Clock.fixed(NOW,ZoneOffset.UTC)));
        var registry=registry();
        var catalog=new MetricCatalog(LOADER);
        Map<String,String> expected=Map.of("price.open","100","price.close","102","price.high","103","price.low","99",
                "price.change_pct","2","trade.volume","240","trade.turnover","28800");
        var compiler=new MetricPlanCompiler(catalog,registry,providers());
        var executor=new DagExecutor(registry,new DagConfig(),providers(new KlineMetricProvider(service)));
        try {
            for(var entry:expected.entrySet()) {
                var physical=compiler.compile(plan(metric("m",entry.getKey(),"range_statistic","binance")),8);
                var result=executor.execute(physical,"test",e->{},new RequestContext(ZoneOffset.UTC,NOW)).context().get("metric_0");
                assertTrue(result.ok(),result.error());
                assertEquals(0,new BigDecimal(entry.getValue()).compareTo(result.data().path("value").decimalValue()));
                assertEquals(86400,result.data().path("observation_seconds").asInt());
                assertTrue(result.data().at("/coverage/range_complete").asBoolean());
            }
        } finally {executor.shutdown();}
        verifyNoInteractions(market);
    }
    @Test void realQueryPipelineProducesCrossExchangeResultAndBlocksCoverageGap() {
        for(boolean missing:List.of(false,true)) {
            var market=mock(MarketDataService.class);
            var service=new KlineQueryService(List.of(source(Exchange.BINANCE,false),source(Exchange.OKX,missing)),new TimeRangeResolver(Clock.fixed(NOW,ZoneOffset.UTC)));
            var registry=registry();
            var p=plan(metric("m1","trade.turnover","range_statistic","binance","okx"));
            calculation(p,"c1","relative_change","{\"current\":\"{{m1.binance.value}}\",\"baseline\":\"{{m1.okx.value}}\"}");
            var llm=new Script(envelope(p),"回答");var config=new DagConfig();var executor=new DagExecutor(registry,config,providers(new KlineMetricProvider(service)));
            try {
                var result=new DagRuntime(llm,planner(llm,registry,config),executor,new PromptStore(LOADER),Clock.fixed(NOW,ZoneOffset.UTC))
                        .execute("昨天两所成交额比较","","test",e->{},null,new RequestContext(ZoneOffset.UTC,NOW));
                var calculation=result.evidence().get(2);
                assertEquals(!missing,calculation.path("ok").asBoolean());
                if(!missing)assertEquals("20",calculation.at("/data/percent").asText());
                else assertFalse(result.evidence().get(1).path("data").has("value"));
                assertEquals(2,llm.calls.size());
            } finally {executor.shutdown();}
        }
    }
    @Test void realSeriesProjectionKeepsTimestampAndOnlySelectedMetric() {
        var service=new KlineQueryService(List.of(source(Exchange.BINANCE,false)),new TimeRangeResolver(Clock.fixed(NOW,ZoneOffset.UTC)));
        var market=mock(MarketDataService.class);var registry=registry();
        var compiler=new MetricPlanCompiler(new MetricCatalog(LOADER),registry,providers());var executor=new DagExecutor(registry,new DagConfig(),providers(new KlineMetricProvider(service)));
        try {
            for(String metric:List.of("price.open","price.close","price.high","price.low","trade.volume")) {
                var result=executor.execute(compiler.compile(plan(metric("m",metric,"time_series","binance")),8),"test",e->{},new RequestContext(ZoneOffset.UTC,NOW)).context().get("metric_0");
                assertTrue(result.ok(),result.error());assertEquals(24,result.data().path("samples").size());
                assertFalse(result.data().has("candles"));assertTrue(result.data().at("/samples/0/time").asText().contains("2026-09-28"));
            }
        } finally{executor.shutdown();}
    }
    @Test void oldToolsRemainRegisteredButCannotEnterNewPlanner() {
        var market=mock(MarketDataService.class);
        var registry=registry(new GetTickerTool(market),new GetFundingRateTool(market));
        assertNotNull(registry.get("get_ticker"));
        var llm=new Script("{\"in_domain\":true,\"plan\":{\"nodes\":[{\"id\":\"n1\",\"tool\":\"get_ticker\",\"args\":{\"symbol\":\"BTC\"}}]}}");
        var config=new DagConfig();config.setPlannerMaxRetries(0);
        assertTrue(planner(llm,registry,config).plan("当前价","","t",e->{}).plan().isEmpty());
        verifyNoInteractions(market);
    }
    @Test void missingProviderFailsStartupButOldKlineToolsAreNotRequired() {
        var catalog=new MetricCatalog(LOADER);
        assertDoesNotThrow(()->new MetricPlanCompiler(catalog,registry(),providers()));
        assertThrows(IllegalArgumentException.class,()->new MetricProviderRegistry(List.of(),catalog));
    }
    @Test
    void invalidCountsCannotBypassChecksViaDirectToolCalls() throws Exception {
        var market = mock(MarketDataService.class);
        var funding = mock(FundingQueryService.class);
        var recentTool = new GetRecentTradesTool(market);
        var fundingTool = new GetFundingRateHistoryTool(market, funding);
        for (String count : List.of("0", "-1", "101", "2147483648", "1.5", "\"50\"", "null")) {
            assertFalse(recentTool.execute(MAPPER.readTree("{\"symbol\":\"BTC\",\"exchange\":\"binance\",\"limit\":" + count + "}"), null).ok());
            assertFalse(fundingTool.execute(MAPPER.readTree("{\"symbol\":\"BTC\",\"exchange\":\"binance\",\"count\":" + count + "}"), null).ok());
        }
        for (String symbol : List.of("BTC-USDC", "BTC/USDC", "BTCUSDC", "BTC-USD-SWAP", "BTC-USDT-261225")) {
            assertFalse(recentTool.execute(MAPPER.createObjectNode().put("symbol", symbol).put("exchange", "binance"), null).ok());
        }
        // time/count 按字段是否提供互斥；无效 count 也不能被 time 模式静默忽略。
        assertFalse(fundingTool.execute(MAPPER.readTree("{\"symbol\":\"BTC\",\"exchange\":\"binance\",\"count\":0,\"time\":" + DAY + "}"), null).ok());
        verifyNoInteractions(market, funding);
    }


}
