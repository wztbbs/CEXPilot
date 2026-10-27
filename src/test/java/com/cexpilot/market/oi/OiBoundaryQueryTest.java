package com.cexpilot.market.oi;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OiPoint;
import com.cexpilot.market.series.SeriesCapability;
import com.cexpilot.metric.*;
import com.cexpilot.runtime.RequestContext;
import com.cexpilot.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 真 Source 参数映射/过滤 + Service + Provider；数据由假端点返回，不访问交易所。 */
class OiBoundaryQueryTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Instant START=Instant.parse("2026-10-27T13:00:00Z"), END=START.plusSeconds(3600);
    private static final TimeSpec FIXED=new TimeSpec.AbsoluteRange(ZoneOffset.UTC,
            new TimeSpec.AbsolutePoint(2026,10,27,LocalTime.of(13,0)),
            new TimeSpec.AbsolutePoint(2026,10,27,LocalTime.of(14,0)),TimeSpec.EndMode.EXCLUSIVE);

    private List<OiPoint> samples(boolean missingEnd) {
        var points=new ArrayList<OiPoint>();
        for(int i=0;i<=(missingEnd?11:12);i++) points.add(new OiPoint(START.plusSeconds(i*300L).toEpochMilli(),
                new BigDecimal(i==0?"100":i==12?"120":"110")));
        points.add(new OiPoint(END.plusSeconds(300).toEpochMilli(),new BigDecimal("999")));
        return points;
    }
    private OiSource source(Exchange exchange,List<OiPoint> data) {
        return exchange==Exchange.BINANCE
                ? new BinanceOiSource((symbol,period,start,end,limit)->data.stream()
                    .filter(p->p.timestamp()>=start && p.timestamp()<=end).limit(limit).toList())
                : new OkxOiSource((inst,period,begin,end,limit)->data.stream()
                    .filter(p->p.timestamp()>begin && p.timestamp()<end).limit(limit).toList());
    }
    private OiQueryService service(OiSource source) {
        return new OiQueryService(List.of(source),new TimeRangeResolver(Clock.fixed(END,ZoneOffset.UTC)));
    }
    private MetricResult result(OiSource source,OiMetric selector,TimeSpec time,Instant now,String interval) {
        var instrument=JSON.createObjectNode().put("market_type","perpetual").put("base","BTC").put("quote","USDT").put("settle","USDT");
        var binding=new MetricBinding("m","oi.test",source.exchange().displayName(),
                selector==OiMetric.QUANTITY?"time_series":"range_statistic",instrument,
                selector==OiMetric.CHANGE_PCT?"percent":"BTC","oi",selector,"test");
        return new OiMetricProvider(service(source),mock(MarketDataService.class))
                .query(new TimeRangeQuery(binding,time,interval,false),new RequestContext(ZoneOffset.UTC,now));
    }
    @ParameterizedTest @EnumSource(Exchange.class)
    void endpointStatisticIncludesRightBoundaryWhileSequenceAndExtremaExcludeIt(Exchange exchange) {
        var source=source(exchange,samples(false));
        var change=assertInstanceOf(MetricResult.SampledScalar.class,result(source,OiMetric.CHANGE_PCT,FIXED,END,"5m"));
        assertEquals(0,new BigDecimal("20").compareTo(change.value())); // 100→120，而不是100→110
        assertEquals(START,change.startSampleAt()); assertEquals(END,change.endSampleAt());
        assertEquals(0,new BigDecimal("3600").compareTo(change.observationSeconds()));
        assertEquals(13,change.metadata().coverage().expectedCount());
        assertFalse(change.metadata().coverage().rangeBeyondNow()); // 物理 +1ms 不造成未来窗口
        var series=assertInstanceOf(MetricResult.Series.class,result(source,OiMetric.QUANTITY,FIXED,END,"5m"));
        assertEquals(12,series.samples().size()); assertEquals(END.minusSeconds(300),series.samples().get(11).time());
        var max=assertInstanceOf(MetricResult.SampledScalar.class,result(source,OiMetric.MAX,FIXED,END,"5m"));
        assertEquals(0,new BigDecimal("110").compareTo(max.value()));
        var end=assertInstanceOf(MetricResult.SampledScalar.class,result(source,OiMetric.END,FIXED,END,"5m"));
        assertEquals(0,new BigDecimal("120").compareTo(end.value()));
        assertEquals(END,end.startSampleAt()); assertEquals(END,end.endSampleAt());
    }
    @ParameterizedTest @EnumSource(Exchange.class)
    void missingEndpointIsIncompleteAndNeverUses1355As1400(Exchange exchange) {
        var source=source(exchange,samples(true));
        var omitted=assertInstanceOf(MetricResult.Omitted.class,result(source,OiMetric.CHANGE_PCT,FIXED,END,"5m"));
        var coverage=((MetricResult.SeriesMetadata)omitted.metadata()).coverage();
        assertFalse(coverage.rangeComplete()); assertEquals(List.of(END.toEpochMilli()),coverage.missing());
        assertInstanceOf(MetricResult.Series.class,result(source,OiMetric.QUANTITY,FIXED,END,"5m"));
    }
    @Test void rollingWindowMovesAsAWholeAndDefaultsToFineGrain() {
        var rolling=new TimeSpec.RollingWindow(ZoneOffset.UTC,new TimeSpec.FixedDuration(1,TimeSpec.RollingUnit.HOUR));
        var scalar=assertInstanceOf(MetricResult.SampledScalar.class,result(source(Exchange.BINANCE,samples(false)),
                OiMetric.CHANGE_PCT,rolling,END.plusSeconds(183),null));
        assertEquals(START,scalar.startSampleAt()); assertEquals(END,scalar.endSampleAt());
        assertEquals("5m",scalar.metadata().interval()); assertTrue(scalar.metadata().automaticInterval());
        assertEquals(END.plusSeconds(183),scalar.metadata().requestedRange().endExclusive());
    }
    @Test void explicitCoarseGrainCanUseExactlyTwoEndpoints() {
        var scalar=assertInstanceOf(MetricResult.SampledScalar.class,result(source(Exchange.BINANCE,
                List.of(new OiPoint(START.toEpochMilli(),BigDecimal.valueOf(100)),new OiPoint(END.toEpochMilli(),BigDecimal.valueOf(120)))),
                OiMetric.CHANGE_PCT,FIXED,END,"1h"));
        assertEquals(0,new BigDecimal("20").compareTo(scalar.value()));
        assertEquals("1h",scalar.metadata().interval()); assertEquals(2,scalar.metadata().coverage().expectedCount());
    }
    @Test void explicitUnalignedWindowAndRollingWindowTooShortForExplicitGrainAreNotExpanded() {
        var shifted=new TimeSpec.AbsoluteRange(ZoneOffset.UTC,new TimeSpec.AbsolutePoint(2026,10,27,LocalTime.of(13,2)),
                new TimeSpec.AbsolutePoint(2026,10,27,LocalTime.of(14,2)),TimeSpec.EndMode.EXCLUSIVE);
        var source=source(Exchange.BINANCE,samples(false));
        var omitted=assertInstanceOf(MetricResult.Omitted.class,result(source,OiMetric.CHANGE_PCT,shifted,END.plusSeconds(180),"5m"));
        assertFalse(((MetricResult.SeriesMetadata)omitted.metadata()).coverage().rangeComplete());
        var halfHour=new TimeSpec.RollingWindow(ZoneOffset.UTC,new TimeSpec.FixedDuration(30,TimeSpec.RollingUnit.MINUTE));
        assertInstanceOf(MetricResult.Omitted.class,result(source,OiMetric.CHANGE_PCT,halfHour,END,"1h"));
    }
    @Test void extraBoundaryCountsAgainstFetchBudgetAndAutomaticGrainCanCoarsen() {
        OiSource limited=new OiSource() {
            public Exchange exchange() { return Exchange.BINANCE; }
            public SeriesCapability capability() { return new SeriesCapability(Set.of(OiInterval.values()),12,1); }
            public FetchResult fetch(String base,OiInterval interval,long start,long end) {
                var data=new ArrayList<OiPoint>();
                for(long t=start;t<end;t+=interval.duration().toMillis()) data.add(new OiPoint(t,BigDecimal.ONE));
                return new FetchResult(data,null);
            }
        };
        assertThrows(IllegalArgumentException.class,()->result(limited,OiMetric.CHANGE,FIXED,END,"5m"));
        var coarser=assertInstanceOf(MetricResult.SampledScalar.class,result(limited,OiMetric.CHANGE,FIXED,END,null));
        assertEquals("15m",coarser.metadata().interval()); assertEquals(5,coarser.metadata().coverage().expectedCount());
        assertInstanceOf(MetricResult.Series.class,result(limited,OiMetric.QUANTITY,FIXED,END,"5m"));
    }
    @Test void emptyEndpointsZeroBaselineAndInteriorGapDoNotProduceAChangeRate() {
        assertInstanceOf(MetricResult.Omitted.class,result(source(Exchange.BINANCE,List.of()),OiMetric.CHANGE_PCT,FIXED,END,"5m"));
        var zero=new ArrayList<>(samples(false)); zero.set(0,new OiPoint(START.toEpochMilli(),BigDecimal.ZERO));
        var omitted=assertInstanceOf(MetricResult.Omitted.class,result(source(Exchange.BINANCE,zero),OiMetric.CHANGE_PCT,FIXED,END,"5m"));
        assertTrue(omitted.reason().contains("为零"));
        var gap=new ArrayList<>(samples(false)); gap.remove(5);
        assertInstanceOf(MetricResult.Omitted.class,result(source(Exchange.BINANCE,gap),OiMetric.CHANGE_PCT,FIXED,END,"5m"));
    }
    @Test void singleSampleExtremaReturnAValueWithoutConstructingAnInvalidTimeRange() {
        var result=assertInstanceOf(MetricResult.SampledScalar.class,result(source(Exchange.BINANCE,
                List.of(new OiPoint(START.toEpochMilli(),new BigDecimal("100")))),OiMetric.MAX,FIXED,END,"1h"));
        assertEquals(0,BigDecimal.valueOf(100).compareTo(result.value()));
        assertEquals(0,result.observationSeconds().signum()); assertEquals(result.startSampleAt(),result.endSampleAt());
    }
}
