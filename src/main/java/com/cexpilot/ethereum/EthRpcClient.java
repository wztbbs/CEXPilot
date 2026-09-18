package com.cexpilot.ethereum;

import com.cexpilot.config.ExchangeConfig;
import com.cexpilot.exception.ExchangeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 以太坊 JSON-RPC 客户端（只读方法）。
 */
@Component
public class EthRpcClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NAME = "ethereum-rpc";

    private final RestClient rest;
    private final AtomicLong idSequence = new AtomicLong(1);

    public EthRpcClient(ExchangeConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.rest = RestClient.builder()
                .baseUrl(config.getEthereum().getRpcUrl())
                .requestFactory(factory)
                .build();
    }

    /** 交易不存在时返回 null（未上链 / 未确认）。 */
    public JsonNode getTransaction(String txHash) {
        return rpc("eth_getTransactionByHash", List.of(txHash));
    }

    /** 收据不存在（pending）时返回 null。 */
    public JsonNode getTransactionReceipt(String txHash) {
        return rpc("eth_getTransactionReceipt", List.of(txHash));
    }

    /** eth_call，返回十六进制字符串。 */
    public String ethCall(String to, String data) {
        ObjectNode call = MAPPER.createObjectNode();
        call.put("to", to);
        call.put("data", data);
        JsonNode result = rpc("eth_call", List.of(call, "latest"));
        return result == null ? null : result.asText(null);
    }

    private JsonNode rpc(String method, List<Object> params) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", idSequence.getAndIncrement());
        body.put("method", method);
        ArrayNode paramsNode = body.putArray("params");
        for (Object param : params) {
            paramsNode.addPOJO(param);
        }
        try {
            String raw = rest.post().body(body).retrieve().body(String.class);
            JsonNode root = MAPPER.readTree(raw);
            if (root.has("error") && !root.path("error").isNull()) {
                JsonNode error = root.path("error");
                throw new ExchangeException(NAME,
                        method + " RPC 错误 " + error.path("code").asInt() + ": "
                                + error.path("message").asText(""));
            }
            JsonNode result = root.path("result");
            return result.isNull() || result.isMissingNode() ? null : result;
        } catch (ExchangeException e) {
            throw e;
        } catch (Exception e) {
            throw new ExchangeException(NAME, method + " 请求失败: " + e.getMessage(), e);
        }
    }
}
