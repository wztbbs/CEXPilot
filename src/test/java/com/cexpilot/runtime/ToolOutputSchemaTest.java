package com.cexpilot.runtime;

import com.cexpilot.dag.DagContext;
import com.cexpilot.dag.ReferenceResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ToolOutputSchemaTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, JsonNode> SCHEMAS = ToolDefinitionLoader.load(new DefaultResourceLoader()).stream()
            .collect(Collectors.toMap(ToolDefinition::name, ToolDefinition::outputSchema));

    // 旧资金费率历史工具已删除，但其 output_schema 结构（数组元组 + columns）仍适合用来测试引用校验。
    private static final JsonNode HISTORY_SCHEMA;
    static {
        try {
            HISTORY_SCHEMA = MAPPER.readTree("""
                    {"type":"object","additionalProperties":false,"properties":{
                    "exchange":{"type":"string"},"symbol":{"type":"string"},
                    "requested_range":{"type":"object","additionalProperties":false,"properties":{
                      "start_inclusive":{"type":"string"},"end_exclusive":{"type":"string"},"timezone":{"type":"string"}}},
                    "coverage":{"type":"object","additionalProperties":false,"properties":{
                      "expected_count":{"type":"integer"},"actual_count":{"type":"integer"},
                      "missing_count":{"type":"integer"},"unexpected_count":{"type":"integer"},
                      "dropped_unclosed":{"type":"boolean"},"closed_part_complete":{"type":"boolean"},
                      "range_complete":{"type":"boolean"},"contains_unclosed":{"type":"boolean"},
                      "covered_until":{"type":"string"},"abort_reason":{"type":"string"},
                      "missing_open_times":{"type":"array","items":{"type":"string"}},
                      "unexpected_open_times":{"type":"array","items":{"type":"string"}}}},
                    "mode":{"type":"string"},"funding_interval_hours":{"type":"integer"},
                    "period_count":{"type":"integer"},"requested_count":{"type":"integer"},
                    "actual_count":{"type":"integer"},"sample_complete":{"type":"boolean"},
                    "actual_count_note":{"type":"string"},
                    "rates_columns":{"type":"array","items":{"type":"string"}},
                    "rates":{"type":"array","items":{"type":"array","prefixItems":[
                      {"type":"string"},{"type":"number"}],"items":false}}}}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".data", ".data.rates", ".data.rates.0", ".data.rates.0.1",
            ".data.rates.1000.0", ".data.rates_columns.1", ".data.coverage.range_complete", ".error"})
    void acceptsCollectionsTupleColumnsAndConditionalFields(String path) {
        assertNull(ToolOutputSchema.referenceError(HISTORY_SCHEMA, path));
    }

    @ParameterizedTest
    @ValueSource(strings = {".rates", ".data.rate", ".data.rates.rate", ".data.rates.0.rate",
            ".data.rates.0.2", ".data.rates.00.1", ".data.rates.-1.1", ".data.rates.2147483648.1",
            ".data.rates.0.1.value", ".data.rates_columns.0.name", ".error.message", ".ok"})
    void rejectsWrongObjectsIndicesColumnsAndScalarDescent(String path) {
        assertNotNull(ToolOutputSchema.referenceError(HISTORY_SCHEMA, path));
    }

    @Test
    void dynamicOriginalRowsRemainRuntimeCheckedWhileKnownSiblingsAreStrict() {
        JsonNode schema = SCHEMAS.get("min");
        assertNull(ToolOutputSchema.referenceError(schema, ".data.item.0"));
        assertNull(ToolOutputSchema.referenceError(schema, ".data.item.rate"));
        assertNotNull(ToolOutputSchema.referenceError(schema, ".data.items.0"));
        assertNotNull(ToolOutputSchema.referenceError(schema, ".data.value.rate"));
    }

    @Test
    void missingOptionalFieldAndEmptyArrayStillFailAtRuntime() throws Exception {
        var context = new DagContext();
        context.put("n1", ToolResult.success(MAPPER.readTree("{\"rates\":[],\"coverage\":{\"range_complete\":false}}")));
        for (String path : new String[]{".data.rates.0.1", ".data.funding_interval_hours"}) {
            assertNull(ToolOutputSchema.referenceError(HISTORY_SCHEMA, path));
            assertThrows(ReferenceResolver.ReferenceResolutionException.class,
                    () -> ReferenceResolver.resolve(MAPPER.getNodeFactory().textNode("{{n1" + path + "}}"), context));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{n1.data.rates[0][1]}}", "{{n1.data.last_price}", "{{ n1.data.last_price }}", "{{n1.data..price}}"})
    void malformedReferencesCannotBeSilentlySkipped(String text) throws Exception {
        var args = MAPPER.createObjectNode().set("input", MAPPER.createArrayNode().add(text));
        assertFalse(ReferenceResolver.syntaxErrors(args).isEmpty());
    }

    @Test
    void validEmbeddedAndWholeReferencesKeepExistingSyntax() {
        var args = MAPPER.getNodeFactory().textNode("{{n1.data.last_price}} vs {{n2.data.last_price}}");
        assertTrue(ReferenceResolver.syntaxErrors(args).isEmpty());
        assertEquals(2, ReferenceResolver.findRefs(args).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"type\":\"unknown\"}", "{\"type\":\"object\"}",
            "{\"type\":\"array\"}", "{\"type\":\"array\",\"items\":null}",
            "{\"type\":\"array\",\"items\":{},\"prefixItems\":{}}", "{\"properties\":{}}",
            "{\"type\":\"number\",\"properties\":{}}", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false,\"typo\":1}"})
    void malformedOutputContractsFailStartup(String schema) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ToolOutputSchema.validateSchema(MAPPER.readTree(schema)));
    }
}
