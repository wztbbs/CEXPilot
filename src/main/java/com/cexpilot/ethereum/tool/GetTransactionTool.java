package com.cexpilot.ethereum.tool;

import com.cexpilot.ethereum.TxAnalysisService;
import com.cexpilot.runtime.AgentTool;
import com.cexpilot.runtime.ToolContext;
import com.cexpilot.runtime.ToolResult;
import com.cexpilot.runtime.ToolSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 以太坊交易分析工具：输入 txHash，返回结构化的交易事实
 * （状态、费用、Token 转账、授权、资金流），供 LLM 解释。
 */
@Component
public class GetTransactionTool implements AgentTool {

    private final TxAnalysisService analysis;

    public GetTransactionTool(TxAnalysisService analysis) {
        this.analysis = analysis;
    }

    @Override
    public String name() {
        return "get_transaction";
    }

    @Override
    public String description() {
        return "分析一笔以太坊主网交易：输入交易哈希，返回执行状态、Gas 费用、ERC20 转账、授权（含无限额度风险提示）、Swap 事件与资金流等结构化事实。用户给出 0x 开头的 64 位交易哈希时使用";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.parse("""
                {"type": "object", "properties": {
                "tx_hash": {"type": "string", "description": "以太坊交易哈希，0x 开头 64 位十六进制"}
                }, "required": ["tx_hash"]}
                """);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            return ToolResult.success(analysis.analyze(args.path("tx_hash").asText(null)));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("链上数据获取失败: " + e.getMessage());
        }
    }
}
