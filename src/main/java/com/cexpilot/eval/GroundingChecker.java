package com.cexpilot.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字出处校验（Answer Quality 的机器部分）：答案中的数字必须能在证据 JSON 中找到。
 *
 * 规则：
 * - 允许四舍五入差异（相对误差 ≤ 1%）与百分比两种表示（x% 与 x/100 视为同一数）。
 * - 豁免：答案中出现在问题里的数字、小于 32 的整数（多为序号/根数/小时数）。
 * - 抽取前先剔除日期、时间、0x 地址，避免误报。
 */
public final class GroundingChecker {

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}[-/年.]\\d{1,2}[-/月.]\\d{1,2}日?");
    private static final Pattern TIME_PATTERN = Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2})?");
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("0x[0-9a-fA-F]{8,}");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d[\\d,]*\\.?\\d*%?");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private GroundingChecker() {
    }

    public record GroundingResult(boolean passed, List<String> ungrounded) {
    }

    public static GroundingResult check(String answer, JsonNode evidence, String question) {
        if (answer == null || answer.isBlank()) {
            return new GroundingResult(false, List.of("答案为空"));
        }
        Set<BigDecimal> groundedNumbers = new HashSet<>();
        collectNumbers(evidence, groundedNumbers);
        collectNumbersFromText(question, groundedNumbers);

        List<String> ungrounded = new ArrayList<>();
        for (String token : extractNumbers(answer)) {
            BigDecimal value = parseToken(token);
            if (value == null || exempt(value)) {
                continue;
            }
            if (!isGrounded(value, groundedNumbers)) {
                ungrounded.add(token);
            }
        }
        return new GroundingResult(ungrounded.isEmpty(), ungrounded);
    }

    static List<String> extractNumbers(String text) {
        String cleaned = DATE_PATTERN.matcher(text).replaceAll(" ");
        cleaned = TIME_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = ADDRESS_PATTERN.matcher(cleaned).replaceAll(" ");
        List<String> tokens = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(cleaned);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    static boolean isGrounded(BigDecimal value, Set<BigDecimal> groundedNumbers) {
        // 按绝对值匹配：符号一致性（涨跌方向）由 sign 规则单独检查，出处校验只关心数值本身
        BigDecimal abs = value.abs();
        BigDecimal[] candidates = abs.compareTo(BigDecimal.ZERO) == 0
                ? new BigDecimal[]{abs}
                : new BigDecimal[]{abs, abs.movePointLeft(2), abs.movePointRight(2)};
        for (BigDecimal candidate : candidates) {
            for (BigDecimal grounded : groundedNumbers) {
                if (closeEnough(candidate, grounded.abs())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean closeEnough(BigDecimal a, BigDecimal b) {
        if (a.compareTo(b) == 0) {
            return true;
        }
        if (b.signum() == 0) {
            return a.signum() == 0;
        }
        BigDecimal relDiff = a.subtract(b).abs()
                .divide(b.abs(), 6, RoundingMode.HALF_UP);
        return relDiff.compareTo(TOLERANCE) <= 0;
    }

    /** 小于 32 的整数大多是序号 / K线根数 / 小时数，不强制要求出处。 */
    private static boolean exempt(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0
                && value.abs().compareTo(new BigDecimal("32")) < 0;
    }

    private static BigDecimal parseToken(String token) {
        try {
            String cleaned = token.replace(",", "").replace("%", "");
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    static void collectNumbers(JsonNode node, Set<BigDecimal> out) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            out.add(node.decimalValue());
        } else if (node.isTextual()) {
            try {
                out.add(new BigDecimal(node.asText().replace(",", "")));
            } catch (Exception ignored) {
            }
        } else if (node.isArray() || node.isObject()) {
            node.forEach(child -> collectNumbers(child, out));
        }
    }

    private static void collectNumbersFromText(String text, Set<BigDecimal> out) {
        if (text == null) {
            return;
        }
        for (String token : extractNumbers(text)) {
            BigDecimal value = parseToken(token);
            if (value != null) {
                out.add(value);
            }
        }
    }
}
