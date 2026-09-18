# Crypto / CEX AI Copilot：开发、验证与传播路线图

> 目标：做一个**可公开访问、可真实体验、可持续迭代**的 Crypto / CEX AI 产品。  
> 它既能服务于 **CEX Backend 求职**，又能体现 **Agent 工程能力**，同时保留未来做开源、顾问、私有化部署或产品化的可能性。
>
> 核心定位不是“做一个会 Crypto 的聊天机器人”，而是：
>
> **一个拥有 Agent 交互层的 CEX / Financial Backend System。**
>
> 技术主线：
>
> **CEX / Trading / Financial Backend 是主体，Agent 是第二能力轴。**
>
> 核心设计原则：
>
> **Read Path 可以适度 Agentic；Write Path 必须 Deterministic。**

---

# 0. 总体目标与原则

## 0.1 产品定位

项目名称可暂定：

**Crypto / CEX AI Copilot**

产品最终包含四类能力：

1. **Market Investigator**
   - 查询 Binance / OKX 实时市场数据。
   - 回答价格变化、成交量、Funding、OI、OrderBook 等问题。
   - 支持多轮追问。

2. **On-chain Transaction Investigator**
   - 输入 Ethereum Transaction Hash。
   - 分析交易行为、Token Transfer、Approve、Swap、资金流等。
   - 支持多轮追问。

3. **Order / Position Investigator**
   - 分析 Demo 订单为什么未成交、部分成交、被取消。
   - 分析 Position、PnL、Funding、Fee、Mark Price 等。

4. **Trading Agent**
   - 使用 OKX Demo Trading。
   - 从自然语言生成 Trading Intent。
   - Risk Check。
   - Human Approval。
   - 安全执行。
   - 处理幂等、超时、重复执行、恢复、Reconciliation。

---

## 0.2 项目真正的技术核心

项目不以“用了多少 Agent 框架”为含金量标准。

真正需要证明的是：

- 如何设计 Tool 边界。
- 如何控制 Context。
- 如何保证数据与金融语义正确。
- 如何建立 Eval。
- 如何处理 Tool 失败与 Partial Result。
- 如何把线上 Failure 固化为 Regression Case。
- 如何让概率型 LLM 安全调用有副作用的金融系统。
- 如何设计权限、审批、审计、幂等、状态机与恢复。

核心原则：

```text
LLM
负责：
- 理解用户意图
- 选择信息
- 生成结构化 Intent
- 解释结果

确定性代码
负责：
- 金额计算
- PnL
- 风险规则
- 数据标准化
- 权限判断
- 幂等
- 状态机
- 下单
- Reconciliation
```

---

## 0.3 不做什么

至少前三个阶段内，不主动做：

- 不把项目做成 Multi-Agent 系统。
- 不为了“高级感”引入 CrewAI 等多 Agent 框架。
- 不追求开放式无限 Agent Loop。
- 不在早期引入 Mem0 / Letta。
- 不为了简历强行引入 MCP。
- 不做生产级 Solidity / DeFi Protocol 开发。
- 不支持十几个交易所。
- 不一开始就做完整交易平台。
- 不使用真钱进行 Trading Agent 测试。

---

# 1. 总体里程碑

整个项目分三个大阶段：

```text
Phase 1
Build & Smoke Eval
查询功能 + 最小 Eval

        ↓

Phase 2
Launch & Learn
公开发布 + 真实用户 + Failure Dataset + Regression Eval

        ↓

Phase 3
Act Safely
操作类能力 + OKX Demo Trading + 安全执行
```

三个阶段分别回答三个问题：

### Phase 1

> Agent 能不能比较可靠地回答问题？

### Phase 2

> 我们怎么证明它越来越可靠，而不是“感觉好像变好了”？

### Phase 3

> 即使 Agent 会犯错，怎么保证它也不能把金融系统搞坏？

---

# 2. Phase 1：查询功能 + Smoke Eval

## 2.1 阶段目标

第一阶段的目标不是做“大而全 Agent”。

目标是：

> **尽快做出一个可公开访问、真实数据驱动、可测试、可 Trace 的查询型产品。**

第一阶段应该保持架构简单。

不要为了“Agent”两个字主动增加复杂度。

---

# 3. Phase 1 功能列表

## 3.1 V0.1：Market Investigator MVP

