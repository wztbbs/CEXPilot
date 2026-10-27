package com.cexpilot.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class MonitorJson {
    private final ObjectMapper mapper;
    public MonitorJson(ObjectMapper mapper) { this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS); }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("监控 JSON 序列化失败", e); }
    }
    public JsonNode read(String value) {
        if (value == null) return mapper.createArrayNode();
        try {
            JsonNode node = mapper.readTree(value);
            // H2 MySQL 模式的 JSON 返回包裹字符串；MySQL 原生 JSON 直接返回对象。
            return node.isTextual() ? mapper.readTree(node.textValue()) : node;
        } catch (JsonProcessingException e) { throw new IllegalStateException("监控 JSON 无法读取", e); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
