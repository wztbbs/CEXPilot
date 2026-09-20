package com.cexpilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 仅供测试构造合成 schema，生产参数定义来自 YAML。
 */
public final class ToolSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolSchemas() {
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("非法的工具 Schema JSON", e);
        }
    }
}