第一版只支持：

- Binance
- OKX

不接：

- Bybit
- Gate
- Coinbase
- 其他交易所

Market 第一版支持：

- BTC
- ETH
- 可扩展其他主流 Symbol

时间窗口：

- 1h
- 4h
- 24h

数据：

- Kline
- Volume
- Funding Rate
- Open Interest

示例问题：

```text
BTC 最近 1 小时怎么了？

BTC 最近为什么下跌？

Binance 和 OKX 的 BTC 走势有没有明显差异？

Funding 最近有什么变化？

OI 上升但价格下跌意味着什么？
```

---

## 3.2 V0.2：增强 Market Evidence

增加：

- OrderBook
- Recent Trades
- Mark Price
- Index Price

增加两个交易所之间的：

- Symbol 映射
- Timestamp 对齐
- 数据 freshness
- 数据标准化
- 异常差异检测

---

## 3.3 V0.3：多轮追问

支持 Conversation Context。

例如：

```text
用户：
BTC 最近为什么跌？

系统：
...

用户：
那 OKX 呢？

系统需要知道：
- “那”指 BTC
- 时间范围仍然是上一轮
- 用户正在比较 Binance / OKX
```

这一阶段需要的是：

**Working Context / Conversation Context**

不需要 Long-term Memory。

---

## 3.4 V0.4：Ethereum Transaction Investigator

输入：

```text
chain = ethereum
txHash = 0x...
```

支持：

- Transaction
- Receipt
- Logs
- ERC20 Transfer
- ERC20 Approve
- Swap 基础识别
- Token Metadata
- 基础资金流

支持追问：

```text
这笔交易做了什么？

为什么有 Approve？

这个 Approval 有没有风险？

钱最后去了哪里？

为什么交易失败还扣 Gas？
```

---

# 4. Phase 1 查询流程

第一阶段核心流程本质上可以是一个简单 DAG。

例如 Market Investigator：

```text
用户问题
   ↓
解析：
- symbol
- timeframe
- intent
   ↓
生成查询计划
   ↓
并行查询
┌───────────────┬───────────────┬───────────────┐
Kline           Funding         OI / Volume
└───────────────┴───────────────┴───────────────┘
   ↓
Binance / OKX 数据标准化
   ↓
确定性计算
   ↓
Evidence Pack
   ↓
LLM 解释
   ↓
答案 + Evidence + Trace
```

这里不需要为了 Agent 强行做复杂 Planning。

---

# 5. Phase 1 关键技术

## 5.1 后端

建议：

- Java
- Spring Boot
- PostgreSQL
- Redis：可选，必要时再加
- Docker
- Nginx

第一阶段不一定需要：

- Kafka
- MQ
- Event Sourcing

不要为了“生产级”提前把所有基础设施堆进来。

---

## 5.2 LLM

使用模型官方 SDK。

第一阶段自己实现轻量 Agent Runtime。

核心接口例如：

```java
interface AgentTool {
    String name();
    String description();
    JsonSchema inputSchema();
    ToolResult execute(ToolRequest request);
}
```

核心组件：

```text
AgentRuntime
ToolRegistry
ContextStore
ModelGateway
TraceSink
```

第一阶段实现：

```text
AgentRuntime = SimpleAgentRuntime
ToolRegistry = LocalToolRegistry
ContextStore = PostgreSQL
TraceSink = OpenTelemetry / DB
```

---

## 5.3 Tool Calling

第一阶段必须掌握：

- Tool Schema
- Tool Selection
- Tool Result
- Max Tool Calls
- Timeout
- Retry
- Partial Failure
- Tool Result → Context

必须自己思考的问题：

- Tool 粒度应该多大？
- 原始 API JSON 是否全部进入 Context？
- 哪些字段应该先结构化？
- 一个问题最多允许调用多少 Tool？
- Tool Timeout 后是 Retry、Degrade 还是直接失败？

---

## 5.4 Context Engineering

第一阶段重点。

不要一开始引入 Mem0。

需要设计：

```text
Raw Tool Result
       ↓
Structured Facts
       ↓
Evidence Pack
       ↓
Context Selection
       ↓
LLM
```

需要回答：

- 哪些事实进入 Prompt？
- 哪些数据只存 Trace？
- 上一轮 Context 保存多久？
- 哪些数据有 TTL？
- 哪些事实可以复用？
- 哪些实时数据必须重新查询？

