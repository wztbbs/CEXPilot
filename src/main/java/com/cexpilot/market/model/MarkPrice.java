package com.cexpilot.market.model;

import java.math.BigDecimal;

public record MarkPrice(BigDecimal markPrice,
                        BigDecimal indexPrice,
                        BigDecimal fundingRate,
                        long nextFundingTime) {
}
