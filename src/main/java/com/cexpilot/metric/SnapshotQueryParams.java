package com.cexpilot.metric;

/**
 * snapshot / official_24h 形态的快照参数；depth 是否允许仍由绑定决定。
 */
public record SnapshotQueryParams(Integer depth) implements QueryParams {
}