---

## 5.5 Observability

第一阶段就应该做基础 Trace。

推荐：

- OpenTelemetry
- 自己的 Trace 表

至少记录：

```text
traceId
conversationId
model
promptVersion
toolCalls
toolInput
toolOutput
duration
tokenUsage
cost
error
finalAnswer
userFeedback
```

目标：

用户点一个 👎 后，可以完整回放：

> 当时到底调用了哪些 Tool、拿到了什么数据、模型为什么可能回答错。

---

# 6. Phase 1 暂时不要引入的框架

## 6.1 LangGraph

第一阶段：

**不需要。**

原因：

- DAG 简单。
- 自己实现更容易理解 Agent 的核心机制。
- 避免框架隐藏状态、Context、Tool Routing 等关键细节。

LangGraph 的最佳引入时间：

> Phase 2，当真实 Workflow 已经开始复杂到自己维护比较痛苦时。

---

## 6.2 MCP Server

第一阶段：

**不需要。**

第一阶段先：

```text
Agent
 ↓
MarketTool
 ↓
BinanceAdapter / OkxAdapter
```

重点先确定：

- Tool 是否应该存在。
- Tool 输入输出是什么。
- Tool 边界是否合理。

等边界稳定后再做 MCP。

---

## 6.3 Mem0 / Letta

第一阶段：

**不要引入。**

当前需求是：

- Working Context
- Conversation Context

不是：

- Cross-conversation Long-term Memory

只有真的出现长期偏好：

```text
Preferred Exchange = OKX
Max Order = 1000 USDT
Require Approval > 500 USDT
Default Leverage = 2x
```

才值得研究 Mem0 / Letta。

---

## 6.4 Multi-Agent

第一阶段不用。

前三阶段都默认不用。

不要设计：

```text
Market Agent
Risk Agent
Trading Agent
Supervisor Agent
```

金融系统应该优先是：

```text
MarketService
RiskEngine
OrderService
ExecutionService
```

而不是多个 Agent 互相讨论。

---

# 7. Phase 1 Smoke Eval

## 7.1 初始规模

先准备：

**30～50 个 Case**

分类：

```text
evals/
├── market/
│   ├── price-change
│   ├── funding
│   ├── oi
│   └── exchange-divergence
│
├── ethereum/
│   ├── eth-transfer
│   ├── erc20-transfer
│   ├── approve
│   ├── swap
│   └── failed-tx
│
└── conversation/
    └── context-follow-up
```

---

## 7.2 Eval 三层结构

### 第一层：Deterministic Correctness

程序直接断言。

例如：

- 涨跌幅计算是否正确。
- Funding 单位转换是否正确。
- Token decimals 是否正确。
- Transfer amount 是否正确。
- Event Decode 是否正确。

不使用 LLM Judge。

---

### 第二层：Agent Behavior

检查 Tool Selection。

例如：

```yaml
question: "BTC 最近一小时价格变化多少？"

expected_tools:
  - ticker
  - kline

optional_tools:
  - recent_trades

forbidden_tools:
  - place_order
```

---

### 第三层：Answer Quality

检查：

- 是否回答了问题。
- 是否引用正确证据。
- 是否遗漏重要信息。
- 是否把相关性误写成因果关系。
- 数据不足时是否明确表达不确定性。

组合方式：

- Rules
- LLM Judge
- 人工抽查

---

# 8. Phase 1 的关键“人肉决策”

以下问题不能直接交给 Codex 自动决定。

## Market

- “最近一小时”如何定义。
- Binance / OKX Kline 如何对齐。
- Funding 用当前值还是趋势。
- OI 上升 + 价格下降如何解释。
- OrderBook Snapshot 能否解释过去一小时价格变化。
- 两个交易所数据冲突时如何回答。
- 什么情况下只能说“相关”，不能说“导致”。

## Ethereum

- 哪些 Log 代表真正业务动作。
- Multicall 如何展示。
- Proxy Contract 如何识别。
- 未 Verified ABI 怎么处理。
- Unknown Contract 如何降级。
- Token Metadata 异常怎么办。

这些决策需要写 ADR。

---

# 9. Phase 1 建议的 ADR

目录：

```text
docs/adr/
```

建议：

