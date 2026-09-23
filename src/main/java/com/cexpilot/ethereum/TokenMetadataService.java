package com.cexpilot.ethereum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ERC20 元数据（symbol / decimals），eth_call 只读调用 + 进程内缓存。
 * 元数据按 latest 区块查询，历史交易当时的元数据可能不同；查询失败的结果带 TTL，
 * 查询成功（含合约无 symbol/decimals）的结果永久缓存。
 */
@Service
public class TokenMetadataService {

    private static final Logger log = LoggerFactory.getLogger(TokenMetadataService.class);

    private static final String SELECTOR_SYMBOL = "0x95d89b41";
    private static final String SELECTOR_DECIMALS = "0x313ce567";
    /** 查询失败（RPC 异常）的结果只短暂缓存；查询成功但合约无该字段的结果永久缓存。 */
    private static final long FAILURE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private final EthRpcClient rpc;
    private final Map<String, CachedMeta> cache = new ConcurrentHashMap<>();

    public TokenMetadataService(EthRpcClient rpc) {
        this.rpc = rpc;
    }

    public record TokenMeta(String symbol, Integer decimals) {
    }

    private record CachedMeta(TokenMeta meta, long loadedAtMillis, boolean failed) {
    }

    public TokenMeta meta(String address) {
        return cache.compute(address.toLowerCase(), (key, old) -> {
            if (old != null && (!old.failed()
                    || System.currentTimeMillis() - old.loadedAtMillis() < FAILURE_TTL_MILLIS)) {
                return old;
            }
            return load(key);
        }).meta();
    }

    private CachedMeta load(String address) {
        boolean failed = false;
        String symbol = null;
        Integer decimals = null;
        try {
            String symbolData = rpc.ethCall(address, SELECTOR_SYMBOL);
            symbol = HexUtils.decodeAbiString(symbolData);
        } catch (Exception e) {
            failed = true;
            log.debug("symbol() 查询失败 {}: {}", address, e.getMessage());
        }
        try {
            String decimalsData = rpc.ethCall(address, SELECTOR_DECIMALS);
            if (decimalsData != null && decimalsData.length() > 2) {
                decimals = HexUtils.toBigInteger(decimalsData).intValue();
            }
        } catch (Exception e) {
            failed = true;
            log.debug("decimals() 查询失败 {}: {}", address, e.getMessage());
        }
        return new CachedMeta(new TokenMeta(symbol, decimals), System.currentTimeMillis(), failed);
    }
}
