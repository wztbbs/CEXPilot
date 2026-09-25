package com.cexpilot.market.tool;

import com.cexpilot.exception.ExchangeException;
import com.cexpilot.market.Exchange;
import com.cexpilot.market.MarketDataService;
import com.cexpilot.market.SymbolMapper;
import com.cexpilot.market.policy.MarketScopePolicy;
import com.cexpilot.runtime.AgentTool;
import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Supplier;

/**
 * 行情工具基类：统一参数解析与异常 → ToolResult 转换。
 * 工具失败表现为 ToolResult.failure 回灌给 LLM，不抛异常中断 Agent 循环。
 */
public abstract class AbstractMarketTool implements AgentTool {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected final MarketDataService market;

    protected AbstractMarketTool(MarketDataService market) {
        this.market = market;
    }

    @Override
    public ToolResult execute(JsonNode args, com.cexpilot.runtime.ToolContext ctx) {
        return guard(() -> {
            MarketScopePolicy.check(args);
            return doExecute(args, ctx);
        });
    }

    protected abstract JsonNode doExecute(JsonNode args, com.cexpilot.runtime.ToolContext ctx);

    protected ToolResult guard(Supplier<JsonNode> body) {
        try {
            return ToolResult.success(body.get());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("参数错误: " + e.getMessage());
        } catch (ExchangeException e) {
            return ToolResult.failure("交易所数据获取失败: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("工具内部异常: " + e.getMessage());
        }
    }

    protected Exchange parseExchange(JsonNode args) {
        return Exchange.parse(args.path("exchange").asText());
    }

    protected String parseBase(JsonNode args) {
        return SymbolMapper.normalize(args.path("symbol").asText(null));
    }

}
