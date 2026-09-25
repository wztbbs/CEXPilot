package com.cexpilot.dag.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

public class QueryCapabilityGuardTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final QueryCapabilityGuard GUARD = QueryCapabilityGuard.defaults();

    public static ObjectNode requirements(boolean comparison) {
        return MAPPER.createObjectNode().put("requires_period_comparison", comparison);
    }

    @Test
    void onlyTaskComparisonIsCheckedHere() {
        assertNull(GUARD.refusal(GuardContext.of(requirements(false))));
        assertEquals(GuardMessages.UNSUPPORTED_COMPARISON,
                GUARD.refusal(GuardContext.of(requirements(true))));
    }

    @Test
    void missingOrMalformedTaskRequirementsFailClosed() {
        for (var r : java.util.List.of(MAPPER.nullNode(), MAPPER.missingNode(),
                MAPPER.createObjectNode(), requirements(false).put("requires_period_comparison", "false"),
                MAPPER.createObjectNode().put("time_scope", "current"))) {
            assertEquals(GuardMessages.UNCONFIRMED, GUARD.refusal(GuardContext.of(r)));
        }
    }

    @Test
    void springChainContainsOnlyTaskLevelGuards() {
        try (var context = new AnnotationConfigApplicationContext("com.cexpilot.dag.guard")) {
            assertEquals(1, context.getBeansOfType(CapabilityGuard.class).size());
            assertNull(context.getBean(QueryCapabilityGuard.class)
                    .refusal(GuardContext.of(requirements(false))));
        }
    }
}
