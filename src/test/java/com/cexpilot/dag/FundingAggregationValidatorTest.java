package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.metric.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class FundingAggregationValidatorTest {
    static final String QUESTION="币安 BTC/USDT 永续最近10期已结算资金费率最高、最低分别是多少，发生在哪一期？";
    private final MetricPlanCompiler compiler=new MetricPlanCompiler(new MetricCatalog(LOADER),registry(),catalogProviders());
    private ObjectNode rates(String... exchanges) {
        var m=metric("m1","funding.rate_settled","recent_n",exchanges);
        m.remove(List.of("interval","time")); m.put("count",10); return plan(m);
    }
    private ObjectNode complete() {
        var p=rates("binance");
        for(String op:List.of("max","min")) calculation(p,op,op,"{\"kind\":\"field\",\"collection\":\"{{m1.binance.samples}}\",\"field\":\"value\"}");
        return p;
    }
    private List<String> check(ObjectNode plan,String question) {
        return new FundingAggregationValidator().check(question,compiler.compileFull(plan,12));
    }
    @Test void recordedC3SingleSampleAndMissingMinimumAreReportedTogether() {
        var p=rates("binance");
        calculation(p,"c1","max","{\"kind\":\"values\",\"values\":[\"{{m1.binance.samples.0.value}}\"]}");
        var errors=check(p,QUESTION);
        assertEquals(2,errors.size());
        assertTrue(errors.get(0).contains("max 未绑定全部样本"));
        assertTrue(errors.get(1).contains("缺少 min"));
        assertTrue(errors.stream().allMatch(e->e.contains("\"kind\":\"field\"")));
        var config=new DagConfig();config.setPlannerMaxRetries(1);
        var llm=new Script(envelope(p),envelope(complete()));
        var outcome=planner(llm,registry(),config).plan(QUESTION,"","t",e->{});
        assertTrue(outcome.plan().isPresent(),outcome.lastError());
        assertEquals(2,outcome.llmCalls());
        String feedback=llm.calls.get(1).get(3).content();
        assertTrue(feedback.contains("max 未绑定全部样本"));assertTrue(feedback.contains("缺少 min"));
    }
    @Test void completeSequenceExtremaPassAndChangingCountDoesNotEvadeGuard() {
        assertTrue(check(complete(),QUESTION).isEmpty());
        var p=complete();((ObjectNode)p.at("/metrics/0")).put("count",1);
        assertTrue(check(p,QUESTION).get(0).contains("应取最近10期"));
    }
    @Test void explicitSubsetsAndIndexedComparisonsRemainLegal() {
        var p=rates("binance");
        calculation(p,"c1","max","{\"kind\":\"values\",\"values\":[\"{{m1.binance.samples.0.value}}\",\"{{m1.binance.samples.1.value}}\"]}");
        assertTrue(check(p,"最近10期资金费率中，仅比较第1期和第2期的最高值").isEmpty());
        assertTrue(check(p,"最近10期资金费率去掉首尾两期，最高值是多少").isEmpty());
        assertTrue(check(p,"资金费率最低呢？").isEmpty());
        assertTrue(check(p,"比较两所最近10期资金费率均值，最高均值是哪家？").isEmpty());
        assertTrue(check(p,"最近10期两所资金费率差值的最高值是多少？").isEmpty());
        var comparison=rates("binance");
        calculation(comparison,"c1","difference","{\"left\":\"{{m1.binance.samples.9.value}}\",\"right\":\"{{m1.binance.samples.0.value}}\"}");
        assertTrue(check(comparison,"最近10期资金费率，最后一期减第一期是多少？").isEmpty());
    }
    @Test void enumeratingAllIndicesAlsoCoversWholeSetButRepeatingOneIndexDoesNot() {
        var p=rates("binance");
        var values=JSON.createArrayNode();for(int i=0;i<10;i++) values.add("{{m1.binance.samples."+i+".value}}");
        var input=JSON.createObjectNode().put("kind","values");input.set("values",values);
        calculation(p,"c1","max",input.toString());
        assertTrue(check(p,"最近10期资金费率最高是多少？").isEmpty());
        values.set(9,JSON.getNodeFactory().textNode("{{m1.binance.samples.0.value}}"));input.set("values",values);
        ((ObjectNode)p.at("/calculations/0")).set("input",input);
        assertFalse(check(p,"最近10期资金费率最高是多少？").isEmpty());
    }
    @Test void eachExchangeMustHaveItsOwnWholeSequenceExtrema() {
        var p=rates("binance","okx");
        calculation(p,"c1","max","{\"kind\":\"field\",\"collection\":\"{{m1.binance.samples}}\",\"field\":\"value\"}");
        var errors=check(p,"比较两所最近10期资金费率的最高和最低值");
        assertEquals(3,errors.size());assertTrue(errors.stream().anyMatch(e->e.contains("m1.okx")));
    }
}
