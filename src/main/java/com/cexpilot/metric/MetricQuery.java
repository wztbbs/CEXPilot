package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.time.CandleInterval;
import com.cexpilot.time.TimeSpec;
import java.util.Objects;

/** 编译完成后的类型化请求；执行时不再反解析 Plan JSON。 */
public record MetricQuery(MetricBinding binding, TimeSpec time, CandleInterval interval, boolean includeUnclosed) {
    public MetricQuery {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(time, "time");
        var instrument = binding.instrument();
        if (!"perpetual".equals(instrument.path("market_type").asText())
                || !"USDT".equals(instrument.path("quote").asText())
                || !"USDT".equals(instrument.path("settle").asText())) {
            throw new IllegalArgumentException("当前指标仅支持 USDT 报价、USDT 结算永续");
        }
        String base = instrument.path("base").asText();
        if (!base.matches("[A-Z0-9]{2,15}") || base.matches(".+(?:USDC|BUSD|USD)")
                || !SymbolMapper.normalize(base).equals(base)) {
            throw new IllegalArgumentException("instrument.base 必须是基础币代码，不能填交易对: " + base);
        }
        Exchange.parse(binding.exchange());
    }
    public Exchange exchange() { return Exchange.parse(binding.exchange()); }
    public String base() { return binding.instrument().path("base").asText(); }
}
