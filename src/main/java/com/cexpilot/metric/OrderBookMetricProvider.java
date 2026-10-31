package com.cexpilot.metric;

import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.runtime.RequestContext;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** 盘口快照指标：只输出价格类与买卖占比，数量单位差异（基础币 / 合约张数）不进指标 value。 */
@Component
public class OrderBookMetricProvider implements MetricProvider {
    public static final String NAME = "orderbook";

    private final MarketDataService market;

    public OrderBookMetricProvider(MarketDataService market) { this.market = market; }
    @Override public String name() { return NAME; }
    @Override public MetricSelector selector(String name) { return OrderBookSnapshot.valueOf(name); }

    @Override
    public MetricResult query(MetricQuery query, RequestContext context) {
        Objects.requireNonNull(context, "请求时间上下文不能为空");
        Objects.requireNonNull(context.userZone(), "请求时区不能为空");
        if (!(query instanceof SnapshotQuery snapshot)) {
            throw new IllegalArgumentException("orderbook Provider 仅支持快照查询: " + query.getClass().getSimpleName());
        }
        if (snapshot.depth() == null) {
            throw new IllegalArgumentException("盘口查询缺少档位数 depth");
        }
        OrderBook book = snapshot.marketType() == com.cexpilot.market.MarketType.SPOT
                ? market.orderBook(snapshot.exchange(), snapshot.base(), snapshot.depth(), snapshot.marketType())
                : market.orderBook(snapshot.exchange(), snapshot.base(), snapshot.depth());
        if (book == null) throw new IllegalArgumentException("交易所未返回盘口快照");
        MetricResult.Metadata metadata;
        if (book.timestamp() > 0) {
            metadata = new MetricResult.SnapshotMetadata(Instant.ofEpochMilli(book.timestamp()), context.userZone());
        } else if (snapshot.marketType() == com.cexpilot.market.MarketType.SPOT
                && snapshot.exchange() == com.cexpilot.market.Exchange.BINANCE && book.collectedAt() > 0) {
            metadata = new MetricResult.CollectedSnapshotMetadata(Instant.ofEpochMilli(book.collectedAt()), context.userZone());
        } else throw new IllegalArgumentException("盘口快照缺少数据时间，不能标注 as_of");
        OrderBookSnapshot selector = (OrderBookSnapshot) query.binding().selector();
        BigDecimal value = selector.value(book);
        if (value == null) {
            return new MetricResult.Omitted(metadata, "盘口买卖一侧为空或买卖盘合计为 0，不提供指标 value");
        }
        return new MetricResult.Point(metadata, value);
    }
}
