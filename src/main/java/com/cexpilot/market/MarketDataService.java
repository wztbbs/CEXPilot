package com.cexpilot.market;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.FundingInfo;
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

    public List<Candle> klines(Exchange exchange, String base, TimeWindow window) {
        return switch (exchange) {
            case BINANCE -> binance.klines(SymbolMapper.binanceSymbol(base),
                    window.binanceInterval(), window.candles());
            case OKX -> okx.candles(SymbolMapper.okxInstId(base),
                    window.okxBar(), window.candles());
        };
    }

    public Ticker ticker(Exchange exchange, String base) {
        return switch (exchange) {
            case BINANCE -> binance.ticker24h(SymbolMapper.binanceSymbol(base));
            case OKX -> okx.ticker(SymbolMapper.okxInstId(base));
        };
    }

    public FundingInfo funding(Exchange exchange, String base, int count) {
        return switch (exchange) {
            case BINANCE -> {
                // 当前费率取历史末条（费率与结算时间同源）；premiumIndex 只取下次结算时间和快照时间，
                // 不拿它的 lastFundingRate 配历史时间——两者实采可能不一致（历史接口有滞后）
                String symbol = SymbolMapper.binanceSymbol(base);
                MarkPrice premium = binance.premiumIndex(symbol);
                List<FundingInfo.RatePoint> history = binance.fundingRateHistory(symbol, count);
                FundingInfo.RatePoint last = history.isEmpty() ? null : history.get(history.size() - 1);
                yield new FundingInfo(last == null ? null : last.rate(), "settled",
                        last == null ? 0 : last.fundingTime(),
                        premium.nextFundingTime(), 0, premium.markPriceTime(), "settled", history);
            }
            case OKX -> {
                // OKX 当前费率是预测值，下一次结算 = 该费率生效的 fundingTime；
                // nextFundingTime 是再下一期
                String instId = SymbolMapper.okxInstId(base);
                FundingSnapshot snapshot = okx.fundingRate(instId);
                yield new FundingInfo(snapshot.rate(), "predicted", snapshot.fundingTime(),
                        snapshot.fundingTime(), snapshot.nextFundingTime(), snapshot.ts(), "realized",
                        okx.fundingRateHistory(instId, count));
            }
        };
    }

    /** 两所均查询指定 USDT 永续合约，以基础币数量表达；最新值取历史末点。 */
    public OpenInterestInfo openInterest(Exchange exchange, String base) {
        String asset = SymbolMapper.normalize(base);
        List<OpenInterestInfo.OiPoint> history = switch (exchange) {
            case BINANCE -> binance.openInterestHistory(SymbolMapper.binanceSymbol(asset), "1h", 24);
            case OKX -> okx.openInterestHistory(SymbolMapper.okxInstId(asset), "1H", 24);
        };
        // 历史为空不拼接不同时间来源的快照，也不乘最新价估算金额。
        BigDecimal latest = history.isEmpty() ? null : history.get(history.size() - 1).oi();
        return new OpenInterestInfo(latest, asset, history);
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
