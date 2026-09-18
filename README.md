# CEXPilot — Crypto / CEX AI Copilot（Phase 1）

一个带 Agent 交互层的 CEX 后端系统。Phase 1 提供查询能力：

- **Market Investigator**：自然语言查询 Binance / OKX 永续合约行情（价格变化、Funding、OI、盘口、两所对比）
- **Ethereum Tx Investigator**：输入交易哈希，分析转账 / 授权 / Swap / 资金流
- 支持多轮追问；每次问答全链路 Trace 落库；支持 👍/👎 反馈与 Smoke Eval

核心原则：**LLM 只负责理解意图、选择工具、解释结果；所有金融数字由确定性代码计算。**

## 架构一句话

```
用户问题 → AgentRuntime（有界 tool-calling 循环）
        → ToolRegistry 里的工具（内部是确定性取数+计算，返回结构化事实）
        → LLM 基于事实生成答案 → 答案 + Evidence + Trace（MySQL）
```

概念:
| 概念                    | 解决什么                          |
| --------------------- | ----------------------------- |
| Conversation          | 用户前后几轮对话属于同一个上下文吗             |
| Execution             | 一次 Query 的执行生命周期              |
| Plan                  | 这次 Execution 准备做哪些事情         |
| Step                  | Plan 中一个执行单元                  |
| Checkpoint            | Execution 执行到了哪里              |
| Trace                 | Execution 实际发生了什么            |
| Context               | 当前 Execution 从 Conversation 中继承什么信息 |
| Memory                | 跨 Conversation 保存什么           |


包结构与职责对应关系：

| 包 | 职责 |
|---|---|
| `runtime` | AgentRuntime / ToolRegistry / TraceSink（Agent 骨架） |
| `llm` | OpenAI 兼容客户端（function calling） |
| `market` | Binance / OKX 客户端、数据标准化、确定性计算、8 个行情工具 |
| `ethereum` | RPC 客户端、ABI 事件解码、交易资金流分析 |
| `conversation` | 对话最近几轮上下文（不做长期记忆） |
| `trace` / `feedback` / `eval` | Trace 落库与回放 / 用户反馈 / Smoke Eval |

## 启动

```bash
# 1. 建库（表结构启动时自动初始化）
mysql -e "CREATE DATABASE IF NOT EXISTS cexpilot_test DEFAULT CHARSET utf8mb4"

# 2. 配置环境变量（普通模型 Key 必填，公共行情不需要交易所 Key）
export LLM_NORMAL_API_KEY=sk-xxx    # LLM_NORMAL_BASE_URL 默认 DashScope 兼容模式，LLM_NORMAL_MODEL 默认 qwen-plus
# 旗舰模型按需配置：LLM_FLAGSHIP_API_KEY / LLM_FLAGSHIP_MODEL（默认 qwen-max）/ LLM_FLAGSHIP_BASE_URL
export DB_HOST=127.0.0.1 DB_USER=root DB_PASSWORD=xxx

# 3. 运行
mvn spring-boot:run -Dspring-boot.run.profiles=test   # 测试环境
java -jar target/cexpilot-0.1.0.jar --spring.profiles.active=prod   # 线上
```

> Binance / OKX 的 API 在大陆网络不可直连。境内部署时用 `BINANCE_BASE_URL` / `OKX_BASE_URL` 环境变量指向代理或网关。

## 调用示例

```bash
# 行情问答
curl -X POST localhost:8080/api/ask -H 'Content-Type: application/json' \
  -d '{"question": "BTC 最近 1 小时怎么了？"}'

# 多轮追问（带上次返回的 conversationId）
curl -X POST localhost:8080/api/ask -H 'Content-Type: application/json' \
  -d '{"conversationId": "<conversation_id>", "question": "那 OKX 呢？"}'

# 以太坊交易分析
curl -X POST localhost:8080/api/ask -H 'Content-Type: application/json' \
  -d '{"question": "分析这笔交易：0xabad7bc4a2320b6ae713d9c4694540f3c47a4fb5a6bb64e3ccd7d5a16c4224a6"}'

# 👎 反馈 → Trace 回放
curl -X POST localhost:8080/api/feedback -H 'Content-Type: application/json' \
  -d '{"traceId": "<trace_id>", "rating": "down", "category": "data_error"}'
curl localhost:8080/api/trace/<trace_id>

# Smoke Eval（真实调用 LLM 与交易所 API）
curl -X POST 'localhost:8080/api/eval/run'           # 全部 25 条 case
curl -X POST 'localhost:8080/api/eval/run?category=market'
```

## 测试

```bash
mvn test   # 44 个纯单元测试，不依赖网络与数据库
```