```text
ADR-001-llm-vs-deterministic-calculation.md
ADR-002-market-evidence-model.md
ADR-003-tool-granularity.md
ADR-004-context-selection.md
ADR-005-partial-result-policy.md
ADR-006-cross-exchange-data-normalization.md
ADR-007-correlation-vs-causality.md
ADR-008-eval-strategy.md
```

每篇包含：

1. 问题
2. 备选方案
3. 决策
4. 原因
5. Trade-off
6. 后续风险

---

# 10. Phase 1 区块链知识要求

不要求达到生产级 Smart Contract Engineer 水平。

目标：

> **Blockchain Integration Engineer 的理解水平。**

需要掌握：

## 基础对象

```text
Block
Transaction
Receipt
Log / Event
Address
Contract
```

## Transaction

理解：

```text
from
to
value
input
nonce
gas
gasPrice
EIP-1559 fee
```

## ERC20

至少掌握：

```text
transfer
approve
transferFrom
allowance
unlimited approval
```

## ABI / Event

理解：

- Calldata Decode
- Function Selector
- Event Topic
- Log Data

## Contract Interaction

理解：

```text
EOA
Contract
Proxy
Implementation
DelegateCall
View Call
State-changing Call
```

## DeFi 基础

理解：

```text
DEX
AMM
Liquidity Pool
Swap
Router
Slippage
```

了解：

- Uniswap
- 1inch
- Aave
- Lido
- Bridge

无需深入：

- Uniswap V3 复杂数学
- 协议级 Solidity 安全开发

## CEX 更相关的链知识

后续重点：

```text
Deposit
Confirmation
Reorg
Finality
Hot Wallet
Cold Wallet
HD Wallet
Nonce Management
Sweep
Withdrawal
Broadcast
```

---

# 11. Phase 1 发帖计划

第一阶段宣传目标：

> 不是直接找老板，而是获得真实 Workload 和真实 Failure。

---

## 帖子 1：产品第一次公开

### 标题方向

英文：

> I built an AI-powered Crypto Market Investigator using real Binance and OKX data

中文：

> 我做了一个可以直接分析 Binance / OKX 实时行情的 AI Crypto Investigator

### 内容重点

展示：

- 输入一个自然语言问题。
- 系统自动查真实数据。
- 展示 Evidence。
- 最终由 LLM 解释。
- 所有数字由代码计算，不让 LLM 心算。

附：

- Live Demo
- GitHub

---

## 帖子 2：Ethereum Transaction Investigator

英文：

> I built a tool that explains real Ethereum transactions in plain English — and you can keep asking where the money went

内容：

- 粘贴真实 txHash。
- 自动识别 Transfer / Approve / Swap。
- 可以继续追问。

展示：

- 一笔真实复杂交易。
- 资金流图。
- Agent Trace。

---

## 帖子 3：工程思路

题目：

> Why I don’t let the LLM calculate financial numbers in my Crypto Agent

内容：

- LLM 负责理解与表达。
- 金融计算由代码完成。
- Evidence Pack。
- Grounded Answer。
- Eval。

这类文章同时有：

- 招聘价值
- 技术传播价值

---

# 12. Phase 1 毕业条件

Phase 1 不无限打磨。

达到以下条件即可进入 Phase 2：

- Public Live Demo 可以访问。
- Binance / OKX Market Investigator 可稳定使用。
- Ethereum Tx Investigator 有最小可用版本。
- 支持多轮追问。
- 有基础 Trace。
- 有 Feedback。
- 30～50 个 Smoke Eval Case。
- 核心 Deterministic Eval 基本稳定通过。
- 具备初始 README / Architecture / ADR。

---

# 13. Phase 2：公开发布 + 真实用户 + Eval 体系化

## 13.1 阶段目标

第二阶段真正的核心不是：

> Star。

而是：

> **Failure Dataset。**

产品开始接受真实用户输入。

形成闭环：

```text
上线
 ↓
用户使用
 ↓
真实 Failure
 ↓
人工分析
 ↓
固化为 Eval Case
 ↓
修复
 ↓
Regression Eval
 ↓
再次上线
```

---

# 14. Phase 2 用户反馈系统

答案底部：

```text
👍 Accurate
👎 Something is wrong
```

点击 👎：

