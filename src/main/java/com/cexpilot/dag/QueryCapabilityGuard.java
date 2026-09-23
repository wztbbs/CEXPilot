package com.cexpilot.dag;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 发布前的保守能力闸门。检查用户诉求与已验证能力，不解析日期，也不证明数据完整。
 * requirements 描述原问题（含已消解的历史指代），不能从选中的工具默认参数反推。
 */
final class QueryCapabilityGuard {
    static final String UNCONFIRMED = "暂时无法可靠确认这次查询的时间和交易对要求，无法处理本次查询。请明确交易对和时间口径。";
    static final String UNSUPPORTED_TIME = "暂不支持指定历史区间、自然日/周或跨期对比，无法处理本次查询；不能用当前数据或最近若干条记录代替。";
    private static final Set<String> MODES = Set.of("unspecified", "current", "recent_samples",
            "rolling_window", "calendar_window", "absolute_range", "period_comparison", "mixed", "unknown");
    private static final Set<String> MARKET_TOOLS = Set.of("get_ticker", "get_funding_rate",
            "get_recent_trades", "get_orderbook", "get_mark_price", "get_klines",
            "get_open_interest", "compare_exchanges");
    private static final Set<String> UNVERIFIED_WINDOW_TOOLS = Set.of(
            "get_klines", "get_open_interest", "compare_exchanges");
    /** 支持用户指定条数的近期样本工具。 */
    private static final Set<String> SAMPLE_TOOLS = Set.of("get_funding_rate", "get_recent_trades");
    /** 样本条数上限：两所逐笔成交与费率历史接口的公共上限。 */
    private static final int MAX_SAMPLE_COUNT = 100;
    // 只作常见漏提取的兜底；不是通用自然语言解析器。仅在市场查询时启用。
    private static final Pattern HISTORICAL = Pattern.compile(
            "今天|今日|昨天|昨日|前天|明天|上上周|上周|本周|这周|上星期|本星期|上个月|上月|本月|这月|去年|今年|上季度|本季度|同比|环比|整点|自然[日周月年]|"
                    + "\\d{4}[-/年]\\d{1,2}|\\d{1,2}月\\d{1,2}[日号]|\\d{1,2}[:：]\\d{2}|"
                    + "\\b(today|yesterday|last week|this week|last month|this month|last year|year.over.year|week.over.week|month.over.month)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile(
            "(?:\\d+(?:\\.\\d+)?|[一二两三四五六七八九十百]+)\\s*(?:小时|分钟|天|周|个月|年)|"
                    + "\\b\\d+(?:\\.\\d+)?\\s*(?:h|d|w|m|hours?|days?|weeks?|minutes?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_USDT_PAIR = Pattern.compile(
            "\\b[A-Z0-9]+\\s*[-/]\\s*(USDC|USD|BTC|ETH|BUSD)\\b|\\b[A-Z0-9]+USDC\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKET_QUERY = Pattern.compile(
            "币安|欧易|成交|行情|资金费率|持仓|盘口|\\b(BTC|ETH|binance|okx|ticker|funding|open interest)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAMPLE_COUNT = Pattern.compile(
            "(?:\\d+|[一二两三四五六七八九十百]+)\\s*(?:条|期|笔)|\\b\\d+\\s*(?:trades|samples|records)\\b", Pattern.CASE_INSENSITIVE);

