package com.cexpilot.ethereum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ERC20 元数据（symbol / decimals），eth_call 只读调用 + 进程内缓存。
 * 元数据异常（非标准实现、代理合约等）降级为 null，不影响主流程。
 */
@Service
public class TokenMetadataService {

    private static final Logger log = LoggerFactory.getLogger(TokenMetadataService.class);

    private static final String SELECTOR_SYMBOL = "0x95d89b41";
    private static final String SELECTOR_DECIMALS = "0x313ce567";

    private final EthRpcClient rpc;
    private final Map<String, TokenMeta> cache = new ConcurrentHashMap<>();

    public TokenMetadataService(EthRpcClient rpc) {
        this.rpc = rpc;
    }

    public record TokenMeta(String symbol, Integer decimals) {
    }

    public TokenMeta meta(String address) {
        return cache.computeIfAbsent(address.toLowerCase(), this::load);
    }

    private TokenMeta load(String address) {
        String symbol = null;
        Integer decimals = null;
        try {
            String symbolData = rpc.ethCall(address, SELECTOR_SYMBOL);
            symbol = HexUtils.decodeAbiString(symbolData);
        } catch (Exception e) {
            log.debug("symbol() 查询失败 {}: {}", address, e.getMessage());
        }
        try {
            String decimalsData = rpc.ethCall(address, SELECTOR_DECIMALS);
            if (decimalsData != null && decimalsData.length() > 2) {
                decimals = HexUtils.toBigInteger(decimalsData).intValue();
            }
        } catch (Exception e) {
            log.debug("decimals() 查询失败 {}: {}", address, e.getMessage());
        }
        return new TokenMeta(symbol, decimals);
    }
}
