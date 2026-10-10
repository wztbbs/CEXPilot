package com.cexpilot.dag;

import com.cexpilot.config.DagConfig;
import com.cexpilot.runtime.AgentTool;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolRegistry;
import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.cexpilot.dag.MetricTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanValidator 的确定性校验：未知工具、白名单、required 参数、环、悬空依赖、
 * 节点数 / 深度上限、引用闭合；以及合法 plan 通过。
 * 包含旧版 nodes 格式用例，以及经 MetricPlanCompiler 编译后的新版 metrics/calculations 格式用例。
 */
class PlanValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final org.springframework.core.io.DefaultResourceLoader LOADER = new org.springframework.core.io.DefaultResourceLoader();

    static class StubTool implements com.cexpilot.runtime.TestTools.TestTool {
        private final String name;
        private final List<String> required;

        StubTool(String name, List<String> required) {
            this.name = name;
            this.required = required;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public JsonNode inputSchema() {
            try {
                return MAPPER.readTree("{\"type\":\"object\",\"required\":" + MAPPER.writeValueAsString(required) + "}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext ctx) {
            return ToolResult.success(MAPPER.createObjectNode());
        }
    }

    private static PlanValidator validator(DagConfig config) {
        ToolRegistry registry = com.cexpilot.runtime.TestTools.registry(List.of(
                new StubTool("tool_a", List.of("symbol")),
                new StubTool("tool_b", List.of())));
        return new PlanValidator(registry, config);
    }

    private static DagConfig config() {
        return new DagConfig();
    }

    private static DagPlan parse(String planJson) {
        try {
            return DagPlan.fromJson(MAPPER.readTree(planJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void validPlanPasses() {
        DagPlan plan = parse("""
                {"nodes": [
                  {"id": "n1", "tool": "tool_a", "args": {"symbol": "BTCUSDT"}, "depends_on": []},
                  {"id": "n2", "tool": "tool_a", "args": {"symbol": "{{n1.data.symbol}}"}, "depends_on": ["n1"]},
                  {"id": "n3", "tool": "tool_b", "args": {}, "depends_on": ["n1", "n2"]}
                ]}
                """);
        assertTrue(validator(config()).validate(plan, null, 8).isEmpty());
    }

    @Test
    void unknownToolRejected() {
        DagPlan plan = parse("""
                {"nodes": [{"id": "n1", "tool": "ghost_tool", "args": {}, "depends_on": []}]}
                """);
        List<String> errors = validator(config()).validate(plan, null, 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("未注册的工具") && e.contains("ghost_tool")));
    }

    @Test
    void toolOutsideWhitelistRejected() {
        DagPlan plan = parse("""
                {"nodes": [{"id": "n1", "tool": "tool_a", "args": {"symbol": "BTC"}, "depends_on": []}]}
                """);
        List<String> errors = validator(config()).validate(plan, Set.of("tool_b"), 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("不在本次允许的范围内")));
    }

    @Test
    void missingRequiredArgRejected() {
        DagPlan plan = parse("""
                {"nodes": [{"id": "n1", "tool": "tool_a", "args": {}, "depends_on": []}]}
                """);
        List<String> errors = validator(config()).validate(plan, null, 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("必填参数") && e.contains("symbol")));
    }

    @Test
    void requiredArgCanBeReference() {
        DagPlan plan = parse("""
                {"nodes": [
                  {"id": "n1", "tool": "tool_b", "args": {}, "depends_on": []},
                  {"id": "n2", "tool": "tool_a", "args": {"symbol": "{{n1.data.symbol}}"}, "depends_on": ["n1"]}
                ]}
                """);
        assertTrue(validator(config()).validate(plan, null, 8).isEmpty());
    }

    @Test
    void cycleRejected() {
        DagPlan plan = parse("""
                {"nodes": [
                  {"id": "n1", "tool": "tool_b", "args": {}, "depends_on": ["n2"]},
                  {"id": "n2", "tool": "tool_b", "args": {}, "depends_on": ["n1"]}
                ]}
                """);
        List<String> errors = validator(config()).validate(plan, null, 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("环")));
    }

    @Test
    void danglingDependsOnRejected() {
        DagPlan plan = parse("""
                {"nodes": [{"id": "n1", "tool": "tool_b", "args": {}, "depends_on": ["n9"]}]}
                """);
        List<String> errors = validator(config()).validate(plan, null, 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("不存在的节点") && e.contains("n9")));
    }

    @Test
    void nodeCountLimitEnforced() {
        DagConfig config = config();
        config.setMaxNodes(2);
        DagPlan plan = parse("""
                {"nodes": [
                  {"id": "n1", "tool": "tool_b", "args": {}, "depends_on": []},
                  {"id": "n2", "tool": "tool_b", "args": {}, "depends_on": []},
                  {"id": "n3", "tool": "tool_b", "args": {}, "depends_on": []}
                ]}
                """);
        List<String> errors = validator(config).validate(plan, null, 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("节点数")));
    }

    @Test
    void maxToolCallsTightensNodeLimit() {
        DagPlan plan = parse("""
                {"nodes": [
                  {"id": "n1", "tool": "tool_b", "args": {}, "depends_on": []},
                  {"id": "n2", "tool": "tool_b", "args": {}, "depends_on": []}
                ]}
                """);
        // maxToolCalls=1 时节点上限收紧为 min(maxNodes=8, 1)=1
        List<String> errors = validator(config()).validate(plan, null, 1);
        assertTrue(errors.stream().anyMatch(e -> e.contains("节点数")));
    }

    @Test
    void depthLimitEnforced() {
        DagConfig config = config();
        config.setMaxDepth(2);
        DagPlan plan = parse("""
                {"nodes": [
                  {"id": "n1", "tool": "tool_b", "args": {}, "depends_on": []},
                  {"id": "n2", "tool": "tool_b", "args": {}, "depends_on": ["n1"]},
                  {"id": "n3", "tool": "tool_b", "args": {}, "depends_on": ["n2"]}
                ]}
                """);
        List<String> errors = validator(config).validate(plan, null, 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("深度")));
    }

    @Test
    void refToUnknownNodeRejected() {
        DagPlan plan = parse("""
                {"nodes": [{"id": "n1", "tool": "tool_a", "args": {"symbol": "{{n9.data.x}}"}, "depends_on": []}]}
                """);
        List<String> errors = validator(config()).validate(plan, null, 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("不存在的节点") && e.contains("n9")));
    }

    @Test
    void refOutsideDependencyClosureRejected() {
        DagPlan plan = parse("""
                {"nodes": [
                  {"id": "n1", "tool": "tool_b", "args": {}, "depends_on": []},
                  {"id": "n2", "tool": "tool_a", "args": {"symbol": "{{n1.data.symbol}}"}, "depends_on": []}
                ]}
                """);
        List<String> errors = validator(config()).validate(plan, null, 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("未（传递）依赖")));
    }

    // ---------- 新版 metrics/calculations 格式：经 MetricPlanCompiler 编译后再校验 ----------

    private static DagPlan compile(ObjectNode logicalPlan) {
        var catalog = new com.cexpilot.metric.MetricCatalog(LOADER);
        var registry = MetricTestSupport.registry();
        var compiler = new com.cexpilot.metric.MetricPlanCompiler(catalog, registry, MetricTestSupport.catalogProviders());
        return compiler.compile(logicalPlan, 8);
    }

    private static PlanValidator validatorForCompiled() {
        return new PlanValidator(MetricTestSupport.registry(), new DagConfig());
    }

    @Test
    void compiledMetricPlanPassesValidation() {
        ObjectNode logicalPlan = plan(metric("m1", "trade.turnover", "range_statistic", "binance"));
        calculation(logicalPlan, "c1", "avg", "{\"kind\":\"values\",\"values\":[\"{{m1.binance.value}}\"]}");
        DagPlan plan = compile(logicalPlan);
        assertTrue(validatorForCompiled().validate(plan, null, 8).isEmpty());
    }

    @Test
    void compiledCyclicCalculationsRejected() {
        ObjectNode logicalPlan = plan(metric("m1", "trade.turnover", "range_statistic", "binance"));
        calculation(logicalPlan, "c1", "avg", "{\"kind\":\"values\",\"values\":[\"{{c2.value}}\"]}");
        calculation(logicalPlan, "c2", "avg", "{\"kind\":\"values\",\"values\":[\"{{c1.value}}\"]}");
        DagPlan plan = compile(logicalPlan);
        List<String> errors = validatorForCompiled().validate(plan, null, 8);
        assertTrue(errors.stream().anyMatch(e -> e.contains("环")));
    }
}
