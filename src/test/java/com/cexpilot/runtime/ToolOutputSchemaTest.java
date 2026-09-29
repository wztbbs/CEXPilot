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

    @ParameterizedTest
    @ValueSource(strings = {"", ".data", ".data.rates", ".data.rates.0", ".data.rates.0.1",
            ".data.rates.1000.0", ".data.rates_columns.1", ".data.coverage.range_complete", ".error"})
    void acceptsCollectionsTupleColumnsAndConditionalFields(String path) {
        assertNull(ToolOutputSchema.referenceError(SCHEMAS.get("get_funding_rate_history"), path));
    }

    @ParameterizedTest
    @ValueSource(strings = {".rates", ".data.rate", ".data.rates.rate", ".data.rates.0.rate",
            ".data.rates.0.2", ".data.rates.00.1", ".data.rates.-1.1", ".data.rates.2147483648.1",
            ".data.rates.0.1.value", ".data.rates_columns.0.name", ".error.message", ".ok"})
    void rejectsWrongObjectsIndicesColumnsAndScalarDescent(String path) {
        assertNotNull(ToolOutputSchema.referenceError(SCHEMAS.get("get_funding_rate_history"), path));
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
            assertNull(ToolOutputSchema.referenceError(SCHEMAS.get("get_funding_rate_history"), path));
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
