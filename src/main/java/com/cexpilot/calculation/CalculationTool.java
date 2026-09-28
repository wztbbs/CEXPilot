package com.cexpilot.calculation;

import com.cexpilot.runtime.AgentTool;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Set;
import java.util.regex.Pattern;

/** 计算工具的公共输入契约；元数据和用途仍以 tools/*.yml 为准。 */
public abstract class CalculationTool implements AgentTool {
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final Pattern REF = Pattern.compile("\\{\\{[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*}}");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    /** 规划期允许完整字段引用；执行期必须是解析后的真实值。 */
    public abstract void validateArguments(JsonNode args, boolean allowReferences);

    protected abstract JsonNode calculate(JsonNode args);

    @Override
    public final ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            validateArguments(args, false);
            return ToolResult.success(calculate(args));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return ToolResult.failure("计算参数错误: " + e.getMessage());
        }
    }

    /** 在 DAG 丢弃引用源元信息之前检查；ok=true 不代表样本或时间窗口完整。 */
    public final void validateSource(JsonNode source) {
        if (source == null) return;
        for (String pointer : new String[]{"/coverage/range_complete", "/sample_complete", "/complete"}) {
            JsonNode flag = source.at(pointer);
            if (!flag.isMissingNode() && (!flag.isBoolean() || !flag.booleanValue())) {
                throw new IllegalArgumentException("上游数据不完整，不能计算: " + pointer);
            }
        }
        if (source.hasNonNull("statistics_omitted")) {
            throw new IllegalArgumentException("上游未提供完整区间统计，不能计算");
        }
    }

    protected static boolean reference(JsonNode node, boolean allowed) {
        return allowed && node != null && node.isTextual() && REF.matcher(node.textValue()).matches();
    }

    protected static void object(JsonNode node, String name, Set<String> fields) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(name + " 必须是对象");
        node.fieldNames().forEachRemaining(key -> {
            if (!fields.contains(key)) throw new IllegalArgumentException(name + " 含未知字段: " + key);
        });
    }

    protected static JsonNode input(JsonNode args) {
        object(args, "args", Set.of("input"));
        JsonNode input = args.get("input");
        if (input == null || !input.isObject()) throw new IllegalArgumentException("input 必须是对象");
        return input;
    }

    /** 兼容现有工具的十进制字符串；绝不把 null、布尔值、单位或百分号强制转成数字。 */
    protected static BigDecimal decimal(JsonNode node, String name, boolean allowReferences) {
        if (reference(node, allowReferences)) return null;
        if (node == null || !(node.isNumber() || node.isTextual())) {
            throw new IllegalArgumentException(name + " 必须是数值或十进制字符串");
        }
        String raw = node.asText();
        if (raw.length() > 256 || !DECIMAL.matcher(raw).matches()) {
            throw new IllegalArgumentException(name + " 必须是有限十进制数（不接受表达式、百分号或单位）");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 不是有效十进制数");
        }
        if (value.precision() > 100 || Math.abs((long) value.scale()) > 1000) {
            throw new IllegalArgumentException(name + " 超出数值范围（最多 100 位有效数字，scale 绝对值不超过 1000）");
        }
        return value;
    }

    protected static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
