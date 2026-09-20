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

    /** 持仓量统一为 USD 名义值：当前值与历史序列同单位，跨交易所可比较。 */
    public OpenInterestInfo openInterest(Exchange exchange, String base) {
        return switch (exchange) {
            case BINANCE -> {
                // 币安当前值只有币数，乘最新价换算 USD；历史直接取 sumOpenInterestValue
                String symbol = SymbolMapper.binanceSymbol(base);
                BigDecimal oiUsd = binance.openInterest(symbol)
                        .multiply(binance.ticker24h(symbol).lastPrice());
                yield new OpenInterestInfo(oiUsd, "USD",
                        binance.openInterestHistory(symbol, "1h", 24));
            }
            case OKX -> new OpenInterestInfo(
                    okx.openInterest(SymbolMapper.okxInstId(base)), "USD",
                    okx.openInterestHistory(base, "1H", 24));
        };
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