    /** 返回 null 才允许执行；缺失/不认识的语义一律不放行，不让 repair 将能力缺口改成默认值。 */
    static String refusal(String question, JsonNode requirements, DagPlan plan) {
        if (!valid(requirements)) {
            return UNCONFIRMED;
        }
        String mode = requirements.path("time_scope").asText();
        String duration = requirements.path("duration").asText(null);
        String quote = requirements.path("quote_asset").asText(null);
        String marketType = requirements.path("market_type").asText(null);
        JsonNode count = requirements.path("sample_count");
        boolean market = plan.nodes().stream().anyMatch(n -> n.tool() != null && MARKET_TOOLS.contains(n.tool()))
                || quote != null || marketType != null || MARKET_QUERY.matcher(question).find();

        if (market && (NON_USDT_PAIR.matcher(question).find()
                || quote != null && !"USDT".equals(quote.toUpperCase(Locale.ROOT))
                || marketType != null && !"perpetual".equals(marketType))) {
            return "当前行情查询仅支持 USDT 本位永续合约，无法查询所要求的交易对或市场；不会替换成其他交易对。";
        }
        if (Set.of("calendar_window", "absolute_range", "period_comparison").contains(mode)
                || market && HISTORICAL.matcher(question).find()) {
            return UNSUPPORTED_TIME;
        }
        if (Set.of("mixed", "unknown").contains(mode)) {
            return UNCONFIRMED;
        }
        if (market) {
            var durations = DURATION.matcher(question);
            while (durations.find()) {
                String literal = durations.group().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
                if (!"rolling_window".equals(mode) || !"24h".equals(duration)
                        || !Set.of("24h", "24hour", "24hours", "24小时", "二十四小时", "1d", "1day", "1days", "1天", "一天").contains(literal)) {
                    return "问题包含尚不支持或未可靠保留的时间跨度，暂时无法处理。";
                }
            }
            if (SAMPLE_COUNT.matcher(question).find() && !"recent_samples".equals(mode)) {
                // 检测到条数诉求，但 planner 未按近期样本口径规划，不能放行给不承诺条数的工具
                return UNCONFIRMED;
            }
        }
        if (plan.nodes().stream().anyMatch(n -> n.tool() != null && UNVERIFIED_WINDOW_TOOLS.contains(n.tool()))) {
            return "当前 K 线、持仓量及跨所走势统计尚未验证完整时间覆盖，暂时无法可靠回答这类查询。";
        }
        if ("rolling_window".equals(mode)) {
            if (!"24h".equals(duration) || plan.nodes().isEmpty()
                    || plan.nodes().stream().anyMatch(n -> !"get_ticker".equals(n.tool()))) {
                return "目前只支持 Ticker 自带的滚动 24 小时统计，暂不支持所要求的时间窗口或指标。";
            }
        } else if (duration != null) {
            return UNCONFIRMED;
        }
        if ("recent_samples".equals(mode)) {
            if (plan.nodes().isEmpty() || plan.nodes().stream().anyMatch(n ->
                    n.tool() == null || !SAMPLE_TOOLS.contains(n.tool()))) {
                return "暂时无法按所要求的样本口径查询；最近若干条记录不代表完整时间区间。";
            }
            // 条数诉求必须显式落在工具参数里：靠默认值冒充等于漏提取
            boolean countMentioned = !count.isNull() || SAMPLE_COUNT.matcher(question).find();
            for (PlanNode node : plan.nodes()) {
                JsonNode arg = node.args() == null ? null : node.args().path(countArg(node.tool()));
                if (arg != null && arg.isIntegralNumber() && arg.canConvertToInt()) {
                    if (arg.intValue() <= 0) {
                        return UNCONFIRMED;
                    }
                    if (arg.intValue() > MAX_SAMPLE_COUNT) {
                        return "样本条数最多支持 " + MAX_SAMPLE_COUNT + " 条，无法完成这次 "
                                + arg.intValue() + " 条的查询；请降低条数后重试。";
                    }
                } else if (countMentioned) {
                    return UNCONFIRMED;
                }
            }
            // 单样本节点时交叉验证：planner 自报条数与工具参数必须一致
            if (!count.isNull() && plan.nodes().size() == 1) {
                JsonNode arg = plan.nodes().get(0).args().path(countArg(plan.nodes().get(0).tool()));
                if (arg.isIntegralNumber() && arg.canConvertToInt() && arg.intValue() != count.intValue()) {
                    return UNCONFIRMED;
                }
            }
        } else if (!count.isNull()) {
            return UNCONFIRMED;
        }
        return null;
    }

    /** 各样本工具承载条数的参数名。 */
    private static String countArg(String tool) {
        return "get_recent_trades".equals(tool) ? "limit" : "count";
    }

    private static boolean valid(JsonNode r) {
        if (!r.isObject() || !MODES.contains(r.path("time_scope").asText())) {
            return false;
        }
        for (String field : new String[]{"duration", "quote_asset", "market_type"}) {
            JsonNode value = r.path(field);
            if (!value.isNull() && (!value.isTextual() || value.asText().isBlank())) {
                return false;
            }
        }
        JsonNode count = r.path("sample_count");
        return count.isNull() || count.isIntegralNumber() && count.canConvertToInt() && count.intValue() > 0;
    }

    private QueryCapabilityGuard() {}
}
