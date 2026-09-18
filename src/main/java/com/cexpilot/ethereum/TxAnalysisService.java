package com.cexpilot.ethereum;

import com.cexpilot.exception.ExchangeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 以太坊交易分析：把 tx + receipt + logs 变成结构化事实。
 * 所有解码与金额换算都是确定性代码，LLM 只负责解释这些事实。
 */
@Service
public class TxAnalysisService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOKEN_META_LOOKUPS = 10;

    private final EthRpcClient rpc;
    private final TokenMetadataService tokenMetadata;

    public TxAnalysisService(EthRpcClient rpc, TokenMetadataService tokenMetadata) {
        this.rpc = rpc;
        this.tokenMetadata = tokenMetadata;
    }

    public ObjectNode analyze(String txHash) {
        if (!HexUtils.isValidTxHash(txHash)) {
            throw new IllegalArgumentException("非法的 txHash，应为 0x + 64 位十六进制字符");
        }

        JsonNode tx = rpc.getTransaction(txHash);
        if (tx == null) {
            throw new IllegalArgumentException("未找到该交易，可能不存在或还未被打包确认");
        }
        JsonNode receipt = rpc.getTransactionReceipt(txHash);

        ObjectNode facts = MAPPER.createObjectNode();
        facts.put("tx_hash", txHash);
        facts.put("block_number", HexUtils.toBigInteger(tx.path("blockNumber").asText(null)).longValue());
        facts.put("from", lower(tx.path("from").asText(null)));
        facts.put("to", lower(tx.path("to").asText(null)));

        BigInteger valueWei = HexUtils.toBigInteger(tx.path("value").asText(null));
        facts.put("value_eth", HexUtils.weiToEth(valueWei));

        if (receipt == null) {
            facts.put("status", "pending");
            facts.put("note", "交易已广播但尚未出块，暂无执行结果");
            return facts;
        }

        boolean success = "0x1".equals(receipt.path("status").asText(""));
        facts.put("status", success ? "success" : "failed");

        BigInteger gasUsed = HexUtils.toBigInteger(receipt.path("gasUsed").asText(null));
        BigInteger gasPrice = HexUtils.toBigInteger(
                receipt.path("effectiveGasPrice").asText(null));
        facts.put("gas_used", gasUsed.longValue());
        facts.put("fee_eth", HexUtils.weiToEth(gasUsed.multiply(gasPrice)));
        if (!success) {
            facts.put("failure_note",
                    "交易执行失败被回滚，但已执行的 EVM 操作仍需支付 Gas，所以失败交易也扣手续费。具体失败原因需要 trace 级数据，公开 RPC 无法直接给出");
        }

        ArrayNode events = facts.putArray("events");
        ArrayNode fundFlow = facts.putArray("fund_flow");
        ArrayNode approvals = facts.putArray("approvals");

        Set<String> metaLookups = new HashSet<>();
        for (JsonNode logEntry : receipt.path("logs")) {
            List<String> topics = new ArrayList<>();
            logEntry.path("topics").forEach(t -> topics.add(t.asText()));
            String contract = lower(logEntry.path("address").asText(null));
            AbiDecoder.DecodedEvent event = AbiDecoder.decode(
                    contract, topics, logEntry.path("data").asText(null));

            ObjectNode eventNode = events.addObject();
            eventNode.put("type", event.type());
            eventNode.put("contract", contract);
            if (event.from() != null) {
                eventNode.put("from", event.from());
            }
            if (event.to() != null) {
                eventNode.put("to", event.to());
            }
            event.extra().forEach(eventNode::put);

            switch (event.type()) {
                case "TRANSFER" -> {
                    TokenMetadataService.TokenMeta meta = lookupMeta(contract, metaLookups);
                    String amount = HexUtils.formatTokenAmount(event.amount(),
                            meta == null ? null : meta.decimals());
                    eventNode.put("token_symbol", meta == null ? null : meta.symbol());
                    eventNode.put("amount", amount);
                    ObjectNode flow = fundFlow.addObject();
                    flow.put("token", meta != null && meta.symbol() != null ? meta.symbol() : contract);
                    flow.put("from", event.from());
                    flow.put("to", event.to());
                    flow.put("amount", amount);
                }
                case "APPROVAL" -> {
                    TokenMetadataService.TokenMeta meta = lookupMeta(contract, metaLookups);
                    String amount = HexUtils.formatTokenAmount(event.amount(),
                            meta == null ? null : meta.decimals());
                    boolean unlimited = "true".equals(event.extra().get("unlimited"));
                    eventNode.put("token_symbol", meta == null ? null : meta.symbol());
                    eventNode.put("amount", unlimited ? "unlimited" : amount);

                    ObjectNode approval = approvals.addObject();
                    approval.put("token", meta != null && meta.symbol() != null ? meta.symbol() : contract);
                    approval.put("owner", event.from());
                    approval.put("spender", event.to());
                    approval.put("amount", unlimited ? "unlimited" : amount);
                    if (unlimited) {
                        approval.put("risk_note",
                                "这是无限额度授权（unlimited approval），spender 可以随时转走该地址下这种代币的全部余额");
                    }
                }
                case "DEPOSIT" -> {
                    ObjectNode flow = fundFlow.addObject();
                    flow.put("token", "ETH->WETH");
                    flow.put("from", event.from());
                    flow.put("amount", HexUtils.weiToEth(event.amount()));
                }
                case "WITHDRAWAL" -> {
                    ObjectNode flow = fundFlow.addObject();
                    flow.put("token", "WETH->ETH");
                    flow.put("from", event.from());
                    flow.put("amount", HexUtils.weiToEth(event.amount()));
                }
                default -> {
                    // NFT_TRANSFER / SWAP / UNKNOWN：事件已记录，资金流交给 LLM 结合 events 解释
                }
            }
        }

        // 主币资金流
        if (valueWei.signum() > 0) {
            ObjectNode flow = fundFlow.addObject();
            flow.put("token", "ETH");
            flow.put("from", lower(tx.path("from").asText(null)));
            flow.put("to", lower(tx.path("to").asText(null)));
            flow.put("amount", HexUtils.weiToEth(valueWei));
        }

        facts.put("event_count", events.size());
        if (facts.path("approvals").isEmpty()) {
            facts.remove("approvals");
        }
        return facts;
    }

    private TokenMetadataService.TokenMeta lookupMeta(String contract, Set<String> metaLookups) {
        if (contract == null || metaLookups.size() >= MAX_TOKEN_META_LOOKUPS
                || !metaLookups.add(contract)) {
            // 已查过（缓存命中）或超出本笔交易的查询上限
            if (metaLookups.contains(contract)) {
                return tokenMetadata.meta(contract);
            }
            return null;
        }
        try {
            return tokenMetadata.meta(contract);
        } catch (Exception e) {
            return null;
        }
    }

    private static String lower(String address) {
        return address == null ? null : address.toLowerCase();
    }
}
