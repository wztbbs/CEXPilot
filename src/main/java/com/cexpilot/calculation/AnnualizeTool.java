package com.cexpilot.calculation;

import ch.obermuhlner.math.big.BigDecimalMath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Set;

/**
 * 将一个已知周期内的比例按固定日数年基准折算，不读取行情、不推断投资方向。
 * periodic_rate: 单期费率/收益率，假设该周期及费率重复；
 * cumulative_rate: 区间内费率直接求和，仅支持简单折算；
 * holding_return: 已计算的持有期总收益率，可做简单或几何年化。
 * 年化不是预测；输入类型和方法必须由计划明确选择，缺失时绝不静默补齐。
 */
@Component
public class AnnualizeTool extends CalculationTool {
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);
    private static final BigDecimal MAX_PERIOD_SECONDS = SECONDS_PER_DAY.multiply(BigDecimal.valueOf(365_250));
    // 限制 exp 的输入，防止短周期/大费率组合产生极大结果或耗尽计算资源。
    private static final BigDecimal MAX_LOG_GROWTH = BigDecimal.valueOf(200);

    @Override
    public String name() { return "annualize"; }

    @Override
    public JsonNode planningInputSchema() { return CalculationInputSchemas.annualize(); }

    @Override
    public void validateArguments(JsonNode args, boolean allowReferences) {
        JsonNode input = input(args);
        object(input, "input", Set.of("basis", "method", "rate", "rate_unit", "period", "year_days"));
        String basis = choice(input, "basis", Set.of("periodic_rate", "cumulative_rate", "holding_return"));
        String method = choice(input, "method", Set.of("simple", "compound"));
        String rateUnit = choice(input, "rate_unit", Set.of("ratio", "percent"));
        if ("cumulative_rate".equals(basis) && "compound".equals(method)) {
            throw new IllegalArgumentException("cumulative_rate 是费率直接求和，只支持 simple；不能把和当作复合收益率");
        }
        BigDecimal rate = decimal(input.get("rate"), "rate", allowReferences);
        if (rate != null) {
            rate = normalizeRate(rate, rateUnit);
            if (("compound".equals(method) || "holding_return".equals(basis))
                    && rate.compareTo(BigDecimal.ONE.negate()) < 0) {
                throw new IllegalArgumentException("复利或持有期收益率不能小于 -100%");
            }
        }
        JsonNode period = input.get("period");
        object(period, "period", Set.of("value", "unit"));
        String unit = choice(period, "unit", Set.of("second", "minute", "hour", "day"));
        BigDecimal duration = decimal(period.get("value"), "period.value", allowReferences);
        if (duration != null) {
            BigDecimal seconds = duration.multiply(secondsPerUnit(unit));
            if (seconds.compareTo(BigDecimal.ONE) < 0 || seconds.compareTo(MAX_PERIOD_SECONDS) > 0) {
                throw new IllegalArgumentException("period 必须在 1 秒到 365250 天之间");
            }
        }
        yearDays(input);
    }

    @Override
    protected JsonNode calculate(JsonNode args) {
        JsonNode input = input(args);
        String basis = input.path("basis").asText();
        String method = input.path("method").asText();
        BigDecimal originalRate = decimal(input.get("rate"), "rate", false);
        BigDecimal rate = normalizeRate(originalRate, input.path("rate_unit").asText());
        JsonNode period = input.get("period");
        BigDecimal periodSeconds = decimal(period.get("value"), "period.value", false)
                .multiply(secondsPerUnit(period.path("unit").asText()));
        int yearDays = yearDays(input);
        BigDecimal yearSeconds = SECONDS_PER_DAY.multiply(BigDecimal.valueOf(yearDays));
        BigDecimal value;
        if ("simple".equals(method)) {
            // 最后一步才舍入，避免先把每年的周期数舍入再乘输入。
            value = rate.multiply(yearSeconds).divide(periodSeconds, PRECISION);
        } else {
            value = compound(rate, yearSeconds, periodSeconds);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("value", plain(value));
        result.put("value_unit", "ratio");
        result.put("percent", plain(value.movePointRight(2)));
        result.put("basis", basis);
        result.put("method", method);
        result.put("input_rate", plain(originalRate));
        result.put("input_rate_unit", input.path("rate_unit").asText());
        result.put("rate_ratio", plain(rate));
        result.put("period_seconds", plain(periodSeconds));
        result.put("year_days", yearDays);
        result.put("year_days_source", input.has("year_days") ? "explicit" : "default");
        result.put("annual_factor", plain(yearSeconds.divide(periodSeconds, PRECISION)));
        result.put("formula", "simple".equals(method)
                ? "rate_ratio * (year_days * 86400 / period_seconds)"
                : "(1 + rate_ratio) ^ (year_days * 86400 / period_seconds) - 1");
        result.put("interpretation", "periodic_rate".equals(basis)
                ? "假设输入费率和周期持续不变的年化外推，不代表未来实际收益"
                : "历史观测窗口的年化折算，不代表未来实际收益");
        result.put("assumption", "compound".equals(method)
                ? "按相同周期收益因子复投/几何折算；资金费率并不自动具有可复投条件"
                : "按固定基准线性折算，不计复投");
        return result;
    }

    private BigDecimal compound(BigDecimal rate, BigDecimal yearSeconds, BigDecimal periodSeconds) {
        if (rate.signum() == 0) return BigDecimal.ZERO;
        if (rate.compareTo(BigDecimal.ONE.negate()) == 0) return BigDecimal.ONE.negate();
        // log(1+r) / exp(x)-1 会在极小 r 时损失有效位；额外保留前导零位数和保护位。
        int leadingZeros = Math.max(0, rate.scale() - rate.precision());
        MathContext working = new MathContext(PRECISION.getPrecision() + leadingZeros + 16,
                PRECISION.getRoundingMode());
        BigDecimal logGrowth = BigDecimalMath.log(BigDecimal.ONE.add(rate), working)
                .multiply(yearSeconds, working).divide(periodSeconds, working);
        if (logGrowth.abs().compareTo(MAX_LOG_GROWTH) > 0) {
            throw new IllegalArgumentException("复利年化超出计算范围：年化对数增长绝对值不能超过 200");
        }
        return BigDecimalMath.exp(logGrowth, working).subtract(BigDecimal.ONE).round(PRECISION);
    }

    private static BigDecimal normalizeRate(BigDecimal rate, String unit) {
        return "percent".equals(unit) ? rate.movePointLeft(2) : rate;
    }

    private static int yearDays(JsonNode input) {
        if (!input.has("year_days")) return 365;
        JsonNode days = input.get("year_days");
        if (!days.isIntegralNumber() || !days.canConvertToInt()
                || !Set.of(360, 365, 366).contains(days.intValue())) {
            throw new IllegalArgumentException("year_days 仅支持整数 360、365、366；未提供时默认 365");
        }
        return days.intValue();
    }

    private static String choice(JsonNode input, String name, Set<String> choices) {
        JsonNode value = input.get(name);
        if (value == null || !value.isTextual() || !choices.contains(value.textValue())) {
            throw new IllegalArgumentException(name + " 必须明确填写为 " + choices);
        }
        return value.textValue();
    }

    private static BigDecimal secondsPerUnit(String unit) {
        return switch (unit) {
            case "second" -> BigDecimal.ONE;
            case "minute" -> BigDecimal.valueOf(60);
            case "hour" -> BigDecimal.valueOf(3600);
            case "day" -> SECONDS_PER_DAY;
            default -> throw new IllegalArgumentException("不支持的周期单位: " + unit);
        };
    }
}
