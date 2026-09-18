package com.cexpilot.market.model;

import java.math.BigDecimal;
import java.util.List;

public record OrderBook(List<Level> bids, List<Level> asks) {

    public record Level(BigDecimal price, BigDecimal qty) {
    }
}
