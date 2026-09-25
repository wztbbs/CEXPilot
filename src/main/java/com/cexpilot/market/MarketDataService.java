package com.cexpilot.market;

import com.cexpilot.market.model.FundingInfo;
import com.cexpilot.market.model.FundingRatePoint;
import com.cexpilot.market.model.FundingSnapshot;
import com.cexpilot.market.model.MarkPrice;
import com.cexpilot.market.model.OpenInterestInfo;
import com.cexpilot.market.model.OrderBook;
import com.cexpilot.market.model.Ticker;
import com.cexpilot.market.model.Trade;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交易所数据门面：对外暴露统一模型，对内处理 Symbol 映射与两所差异。
 */
@Service
public class MarketDataService {

    private final BinanceClient binance;
    private final OkxClient okx;

    public MarketDataService(BinanceClient binance, OkxClient okx) {
        this.binance = binance;
        this.okx = okx;
    }

    public Ticker ticker(Exchange exchange, String base) {
        return switch (exchange) {
            case BINANCE -> binance.ticker24h(SymbolMapper.binanceSymbol(base));
            case OKX -> okx.ticker(SymbolMapper.okxInstId(base));
        };
    }

    /**
     * 资金费率当前快照。Binance 当前费率取历史末条已结算值（费率与结算时间同源），
     * premiumIndex 只取下次结算时间和快照时间；OKX 当前费率是预测值。
     * 历史序列与区间统计走 FundingQueryService，不在此方法内。
     */
    public FundingInfo fundingSnapshot(Exchange exchange, String base) {
        return switch (exchange) {
            case BINANCE -> {
                String symbol = SymbolMapper.binanceSymbol(base);
                MarkPrice premium = binance.premiumIndex(symbol);
                long now = System.currentTimeMillis();
                List<FundingRatePoint> recent = binance.fundingRateHistory(
                        symbol, now - 40 * 3_600_000L, now, 100);
                FundingRatePoint last = recent.isEmpty() ? null : recent.get(recent.size() - 1);
                yield new FundingInfo(last == null ? null : last.rate(), "settled",
                        last == null ? 0 : last.fundingTime(),
                        premium.nextFundingTime(), 0, premium.markPriceTime());
            }
            case OKX -> {
                // OKX 当前费率是预测值，下一次结算 = 该费率生效的 fundingTime；
                // nextFundingTime 是再下一期
                String instId = SymbolMapper.okxInstId(base);
                FundingSnapshot snapshot = okx.fundingRate(instId);
                yield new FundingInfo(snapshot.rate(), "predicted", snapshot.fundingTime(),
                        snapshot.fundingTime(), snapshot.nextFundingTime(), snapshot.ts());
            }
        };
    }

    /**
     * 持仓量当前快照（真快照接口）：两所均以基础币数量表达，带数据时间与采集时间。
     * 历史序列与区间统计走 OiQueryService，不在此方法内。
     */
    public OpenInterestInfo oiSnapshot(Exchange exchange, String base) {
        String asset = SymbolMapper.normalize(base);
        OpenInterestInfo raw = switch (exchange) {
            case BINANCE -> binance.openInterestSnapshot(SymbolMapper.binanceSymbol(asset));
            case OKX -> okx.openInterestSnapshot(SymbolMapper.okxInstId(asset));
        };
        return new OpenInterestInfo(raw.oi(), asset, raw.dataTime(), raw.snapshotTime());
    }

    public OrderBook orderBook(Exchange exchange, String base, int depth) {
        return switch (exchange) {
            case BINANCE -> binance.depth(SymbolMapper.binanceSymbol(base), depth);
            case OKX -> okx.orderBook(SymbolMapper.okxInstId(base), depth);
        };
    }

    public List<Trade> recentTrades(Exchange exchange, String base, int limit) {
        return switch (exchange) {
            case BINANCE -> binance.trades(SymbolMapper.binanceSymbol(base), limit);
            case OKX -> okx.trades(SymbolMapper.okxInstId(base), limit);
        };
    }

    public MarkPrice markPrice(Exchange exchange, String base) {
        return switch (exchange) {
            case BINANCE -> binance.premiumIndex(SymbolMapper.binanceSymbol(base));
            case OKX -> okx.markPrice(SymbolMapper.okxInstId(base), SymbolMapper.okxIndexInstId(base));
        };
    }
}
