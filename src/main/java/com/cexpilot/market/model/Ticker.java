package com.cexpilot.market.model;

import java.math.BigDecimal;

/**
 * 24 小时行情快照。
 *
 * @param lastPrice            最新成交价，例如 BTC-USDT 为 65231.50
 * @param changePct24h         24 小时涨跌幅（百分比数值，非小数），
 *                             例如 2.35 表示上涨 2.35%，-1.20 表示下跌 1.2%；
 *                             基准价（open24h）为 0 时无法计算，为 null
 * @param baseVolume24h        24 小时成交量（以标的币种计），
 *                             例如 BTC-USDT 中为 12345.678 个 BTC
 * @param quoteVolume24h       24 小时成交额（以计价币种计），
 *                             例如 BTC-USDT 中为 805432100.25 个 USDT
 * @param quoteVolumeEstimated 成交额为估算值（OKX 无成交额字段，用币数 × 最新价估算）；
 *                             Binance 为接口原值（false）
 * @param timestamp            快照时间戳（毫秒），0 表示上游未提供
 */
public record Ticker(BigDecimal lastPrice,
                     BigDecimal changePct24h,
                     BigDecimal baseVolume24h,
                     BigDecimal quoteVolume24h,
                     boolean quoteVolumeEstimated,
                     long timestamp) {
}
