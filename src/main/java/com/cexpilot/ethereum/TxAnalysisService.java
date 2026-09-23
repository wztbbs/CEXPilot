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
    private static final String WETH_MAINNET = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2";

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
        String blockNumberHex = tx.path("blockNumber").asText(null);
        if (blockNumberHex != null) {
            facts.put("block_number", HexUtils.toBigInteger(blockNumberHex).longValue());
        }
        facts.put("from", lower(tx.path("from").asText(null)));
        facts.put("to", lower(tx.path("to").asText(null)));

        BigInteger valueWei = HexUtils.toBigInteger(tx.path("value").asText(null));
        facts.put("value_wei", valueWei.toString());
        facts.put("value_eth", HexUtils.weiToEth(valueWei));

        if (receipt == null) {
            facts.put("status", "pending");
            facts.put("note", "交易已广播但尚未出块，暂无执行结果");
            return facts;
        }

        // Byzantium 之前的收据没有 status 字段，只有 root，不能判失败
        String statusHex = receipt.path("status").asText(null);
        Boolean success = null;
        if (statusHex != null) {
            success = "0x1".equals(statusHex);
            facts.put("status", success ? "success" : "failed");
        } else {
            facts.put("status", "unknown");
            facts.put("status_note", "收据缺少 status 字段（Byzantium 之前的旧格式），无法判定执行成功或失败");
        }

        BigInteger gasUsed = HexUtils.toBigInteger(receipt.path("gasUsed").asText(null));
        BigInteger gasPrice = HexUtils.toBigInteger(
                receipt.path("effectiveGasPrice").asText(null));
        BigInteger executionFeeWei = gasUsed.multiply(gasPrice);
        facts.put("gas_used", gasUsed.longValue());
        facts.put("execution_fee_wei", executionFeeWei.toString());
        facts.put("execution_fee_eth", HexUtils.weiToEth(executionFeeWei));
        BigInteger totalFeeWei = executionFeeWei;
        String blobGasUsedHex = receipt.path("blobGasUsed").asText(null);
        String blobGasPriceHex = receipt.path("blobGasPrice").asText(null);
        if (blobGasUsedHex != null && blobGasPriceHex != null) {
            BigInteger blobFeeWei = HexUtils.toBigInteger(blobGasUsedHex)
                    .multiply(HexUtils.toBigInteger(blobGasPriceHex));
            facts.put("blob_fee_wei", blobFeeWei.toString());
            facts.put("blob_fee_eth", HexUtils.weiToEth(blobFeeWei));
            totalFeeWei = totalFeeWei.add(blobFeeWei);
        }
        facts.put("total_fee_wei", totalFeeWei.toString());
        facts.put("total_fee_eth", HexUtils.weiToEth(totalFeeWei));
        if (Boolean.FALSE.equals(success)) {
            facts.put("failure_note",
                    "交易执行失败被回滚，但已执行的 EVM 操作仍需支付 Gas，所以失败交易也扣手续费。具体失败原因需要 trace 级数据，公开 RPC 无法直接给出");
        }

        String createdContract = lower(receipt.path("contractAddress").asText(null));
        if (createdContract != null) {
            facts.put("created_contract_address", createdContract);
        }

        ArrayNode events = facts.putArray("events");
        ArrayNode fundFlow = facts.putArray("fund_flow");
        ArrayNode approvals = facts.putArray("approvals");

        Set<String> metaLookups = new HashSet<>();
        for (JsonNode logEntry : receipt.path("logs")) {
            List<String> topics = new ArrayList<>();
            logEntry.path("topics").forEach(t -> topics.add(t.asText()));
            String contract = lower(logEntry.path("address").asText(null));
            String logIndexHex = logEntry.path("logIndex").asText(null);
            Integer logIndex = logIndexHex == null ? null : HexUtils.toBigInteger(logIndexHex).intValue();
            AbiDecoder.DecodedEvent event = AbiDecoder.decode(
                    contract, topics, logEntry.path("data").asText(null));

            ObjectNode eventNode = events.addObject();
            eventNode.put("type", event.type());
            eventNode.put("contract", contract);
            if (logIndex != null) {
                eventNode.put("log_index", logIndex);
            }
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
                    putAmountFields(eventNode, event.amount(), amount, meta);

                    ObjectNode flow = fundFlow.addObject();
                    flow.put("token", meta != null && meta.symbol() != null ? meta.symbol() : contract);
                    flow.put("token_address", contract);
                    if (logIndex != null) {
                        flow.put("log_index", logIndex);
                    }
                    flow.put("from", event.from());
                    flow.put("to", event.to());
                    flow.put("amount_raw", event.amount().toString());
                    if (amount != null) {
                        flow.put("amount", amount);
                    } else {
                        flow.put("amount_unit", "unknown_raw_integer");
                    }
                }
                case "APPROVAL" -> {
                    TokenMetadataService.TokenMeta meta = lookupMeta(contract, metaLookups);
                    String amount = HexUtils.formatTokenAmount(event.amount(),
                            meta == null ? null : meta.decimals());
                    boolean unlimited = "true".equals(event.extra().get("unlimited"));
                    eventNode.put("token_symbol", meta == null ? null : meta.symbol());
                    if (unlimited) {
                        eventNode.put("amount", "unlimited");
                    } else {
                        putAmountFields(eventNode, event.amount(), amount, meta);
                    }

                    ObjectNode approval = approvals.addObject();
                    approval.put("token", meta != null && meta.symbol() != null ? meta.symbol() : contract);
                    approval.put("token_address", contract);
                    if (logIndex != null) {
                        approval.put("log_index", logIndex);
                    }
                    approval.put("owner", event.from());
                    approval.put("spender", event.to());
                    if (unlimited) {
                        approval.put("amount", "unlimited");
                        approval.put("risk_note",
                                "该日志把 spender 的授权额度设置为 uint256 最大值（unlimited approval）；它只描述该日志发生时的授权变化，不代表当前权限，当前额度需另行查询最新 allowance");
                    } else {
                        approval.put("amount_raw", event.amount().toString());
                        if (amount != null) {
                            approval.put("amount", amount);
                        } else {
                            approval.put("amount_unit", "unknown_raw_integer");
                        }
                    }
                }
                case "DEPOSIT" -> {
                    eventNode.put("amount_raw", event.amount().toString());
                    if (WETH_MAINNET.equals(contract)) {
                        ObjectNode flow = fundFlow.addObject();
                        flow.put("token", "ETH->WETH");
                        flow.put("token_address", contract);
                        if (logIndex != null) {
                            flow.put("log_index", logIndex);
                        }
                        flow.put("from", event.from());
                        flow.put("amount_wei", event.amount().toString());
                        flow.put("amount", HexUtils.weiToEth(event.amount()));
                    } else {
                        eventNode.put("interpretation",
                                "事件签名与 WETH Deposit 相同，但该合约不是已验证的主网 WETH，不能断言发生了 ETH->WETH 转换");
                    }
                }
                case "WITHDRAWAL" -> {
                    eventNode.put("amount_raw", event.amount().toString());
                    if (WETH_MAINNET.equals(contract)) {
                        ObjectNode flow = fundFlow.addObject();
                        flow.put("token", "WETH->ETH");
                        flow.put("token_address", contract);
                        if (logIndex != null) {
                            flow.put("log_index", logIndex);
                        }
                        flow.put("from", event.from());
                        flow.put("amount_wei", event.amount().toString());
                        flow.put("amount", HexUtils.weiToEth(event.amount()));
                    } else {
                        eventNode.put("interpretation",
                                "事件签名与 WETH Withdrawal 相同，但该合约不是已验证的主网 WETH，不能断言发生了 WETH->ETH 转换");
                    }
                }
                default -> {
                    // NFT_TRANSFER / SWAP / UNKNOWN：事件已记录，资金流交给 LLM 结合 events 解释
                }
            }
        }

        // 主币资金流：tx.value 是调用意图，只有成功执行才记实际转账；合约创建时接收方是新合约
        if (valueWei.signum() > 0) {
            if (Boolean.FALSE.equals(success)) {
                facts.put("value_eth_status", "attempted_but_reverted_not_transferred");
            } else {
                ObjectNode flow = fundFlow.addObject();
                flow.put("token", "ETH");
                flow.put("from", lower(tx.path("from").asText(null)));
                String to = lower(tx.path("to").asText(null));
                flow.put("to", to != null ? to : createdContract);
                flow.put("amount_wei", valueWei.toString());
                flow.put("amount", HexUtils.weiToEth(valueWei));
                if (success == null) {
                    flow.put("execution_status", "unverified_receipt_has_no_status");
                }
            }
        }

        facts.put("fund_flow_coverage", "partial_gross_events");
        facts.put("fund_flow_note",
                "fund_flow 由顶层 value 与已识别的日志事件拼接，是逐事件总额而非按地址合并的净额；未包含内部调用（internal transactions）产生的 ETH 转移");
        if (!metaLookups.isEmpty()) {
            facts.put("token_metadata_as_of", "latest");
        }

        facts.put("event_count", events.size());
        if (facts.path("approvals").isEmpty()) {
            facts.remove("approvals");
        }
        return facts;
    }

    private static void putAmountFields(ObjectNode node, BigInteger raw, String formatted,
                                        TokenMetadataService.TokenMeta meta) {
        node.put("amount_raw", raw == null ? null : raw.toString());
        if (meta != null && meta.decimals() != null) {
            node.put("token_decimals", meta.decimals());
        }
        if (formatted != null) {
            node.put("amount", formatted);
        } else {
            node.put("amount_unit", "unknown_raw_integer");
        }
        node.put("metadata_status", metaStatus(meta));
    }

    private static String metaStatus(TokenMetadataService.TokenMeta meta) {
        if (meta == null) {
            return "lookup_failed_or_limit_reached";
        }
        return meta.decimals() == null ? "no_decimals_returned" : "ok";
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
