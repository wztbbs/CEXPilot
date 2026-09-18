package com.cexpilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundingCheckerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode evidence(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void groundedAnswerPasses() throws Exception {
        JsonNode evidence = evidence("""
                [{"tool": "get_klines", "ok": true, "data": {"price_change": {"change_pct": -2.3456, "start_price": 60000, "end_price": 58592.64}}}]
                """);
        String answer = "BTC 最近 1 小时下跌了 2.35%，从 60000 跌到 58592.6。";
        assertTrue(GroundingChecker.check(answer, evidence, "BTC 最近 1 小时价格变化多少？").passed());
    }

    @Test
    void hallucinatedNumberFails() throws Exception {
        JsonNode evidence = evidence("""
                [{"tool": "get_ticker", "ok": true, "data": {"last_price": 60000}}]
                """);
        String answer = "BTC 当前价格 61500 USDT。";
        GroundingChecker.GroundingResult result =
                GroundingChecker.check(answer, evidence, "BTC 现在多少钱？");
        assertFalse(result.passed());
        assertEquals(List.of("61500"), result.ungrounded());
    }

    @Test
    void percentAndDecimalFormsAreEquivalent() throws Exception {
        JsonNode evidence = evidence("""
                [{"tool": "get_funding_rate", "ok": true, "data": {"current_funding_rate": 0.0001}}]
                """);
        // 证据是小数 0.0001，答案用百分比 0.01% 表示，应视为同一数
        assertTrue(GroundingChecker.check("当前资金费率为 0.01%。", evidence,
                "资金费率多少？").passed());
    }

    @Test
    void smallIntegersAndQuestionNumbersExempt() throws Exception {
        JsonNode evidence = evidence("""
                [{"tool": "get_klines", "ok": true, "data": {"change_pct": 1.5}}]
                """);
        // 12（K线根数级的小整数）与问题里的 4 都不需要出处
        assertTrue(GroundingChecker.check("看了最近 12 根K线，涨幅 1.5%。问题里的 4 小时窗口。",
                evidence, "最近 4 小时怎么样？").passed());
    }

    @Test
    void datesAndAddressesIgnored() throws Exception {
        JsonNode evidence = evidence("""
                [{"tool": "get_transaction", "ok": true, "data": {"fee_eth": "0.0021"}}]
                """);
        String answer = "该交易于 2026-09-16 14:30 被打包，手续费 0.0021 ETH，"
                + "收款地址 0x2222222222222222222222222222222222222222。";
        assertTrue(GroundingChecker.check(answer, evidence, "这笔交易？").passed());
    }

    @Test
    void emptyAnswerFails() throws Exception {
        JsonNode evidence = evidence("[]");
        assertFalse(GroundingChecker.check("", evidence, "q").passed());
    }

    @Test
    void extractNumbersHandlesCommaAndNegative() {
        List<String> tokens = GroundingChecker.extractNumbers("价格 -1,234.56 USDT，涨 3.5%");
        assertTrue(tokens.contains("-1,234.56"));
        assertTrue(tokens.contains("3.5%"));
    }

    @Test
    void roundingTolerance() throws Exception {
        JsonNode evidence = evidence("""
                [{"tool": "t", "ok": true, "data": {"v": 60300.12}}]
                """);
        // 60300 与 60300.12 相对误差远小于 1%
        assertTrue(GroundingChecker.check("约 60300。", evidence, "q").passed());
    }
}
