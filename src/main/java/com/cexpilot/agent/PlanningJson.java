package com.cexpilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.Set;

public final class PlanningJson {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private PlanningJson() {}
    /** 新规划协议保留阈值十进制精度；不改变旧 Query Planner 的解析行为。 */
    public static JsonNode parse(String content) {
        if(content==null) throw new IllegalArgumentException("规划输出为空");
        String raw=content.trim();
        if(raw.startsWith("```")) {
            int newline=raw.indexOf('\n'); if(newline>=0) raw=raw.substring(newline+1);
            if(raw.endsWith("```")) raw=raw.substring(0,raw.length()-3).trim();
        }
        try { return JSON.readTree(raw); }
        catch(com.fasterxml.jackson.core.JsonProcessingException first) {
            int start=raw.indexOf('{'),end=raw.lastIndexOf('}');
            if(start>=0 && end>start) try { return JSON.readTree(raw.substring(start,end+1)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException ignored) { /* 统一反馈协议错误 */ }
            throw new IllegalArgumentException("规划输出不是有效 JSON 对象",first);
        }
    }
    public static void object(JsonNode node,Set<String> allowed,String name) {
        if(node==null || !node.isObject()) throw new IllegalArgumentException(name+" 必须是对象");
        node.fieldNames().forEachRemaining(key->{ if(!allowed.contains(key)) throw new IllegalArgumentException(name+" 未知字段："+key); });
    }
    public static String text(JsonNode node,String field,int limit) {
        var value=node.get(field);
        if(value==null || !value.isTextual() || value.asText().isBlank() || value.asText().length()>limit)
            throw new IllegalArgumentException(field+" 必须为非空字符串，最长 "+limit+" 字符");
        return value.asText();
    }
}