```text
错误类型：

□ 数据错误
□ 推理错误
□ 漏掉重要信息
□ 没理解问题
□ 追问上下文错误
□ Tool 调用异常
□ 其他
```

允许用户补充文字。

---

# 15. Phase 2 Eval 升级

Smoke Eval 升级为 Regression Eval。

逐步积累：

- 100+ 真实 Query
- 30～50 个真实 Failure Case
- 后续持续增长

真实 Failure 固化：

```text
evals/
├── market/
├── onchain/
├── conversation/
└── failures/
```

每个 Case 记录：

```text
userQuery
timeRange
marketSnapshot
expectedFacts
requiredEvidence
forbiddenClaims
expectedToolBehavior
failureType
```

---

# 16. Phase 2 重点评估指标

## Quality

- Tool Selection Accuracy
- Deterministic Accuracy
- Groundedness
- Task Success Rate
- Follow-up Context Accuracy

## Reliability

- Tool Error Rate
- Partial Result Rate
- Timeout Rate
- Retry Rate

## Performance

- P50 / P95 Latency
- Token Usage
- Cost / Query
- Tool Calls / Query

## User Feedback

- Positive Feedback Rate
- Negative Feedback Category
- Repeat User Rate

---

# 17. Phase 2 框架引入策略

这一阶段才开始问：

> 哪些框架可以真正降低已经出现的复杂度？

---

## 17.1 LangGraph

如果已经出现：

```text
大量 if / else
复杂 routing
checkpoint
conditional workflow
multi-step retry
resume
```

此时可以引入 LangGraph。

目标不是：

> “简历上写熟悉 LangGraph。”

而是：

> 用它解决已经真实出现的 orchestration complexity。

最好保留一篇 Migration 文档：

> Why I migrated from a custom agent loop to LangGraph.

---

## 17.2 MCP

如果 Tool Boundary 已经稳定，可以选择一个模块做 MCP Server。

推荐：

```text
market-mcp
```

包含：

```text
getTicker
getKline
getFundingRate
getOpenInterest
getOrderBook
```

此时研究：

- MCP Tool Schema
- Tool Discovery
- Tool Metadata
- Read-only Permission
- Tool Versioning

不要为了 MCP 重构整个系统。

---

## 17.3 Eval Framework

如果 Case 已经达到几百条，可以再评估：

- LangSmith
- DeepEval
- Ragas
- 自研 Runner

重点仍然不是工具名字，而是：

> Dataset 与判定标准是否合理。

---

## 17.4 Mem0 / Letta

仍然可以不引入。

如果真实用户确实产生跨 Conversation 偏好需求，再引入。

原则：

> 不为了学习 Memory 而人为制造 Memory 需求。

---

# 18. Phase 2 发帖计划

这一阶段帖子不再只是“产品发布”。

重点转向：

> 真实问题、失败、修复、Eval。

---

## 帖子 1

> What 100 real Crypto queries taught me about Agent evaluation

内容：

- 用户问了哪些奇怪问题。
- Agent 哪些地方最容易错。
- 如何变成 Regression Case。

---

## 帖子 2

> Binance and OKX disagreed — how should an AI market agent answer?

内容：

- 两个交易所数据不同。
- 不能简单相信一个数据源。
- 如何构建 Evidence Pack。

---

## 帖子 3

> Why I didn’t use LangGraph in V1 — and what finally made me adopt it

内容：

- V1 自研 Loop。
- 复杂度在哪里增长。
- LangGraph 真正解决了什么。

---

## 帖子 4

> A real Ethereum transaction my agent failed to explain correctly

内容：

- Failure。
- Root Cause。
- Eval Case。
- 修复方案。

这种帖子传播价值通常高于：

> “今天又实现了一个 Feature。”

---

# 19. Phase 2 毕业条件

进入 Phase 3 前，希望达到：

- 产品有真实用户。
- 已积累 100+ 有价值 Query。
- 至少 30～50 个真实 Failure Regression Case。
- 每次修改都会跑 Regression Eval。
- Trace 能定位绝大部分问题。
- Tool / Context / Eval 设计已经比较稳定。
- 有一些真实 GitHub Issue / Feedback。
- Query Agent 进入稳定迭代状态。

---

# 20. Phase 3：操作类 Agent + Safe Execution

## 20.1 阶段目标

第三阶段才是整个项目最能体现：

