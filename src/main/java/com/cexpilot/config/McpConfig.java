package com.cexpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 行情 MCP 接入配置。transport 切换币安资金费率数据源的取数通道：
 * local = 进程内直连交易所（默认）；mcp = 经本地行情 MCP Server 的 tools/call。
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.funding")
public class McpConfig {

    /** local | mcp */
    private String transport = "local";
    /** 行情 MCP Server 地址（Streamable HTTP） */
    private String baseUrl = "http://localhost:8090";
    /** MCP 端点路径 */
    private String endpoint = "/mcp";
    /** 单次 MCP 请求超时（含 initialize 握手） */
    private long requestTimeoutMs = 30_000;

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }
}
