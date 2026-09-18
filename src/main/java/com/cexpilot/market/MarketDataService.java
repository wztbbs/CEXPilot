package com.cexpilot.market;

import com.cexpilot.market.model.Candle;
import com.cexpilot.market.model.FundingInfo;
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

    public FundingInfo funding(Exchange exchange, String base) {
        return switch (exchange) {
            case BINANCE -> {
                MarkPrice premium = binance.premiumIndex(SymbolMapper.binanceSymbol(base));
                List<BigDecimal> history = binance.fundingRateHistory(SymbolMapper.binanceSymbol(base), 10);
                yield new FundingInfo(premium.fundingRate(), premium.nextFundingTime(), history);
            }
            case OKX -> {
                MarkPrice funding = okx.fundingRate(SymbolMapper.okxInstId(base));
                List<BigDecimal> history = okx.fundingRateHistory(SymbolMapper.okxInstId(base), 10);
                yield new FundingInfo(funding.fundingRate(), funding.nextFundingTime(), history);
            }
        };
    }

    public OpenInterestInfo openInterest(Exchange exchange, String base) {
        return switch (exchange) {
            case BINANCE -> new OpenInterestInfo(
                    binance.openInterest(SymbolMapper.binanceSymbol(base)), base,
                    binance.openInterestHistory(SymbolMapper.binanceSymbol(base), "1h", 24));
            case OKX -> new OpenInterestInfo(
                    okx.openInterest(SymbolMapper.okxInstId(base)), base,
                    okx.openInterestHistory(base, "1H", 24));
        };
    }

    public OrderBook orderBook(Exchange exchange, String base, int depth) {
        int boundedDepth = Math.max(5, Math.min(depth, 50));
        return switch (exchange) {
            case BINANCE -> binance.depth(SymbolMapper.binanceSymbol(base), boundedDepth);
            case OKX -> okx.orderBook(SymbolMapper.okxInstId(base), boundedDepth);
        };
    }

    public List<Trade> recentTrades(Exchange exchange, String base, int limit) {
        int boundedLimit = Math.max(10, Math.min(limit, 100));
        return switch (exchange) {
            case BINANCE -> binance.trades(SymbolMapper.binanceSymbol(base), boundedLimit);
            case OKX -> okx.trades(SymbolMapper.okxInstId(base), boundedLimit);
        };
    }

    public MarkPrice markPrice(Exchange exchange, String base) {
        return switch (exchange) {
            case BINANCE -> binance.premiumIndex(SymbolMapper.binanceSymbol(base));
            case OKX -> okx.markPrice(SymbolMapper.okxInstId(base), SymbolMapper.okxIndexInstId(base));
        };
    }
}
