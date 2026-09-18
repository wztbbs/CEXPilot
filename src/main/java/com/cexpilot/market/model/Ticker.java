package com.cexpilot.market.model;

import java.math.BigDecimal;

public record Ticker(BigDecimal lastPrice,
                     BigDecimal changePct24h,
                     BigDecimal baseVolume24h,
                     BigDecimal quoteVolume24h) {
}
