package com.cexpilot.llm;

import com.cexpilot.config.LlmConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LlmUsageTest {
    private LlmConfig.ModelConfig config(double input, double output) {
        var config = new LlmConfig.ModelConfig(); config.setModel("test");
        config.setPriceInputPer1k(input); config.setPriceOutputPer1k(output); return config;
    }

    @Test void 不同模型按各自单价计算并保留零token的已核实零费用() {
        var response = new ChatResponse("答案",List.of(),100,50);
        var one = LlmUsage.of("normal",config(1,2),response);
        var two = LlmUsage.of("flagship",config(10,20),response);
        assertEquals(0.2,one.estimatedCost(),1e-12); assertEquals(2,two.estimatedCost(),1e-12);
        assertEquals(2.2,LlmUsage.totalCost(List.of(one,two)),1e-12);
        assertEquals(0,LlmUsage.of("normal",config(1,2),new ChatResponse("",List.of(),0,0)).estimatedCost());
    }

    @Test void 未收到使用量不能推断零费用且保留先前已知token() {
        assertNull(LlmUsage.totalCost(List.of()));
        var known = LlmUsage.of("normal",config(1,2),new ChatResponse("",List.of(),100,50));
        for (var unknown : List.of(LlmUsage.of("flagship",config(10,20),null),
                LlmUsage.of("flagship",config(10,20),new ChatResponse("",List.of(),null,5)))) {
            assertNull(unknown.estimatedCost());
            var usages = List.of(known,unknown);
            assertNull(LlmUsage.totalCost(usages));
            assertEquals(100,LlmUsage.knownPromptTokens(usages));
            assertTrue(LlmUsage.knownCompletionTokens(usages)>=50);
        }
    }
}
