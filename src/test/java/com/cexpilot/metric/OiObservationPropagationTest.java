package com.cexpilot.metric;

import com.cexpilot.conversation.QueryFrame;
import com.cexpilot.dag.DagContext;
import com.cexpilot.dag.PlanNode;
import com.cexpilot.market.series.SeriesCoverage;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.time.TimeRange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OiObservationPropagationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Instant START=Instant.parse("2026-10-27T13:00:00Z"), END=START.plusSeconds(3600);
    private static ObjectNode instrument() {
        return JSON.createObjectNode().put("market_type","perpetual").put("base","BTC").put("quote","USDT").put("settle","USDT");
    }
    private ObjectNode payload() {
        var range=new TimeRange(START,END,ZoneOffset.UTC);
        var coverage=new SeriesCoverage(13,13,List.of(),List.of(),List.of(),false,false,null,END.toEpochMilli(),false);
        var metadata=new MetricResult.SeriesMetadata(range,range,coverage,"5m",true,13);
        var binding=new MetricBinding("m","oi.change_pct","binance","range_statistic",instrument(),"percent","oi",OiMetric.CHANGE_PCT,"test-v1");
        return MetricResultJson.write(binding,new MetricResult.SampledScalar(metadata,new BigDecimal("20"),START,END));
    }
    @Test void conversationFrameKeepsInclusiveSelectionAndRealSampleInstantsWithoutTurningAutomaticIntervalIntoUserInput() {
        var target=new EffectiveTarget("m","metric_0","binance",ConditionOrigin.PLANNED,"oi.change_pct","range_statistic",instrument(),
                ConditionOrigin.DEFAULT,null,null,ConditionOrigin.DEFAULT,false,ConditionOrigin.DEFAULT,null,null,null,"percent","oi","test-v1");
        var plan=new CompiledPlan(null,null,null,Map.of(),List.of(target));
        var evidence=JSON.createArrayNode(); evidence.addObject().put("node_id","metric_0").put("ok",true).set("data",payload());
        var frame=QueryFrame.fromCompiledPlan("conversation","turn","OI最近一小时",END,ZoneOffset.UTC,plan,evidence);
        var saved=frame.targets().get(0);
        assertNull(saved.interval());
        assertEquals("boundary_values",saved.executionMetadata().path("sample_selection").asText());
        assertEquals(END.toString(),saved.executionMetadata().path("end_sample_at").asText());
        assertTrue(saved.executionMetadata().path("selection_range").has("end_inclusive"));
        assertFalse(saved.executionMetadata().path("actual_range").has("end_exclusive"));
    }
    @Test void derivedResultCarriesUnambiguousOiSourceMetadata() {
        var context=new DagContext(); context.put("metric_0",ToolResult.success(payload()));
        var node=new PlanNode("calc","calculation",JSON.createObjectNode(),List.of("metric_0"));
        var attached=MetricCalculationContext.attach(node,context,ToolResult.success(JSON.createObjectNode().put("value",20)));
        var source=attached.data().path("metric_sources").get(0);
        assertEquals("boundary_values",source.path("sample_selection").asText());
        assertEquals(START.toString(),source.path("start_sample_at").asText());
        assertEquals(END.toString(),source.path("end_sample_at").asText());
        assertTrue(source.path("selection_range").has("end_inclusive"));
    }
}
