package com.cexpilot.ethereum;

import com.cexpilot.config.ExchangeConfig;
import com.cexpilot.exception.ExchangeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 以太坊 JSON-RPC 客户端（只读方法）。
 */
@Component
public class EthRpcClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(EthRpcClient.class);
    private static final String NAME = "ethereum-rpc";

    private final RestClient rest;
    private final String rpcUrl;
    private final AtomicLong idSequence = new AtomicLong(1);

    public EthRpcClient(ExchangeConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.rpcUrl = config.getEthereum().getRpcUrl();
        this.rest = RestClient.builder()
                .baseUrl(rpcUrl)
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
        long start = System.currentTimeMillis();
        log.info("ethereum-rpc 请求 POST {} body={}", rpcUrl, abbreviate(body.toString()));
        try {
            String raw = rest.post().body(body).retrieve().body(String.class);
            log.info("ethereum-rpc 响应 200 {}ms {} body={}",
                    System.currentTimeMillis() - start, method, abbreviate(raw));
            JsonNode root = MAPPER.readTree(raw);
            if (root.has("error") && !root.path("error").isNull()) {
                JsonNode error = root.path("error");
                // RPC 错误：HTTP 200 但带 error 字段
                log.warn("ethereum-rpc 调用失败 {} code={} msg={}",
                        method, error.path("code").asInt(), error.path("message").asText(""));
                throw new ExchangeException(NAME,
                        method + " RPC 错误 " + error.path("code").asInt() + ": "
                                + error.path("message").asText(""));
            }
            JsonNode result = root.path("result");
            return result.isNull() || result.isMissingNode() ? null : result;
        } catch (ExchangeException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // HTTP 错误状态：状态码 + 响应 body 必须留下来
            log.warn("ethereum-rpc 请求失败 {} {}ms 状态={} body={}",
                    method, System.currentTimeMillis() - start,
                    e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            throw new ExchangeException(NAME,
                    method + " 请求失败: HTTP " + e.getStatusCode() + " " + e.getStatusText(), e);
        } catch (Exception e) {
            log.warn("ethereum-rpc 请求异常 {} {}ms: {}", method, System.currentTimeMillis() - start, e.getMessage());
            throw new ExchangeException(NAME, method + " 请求失败: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "...";
    }
}