> **CEX Backend 深度**

的阶段。

核心问题：

> 如何让概率型、可能重复执行、可能误判的 LLM，安全操作一个不能重复下单、不能算错钱的金融系统？

---

# 21. Phase 3 第一版只做一个操作

不要一开始做完整 Trading。

只做：

> **OKX Demo Trading 的限价下单。**

示例：

```text
用户：
在 BTC 当前价格下方 1% 挂一个 1000 USDT 的限价买单。
```

流程：

```text
自然语言
   ↓
LLM
   ↓
TradingIntent
   ↓
Validate
   ↓
Risk Check
   ↓
Execution Plan
   ↓
Human Approval
   ↓
Market Refresh
   ↓
Risk Recheck
   ↓
Submit
   ↓
Reconcile
   ↓
Order Status
   ↓
LLM 解释
```

---

# 22. Write Path 设计原则

Write Path 不允许使用开放式 Agent Loop。

不是：

```text
LLM
 ↓
placeOrder
 ↓ timeout
LLM
 ↓
retry placeOrder
```

而是：

```text
LLM
 ↓
TradingIntent
 ↓
Deterministic Execution Engine
```

核心原则：

> **Approval 之后，LLM 退出最终执行决策链。**

---

# 23. Phase 3 状态机

此时才正式引入业务状态机。

例如：

```text
CREATED
   ↓
VALIDATED
   ↓
RISK_CHECKED
   ↓
WAITING_APPROVAL
   ↓
APPROVED
   ↓
RISK_RECHECK
   ↓
SUBMITTING
   ↓
SUBMITTED
   ↓
PARTIALLY_FILLED / FILLED / CANCELLED / FAILED
```

特殊状态：

```text
SUBMISSION_UNKNOWN
```

这是非常重要的状态。

---

# 24. Phase 3 关键技术问题

## 24.1 用户重复确认

用户点两次 Confirm。

不能产生两个订单。

需要：

```text
intentId
clientOrderId
exchangeOrderId
```

保证：

> 一个 TradingIntent 对应唯一业务订单。

---

## 24.2 Tool 重复调用

LLM 调用：

```text
place_order(intentId)
```

如果 Tool Timeout 后 LLM 再调用一次：

```text
place_order(intentId)
```

Execution Service 必须识别：

> 这是同一个 Intent。

不能生成第二张订单。

---

## 24.3 下单请求 Timeout

最关键 Case：

```text
POST order
 ↓
timeout
```

此时不知道：

- OKX 没收到
- OKX 已成功下单但响应丢失

不能直接 Retry。

进入：

```text
SUBMISSION_UNKNOWN
```

然后：

```text
clientOrderId
   ↓
Query Order
   ↓
Reconciliation
```

---

## 24.4 Human Approval 后市场变化

例如：

```text
14:00
BTC = 100000
```

用户两分钟后确认：

```text
14:02
BTC = 104000
```

必须：

```text
Market Refresh
 ↓
Risk Recheck
 ↓
Price Deviation Check
```

必要时重新审批。

Approval 应该批准：

> 一个明确、版本化、不可变的 Execution Plan。

而不是批准一句自然语言。

---

## 24.5 Crash Recovery

场景：

```text
系统调用 OKX 成功
 ↓
还没写本地状态
 ↓
进程 Crash
```

恢复后不能直接再下单。

必须通过：

- clientOrderId
- Exchange Query
- Reconciliation

恢复状态。

---

## 24.6 Partial Fill

必须支持：

```text
NEW
PARTIALLY_FILLED
FILLED
CANCELLED
```

分析：

- Remaining Qty
- Avg Fill Price
- Fee
- PnL

---

# 25. Phase 3 MCP

第三阶段是 MCP 收益最高的阶段。

此时 Tool 已经分为：

```text
market-mcp
account-mcp
trading-mcp
```

例如：

```text
market-mcp
  getTicker
  getKline
  getFundingRate

account-mcp
  getBalance
  getPosition

trading-mcp
  createOrder
  cancelOrder
  queryOrder
```

Tool Permission 可以分级：

```text
READ_ONLY
ACCOUNT_READ
TRADE_WRITE
HIGH_RISK_WRITE
FORBIDDEN
```

规则：

