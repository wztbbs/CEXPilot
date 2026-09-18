package com.cexpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 容错解析 LLM 输出里的 JSON：剥代码围栏，截取第一个 {/[ 到最后一个配对闭括号。
 */
public final class LlmJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJson() {
    }

    public static JsonNode parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("LLM 输出为空");
        }
        String cleaned = stripFence(text.trim());
        try {
            return MAPPER.readTree(cleaned);
        } catch (Exception e) {
            String extracted = extractJson(cleaned);
            try {
                return MAPPER.readTree(extracted);
            } catch (Exception e2) {
                throw new IllegalArgumentException("无法从 LLM 输出解析 JSON: " + abbreviate(text), e2);
            }
        }
    }

    private static String stripFence(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            return text.trim();
        }
        return text;
    }

    private static String extractJson(String text) {
        int start = -1;
        char open = 0;
        char close = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                open = c;
                close = c == '{' ? '}' : ']';
                break;
            }
        }
        if (start < 0) {
            throw new IllegalArgumentException("输出中没有 JSON 起始符");
        }
        int end = text.lastIndexOf(close);
        if (end <= start) {
            throw new IllegalArgumentException("输出中没有配对的 JSON 闭括号");
        }
        return text.substring(start, end + 1);
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
