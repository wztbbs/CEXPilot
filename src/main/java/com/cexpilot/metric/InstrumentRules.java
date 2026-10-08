package com.cexpilot.metric;

import com.cexpilot.market.Exchange;
import com.cexpilot.market.SymbolMapper;
import com.fasterxml.jackson.databind.JsonNode;

/** 指标查询共同的产品与品种约束；区间查询与快照共用，避免两处校验漂移。 */
final class InstrumentRules {
    private InstrumentRules() {}

    static void requirePerpetualUsdt(MetricBinding binding) {
        JsonNode instrument = binding.instrument();
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

    static Exchange exchange(MetricBinding binding) { return Exchange.parse(binding.exchange()); }
    static String base(MetricBinding binding) { return binding.instrument().path("base").asText(); }
}