```text
READ_ONLY
→ 自动执行

ACCOUNT_READ
→ 已授权可执行

TRADE_WRITE
→ Risk Check + Human Approval

WITHDRAWAL
→ 默认禁止
```

此时 MCP 不再只是“简历关键词”。

它真正成为：

> Tool Boundary + Permission Boundary。

---

# 26. Phase 3 Security

需要考虑：

- Prompt Injection
- Tool Injection
- Data Exfiltration
- Unauthorized Tool Call
- Prompt 绕过 Approval
- 恶意 Tool Result
- API Key Leakage
- Trace Sensitive Data

原则：

> 权限不能靠 System Prompt。

必须由确定性代码强制执行。

---

# 27. Phase 3 Chaos / Failure Lab

专门设计 Failure Case。

例如：

```text
Case 1
用户重复点击 Confirm

Case 2
LLM 重复调用 placeOrder

Case 3
下单请求 Timeout

Case 4
OKX 实际成功，但本地 Crash

Case 5
API Rate Limit

Case 6
API Key 被撤销

Case 7
订单 Partial Fill

Case 8
用户手工 Cancel

Case 9
Market 大幅变化后才 Approval

Case 10
Agent Runtime 重启后恢复任务
```

每个 Case 要定义：

- Expected Behavior
- State Transition
- Audit Record
- Recovery Action

---

# 28. Phase 3 Eval

增加：

## Action Eval

检查：

- 是否生成正确 Intent。
- Risk Check 是否正确。
- 是否触发 Human Approval。
- 是否禁止重复操作。
- Timeout 后是否进入 Unknown State。
- Recovery 是否正确。
- 是否产生重复订单。
- Audit 是否完整。

最重要指标：

> **Duplicate Financial Side Effect = 0**

---

# 29. Phase 3 发帖计划

## 帖子 1

> How I let an AI agent place demo trades without allowing duplicate orders

展示：

- TradingIntent
- clientOrderId
- 幂等
- Execution Engine

---

## 帖子 2

> Why retrying a timed-out trading API call can create a duplicate order

展示：

- Timeout Ambiguity
- SUBMISSION_UNKNOWN
- Reconciliation

---

## 帖子 3

> Human approval is not enough: why I re-check risk before execution

展示：

- Approval Drift
- Market Refresh
- Risk Recheck

---

## 帖子 4

> Read paths can be agentic. Write paths must be deterministic.

这可以成为项目最核心的一篇技术文章。

---

# 30. GitHub 项目结构

建议：

```text
crypto-cex-copilot/
├── README.md
├── README.zh-CN.md
├── LICENSE
├── CHANGELOG.md
├── CONTRIBUTING.md
│
├── docs/
│   ├── architecture.md
│   ├── market-investigator.md
│   ├── transaction-investigator.md
│   ├── eval.md
│   ├── safe-execution.md
│   ├── security.md
│   ├── deployment.md
│   │
│   └── adr/
│       ├── ADR-001-...
│       ├── ADR-002-...
│       └── ...
│
├── evals/
├── examples/
├── backend/
├── frontend/
└── deployment/
```

README 主要负责：

1. 这是什么。
2. 为什么需要。
3. Live Demo。
4. Quick Start。
5. Screenshots / GIF。
6. Architecture。
7. Benchmark / Eval。
8. Roadmap。
9. Documentation。

不要把 README 写成 API 手册。

---

# 31. 宣传渠道

控制维护成本。

## 日常

- GitHub
- X

## 阶段性

- Reddit
- V2EX
- 知乎 / 掘金

## 大版本发布

- Hacker News / Show HN
- Product Hunt

不要过早建：

- Telegram 群
- Discord 社区

等真实活跃用户出现再做。

---

# 32. 宣传内容原则

不要主要发：

> 我实现了 LangGraph / MCP / Checkpoint。

更应该发：

> 我解决了什么真实问题。

例如：

```text
“这笔 Ethereum Tx 表面是一个 Swap，
实际上包含 7 个 Action。”
```

或者：

```text
“Binance 和 OKX 的 OI 给出了不同信号，
我的 Agent 如何处理这种 Evidence Conflict？”
```

传播层展示：

> 它能干什么。

GitHub 展示：

> 怎么用。

Docs / ADR 展示：

> 为什么这么设计。

---

# 33. Codex 的正确角色

Codex 可以大量帮助写：

