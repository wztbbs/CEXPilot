package com.cexpilot.market.funding;

import com.cexpilot.mcp.FundingMcpClient;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.series.SeriesCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 币安资金费率数据源（MCP 传输）：复用 BinanceFundingRateSource 的参数映射、
 * 游标分页、网格吸附与去重逻辑，仅把"单页拉取"与"结算周期推导"两处的
 * 进程内 HTTP 直连换成对行情 MCP Server 的 tools/call。
 * 由 mcp.funding.transport=mcp 装配，与本地实现互斥，DAG/覆盖核对/算子零改动。
 */
@Component
@ConditionalOnProperty(name = "mcp.funding.transport", havingValue = "mcp")
public class McpFundingRateSource implements FundingRateSource {

    private static final long INTERVAL_LOOKBACK_MS = 8 * 86_400_000L;
    private static final int INTERVAL_PROBE_LIMIT = 1000;

    private final FundingRateSource delegate;

    @Autowired
    public McpFundingRateSource(FundingMcpClient client) {
        this(client::fundingRateHistory, symbol -> {
            long now = System.currentTimeMillis();
            return BinanceFundingRateSource.inferIntervalMs(symbol,
                    client.fundingRateHistory(symbol, now - INTERVAL_LOOKBACK_MS, now, INTERVAL_PROBE_LIMIT));
        });
    }

    /** 测试 seam：与 BinanceFundingRateSource 同构，注入 fake 页。 */
    McpFundingRateSource(BinanceFundingRateSource.PageFetcher fetcher,
                         BinanceFundingRateSource.IntervalProvider intervalProvider) {
        this.delegate = new BinanceFundingRateSource(fetcher, intervalProvider);
    }

    @Override
    public Exchange exchange() {
        return delegate.exchange();
    }

    @Override
    public SeriesCapability capability() {
        return delegate.capability();
    }

    @Override
    public long fundingIntervalMs(String base) {
        return delegate.fundingIntervalMs(base);
    }

    @Override
    public FetchResult fetch(String base, long intervalMs, long startMs, long endMs) {
        return delegate.fetch(base, intervalMs, startMs, endMs);
    }
}
