package com.cexpilot.ethereum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TxAnalysisServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TX_HASH = "0x" + "a".repeat(64);
    private static final String WETH = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2";

    private final EthRpcClient rpc = mock(EthRpcClient.class);
    private final TokenMetadataService metadata = mock(TokenMetadataService.class);
    private final TxAnalysisService service = new TxAnalysisService(rpc, metadata);

    private static ObjectNode tx(String blockNumber, String to, String valueWeiHex) {
        ObjectNode tx = MAPPER.createObjectNode();
        if (blockNumber != null) {
            tx.put("blockNumber", blockNumber);
        }
        tx.put("from", "0x" + "1".repeat(40));
        if (to != null) {
            tx.put("to", to);
        }
        tx.put("value", valueWeiHex);
        return tx;
    }

    private static ObjectNode receipt(String status) {
        ObjectNode receipt = MAPPER.createObjectNode();
        if (status != null) {
            receipt.put("status", status);
        }
        receipt.put("gasUsed", "0x5208");
        receipt.put("effectiveGasPrice", "0x1");
        receipt.putArray("logs");
        return receipt;
    }

    private JsonNode analyze(ObjectNode tx, ObjectNode receipt) {
        when(rpc.getTransaction(TX_HASH)).thenReturn(tx);
        when(rpc.getTransactionReceipt(TX_HASH)).thenReturn(receipt);
        return service.analyze(TX_HASH);
    }

    @Test
    void failedTxKeepsAttemptedValueButNoFundFlow() {
        JsonNode facts = analyze(tx("0x10", "0x" + "2".repeat(40), "0xde0b6b3a7640000"), receipt("0x0"));

        assertEquals("failed", facts.path("status").asText());
        assertEquals("1", facts.path("value_eth").asText());
        assertEquals("1000000000000000000", facts.path("value_wei").asText());
        assertEquals("attempted_but_reverted_not_transferred", facts.path("value_eth_status").asText());
        assertEquals(0, facts.path("fund_flow").size());
        assertTrue(facts.has("execution_fee_eth"));
        assertTrue(facts.has("total_fee_eth"));
    }

    @Test
    void pendingTxHasNoBlockNumber() {
        JsonNode facts = analyze(tx(null, "0x" + "2".repeat(40), "0x1"), null);

        assertEquals("pending", facts.path("status").asText());
        assertFalse(facts.has("block_number"));
    }

    @Test
    void preByzantiumReceiptStatusUnknownNotFailed() {
        JsonNode facts = analyze(tx("0x10", "0x" + "2".repeat(40), "0x1"), receipt(null));

        assertEquals("unknown", facts.path("status").asText());
        assertTrue(facts.has("status_note"));
        assertFalse(facts.has("failure_note"));
        assertEquals("unverified_receipt_has_no_status",
                facts.path("fund_flow").get(0).path("execution_status").asText());
    }

    @Test
    void contractCreationFlowTargetsCreatedContract() {
        ObjectNode receipt = receipt("0x1");
        String created = "0x" + "9".repeat(40);
        receipt.put("contractAddress", created);
        JsonNode facts = analyze(tx("0x10", null, "0xde0b6b3a7640000"), receipt);

        assertTrue(facts.path("to").isNull());
        assertEquals(created, facts.path("created_contract_address").asText());
        JsonNode flow = facts.path("fund_flow").get(0);
        assertEquals("ETH", flow.path("token").asText());
        assertEquals(created, flow.path("to").asText());
    }

    @Test
    void blobFeeIncludedInTotal() {
        ObjectNode receipt = receipt("0x1");
        receipt.put("blobGasUsed", "0x20000");   // 131072
        receipt.put("blobGasPrice", "0x1");
        JsonNode facts = analyze(tx("0x10", "0x" + "2".repeat(40), "0x0"), receipt);

        assertEquals("21000", facts.path("execution_fee_wei").asText());
        assertEquals("131072", facts.path("blob_fee_wei").asText());
        assertEquals("152072", facts.path("total_fee_wei").asText());
        assertEquals("0.000000000000152072", facts.path("total_fee_eth").asText());
    }

    @Test
    void unverifiedWethLikeDepositNotAssertedAsConversion() {
        ObjectNode receipt = receipt("0x1");
        ArrayNode logs = (ArrayNode) receipt.path("logs");
        ObjectNode log = logs.addObject();
        log.put("address", "0x" + "3".repeat(40));
        log.put("logIndex", "0x0");
        ArrayNode topics = log.putArray("topics");
        topics.add(AbiDecoder.WETH_DEPOSIT);
        topics.add("0x" + "0".repeat(24) + "1".repeat(40));
        log.put("data", "0xde0b6b3a7640000");
        JsonNode facts = analyze(tx("0x10", "0x" + "3".repeat(40), "0x0"), receipt);

        JsonNode event = facts.path("events").get(0);
        assertEquals("DEPOSIT", event.path("type").asText());
        assertTrue(event.path("interpretation").asText().contains("不能断言"));
        assertEquals(0, facts.path("fund_flow").size());
    }

    @Test
    void verifiedWethDepositRecordedAsConversion() {
        ObjectNode receipt = receipt("0x1");
        ArrayNode logs = (ArrayNode) receipt.path("logs");
        ObjectNode log = logs.addObject();
        log.put("address", WETH.toUpperCase().replace("X", "x"));
        log.put("logIndex", "0x0");
        ArrayNode topics = log.putArray("topics");
        topics.add(AbiDecoder.WETH_DEPOSIT);
        topics.add("0x" + "0".repeat(24) + "1".repeat(40));
        log.put("data", "0xde0b6b3a7640000");
        JsonNode facts = analyze(tx("0x10", WETH, "0x0"), receipt);

        JsonNode flow = facts.path("fund_flow").get(0);
        assertEquals("ETH->WETH", flow.path("token").asText());
        assertEquals("1", flow.path("amount").asText());
        assertEquals("1000000000000000000", flow.path("amount_wei").asText());
    }

    @Test
    void transferWithoutDecimalsKeepsRawAndMarksUnitUnknown() {
        when(metadata.meta("0x" + "4".repeat(40)))
                .thenReturn(new TokenMetadataService.TokenMeta(null, null));
        ObjectNode receipt = receipt("0x1");
        ArrayNode logs = (ArrayNode) receipt.path("logs");
        ObjectNode log = logs.addObject();
        log.put("address", "0x" + "4".repeat(40));
        log.put("logIndex", "0x5");
        ArrayNode topics = log.putArray("topics");
        topics.add(AbiDecoder.TRANSFER);
        topics.add("0x" + "0".repeat(24) + "1".repeat(40));
        topics.add("0x" + "0".repeat(24) + "2".repeat(40));
        log.put("data", "0xf4240");
        JsonNode facts = analyze(tx("0x10", "0x" + "4".repeat(40), "0x0"), receipt);

        JsonNode event = facts.path("events").get(0);
        assertEquals("1000000", event.path("amount_raw").asText());
        assertFalse(event.has("amount"));
        assertEquals("unknown_raw_integer", event.path("amount_unit").asText());
        assertEquals("no_decimals_returned", event.path("metadata_status").asText());
        JsonNode flow = facts.path("fund_flow").get(0);
        assertEquals("1000000", flow.path("amount_raw").asText());
        assertFalse(flow.has("amount"));
        assertEquals("0x" + "4".repeat(40), flow.path("token_address").asText());
        assertEquals(5, flow.path("log_index").asInt());
        assertEquals("partial_gross_events", facts.path("fund_flow_coverage").asText());
        assertEquals("latest", facts.path("token_metadata_as_of").asText());
    }

    @Test
    void unlimitedApprovalRiskNoteDescribesEventNotCurrentPermission() {
        when(metadata.meta("0x" + "4".repeat(40)))
                .thenReturn(new TokenMetadataService.TokenMeta("TKN", 18));
        ObjectNode receipt = receipt("0x1");
        ArrayNode logs = (ArrayNode) receipt.path("logs");
        ObjectNode log = logs.addObject();
        log.put("address", "0x" + "4".repeat(40));
        log.put("logIndex", "0x0");
        ArrayNode topics = log.putArray("topics");
        topics.add(AbiDecoder.APPROVAL);
        topics.add("0x" + "0".repeat(24) + "1".repeat(40));
        topics.add("0x" + "0".repeat(24) + "2".repeat(40));
        log.put("data", "0x" + "f".repeat(64));
        JsonNode facts = analyze(tx("0x10", "0x" + "4".repeat(40), "0x0"), receipt);

        JsonNode approval = facts.path("approvals").get(0);
        assertEquals("unlimited", approval.path("amount").asText());
        String note = approval.path("risk_note").asText();
        assertTrue(note.contains("该日志发生时"));
        assertFalse(note.contains("可以随时转走"));
    }
}