- CRUD
- DTO
- SDK 封装
- Controller
- Repository
- 前端页面
- 基础测试
- Docker
- CI

但项目 Owner 必须自己决定：

- Tool Boundary
- 数据语义
- Financial Invariant
- Context Policy
- Eval 标准
- State Machine
- Idempotency
- Unknown State
- Approval Semantics
- Retry Policy
- Reconciliation
- Security Boundary

工作模式：

```text
你发现问题
 ↓
你定义约束
 ↓
Codex 提供 2～3 个方案
 ↓
你做 Trade-off
 ↓
你决定
 ↓
写 ADR
 ↓
Codex 实现
 ↓
你设计 Failure Case
 ↓
Codex 补测试
 ↓
真实环境验证
 ↓
你判断是否合格
```

---

# 34. 衡量项目含金量的标准

不要用：

> 写了多少代码。

而用：

- 定义了多少真实问题。
- 发现了多少隐藏约束。
- 有多少明确的 Architecture Decision。
- 有多少真实 Failure Case。
- 有多少 Regression Eval。
- 是否有真实用户。
- 是否能安全处理异常。
- 是否能解释系统为什么这么设计。

---

# 35. 招聘定位

## 普通 CEX Backend

项目加分方向：

- CEX Domain
- Market Data
- Order Lifecycle
- Account
- Position
- Risk
- Idempotency
- Retry
- Compensation
- Reconciliation
- Audit
- High Availability

Agent 是第二能力轴。

---

## CEX × AI

项目直接命中：

- Tool Calling
- MCP
- Context
- Eval
- Agent Runtime
- Safe Execution
- Human Approval
- Permission
- Audit
- Trading / Market

---

## Agent Engineer

项目可以证明：

- Production Agent
- Context Engineering
- Tool Routing
- Eval
- Observability
- Runtime
- Safe Side Effect
- Real User Feedback Loop

---

# 36. 时间投入建议

如果全职工作比较忙，不追求高速开发。

建议：

## 工作日

- 英语 / 技术阅读：30～45 分钟

## 周末

- 一个半天做项目

## 每月

至少产出一个：

- Feature
- ADR
- Failure Analysis
- 技术文章
- Eval Improvement

目标是：

> 连续迭代，而不是短期爆肝。

---

# 37. 最终产品形态

最终架构：

```text
                   User
                    │
                    ▼
               AI Copilot
                    │
          ┌─────────┴──────────┐
          │                    │
      Read Path            Write Path
          │                    │
     Bounded Agent          Intent Only
        Loop                  │
          │                   ▼
          │            Execution Engine
          │                   │
          │          ┌────────┼────────┐
          │          │        │        │
          │        Risk    Approval  Idempotency
          │          │        │        │
          │          └────────┼────────┘
          │                   ▼
          │               Order Service
          │                   │
          ├──── Binance       │
          ├──── OKX  ◄────────┘
          └──── Ethereum
```

最终不是：

> Autonomous Multi-Agent Crypto Bot

而是：

> **Single LLM / Agent Interface + Deterministic CEX Backend + Controlled Tool Loop + Safe Execution Workflow**

---

# 38. 最重要的五条原则

## 原则一

**CEX Backend 是主体，Agent 是第二能力轴。**

## 原则二

**Read Path 可以 Agentic，Write Path 必须 Deterministic。**

## 原则三

**框架在真正出现需求以后再引入。**

不是：

```text
先学 LangGraph
再学 Mem0
再学 MCP
```

而是：

```text
先遇到问题
再用框架解决问题
```

## 原则四

**真实用户是 Eval 数据的来源。**

宣传不仅是 Marketing。

宣传实际上是：

> 获取真实 Workload 与 Failure 的工程手段。

## 原则五

**项目真正的价值不是“会调用 LLM”，而是如何把不确定的 LLM 放进一个要求确定性的金融系统中。**

---

# 39. 一句话 Roadmap

```text
Phase 1
做一个能回答真实 Crypto / CEX 问题的查询系统。

Phase 2
让真实用户把它问坏，并把 Failure 变成 Eval。

Phase 3
让 Agent 能操作真实 Demo 金融系统，同时保证它犯错也不能产生不可控副作用。
```

这就是整个项目从 Demo → Production Engineering → CEX Backend 深度的主线。
