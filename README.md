# CEXPilot — Crypto / CEX AI Copilot（Phase 1）

一个带 Agent 交互层的 CEX 后端系统。Phase 1 提供查询能力：

- **Market Investigator**：自然语言查询 Binance / OKX 永续合约行情（价格变化、Funding、OI、盘口、两所对比）
- **Ethereum Tx Investigator**：输入交易哈希，分析转账 / 授权 / Swap / 资金流
- 支持多轮追问；每次问答全链路 Trace 落库；支持 👍/👎 反馈与 Smoke Eval

核心原则：**LLM 只负责理解意图、选择工具、解释结果；所有金融数字由确定性代码计算。**

## 架构一句话

```
用户问题 → DagPlanner（领域判断、意图归类、查询规划）
        → DagExecutor 并行执行 ToolRegistry 中的工具（确定性取数与计算）
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
| `runtime` / `dag` | YAML 工具注册、规划、参数校验、DAG 执行与事实回答 |
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

# 流式问答（SSE：meta → delta × N → done / error），前端逐字出答案
curl -N -X POST localhost:8080/api/ask/stream -H 'Content-Type: application/json' \
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

# 按时间窗口批量捞 Trace 排查问题（含每条的 events 和 feedback；窗口最长 24h，beginHour 必须大于 endHour）
curl 'localhost:8080/api/traces?beginHour=4&endHour=0'     # 最近 4 小时
curl 'localhost:8080/api/traces?beginHour=12&endHour=8'    # 12 小时前 到 8 小时前

# Smoke Eval（真实调用 LLM 与交易所 API）
curl -X POST 'localhost:8080/api/eval/run'           # 全部 25 条 case
curl -X POST 'localhost:8080/api/eval/run?category=market'
```

## 测试

```bash
mvn clean test   # 单元与配置集成测试，不调用外部 LLM、交易所或数据库
```

## 工具配置与提示词

工具元数据的唯一来源是 `src/main/resources/tools/*.yml`。Java `AgentTool` 只提供绑定名称和执行逻辑，不再包含 description 或参数 schema。

| 配置字段 | 用途 | 是否传给 LLM |
| --- | --- | --- |
| tool `name` | 绑定同名 Java 执行器；重复、缺失绑定启动报错 | 是 |
| tool `enabled` | 控制注册与可执行性；false 时不暴露、不执行 | 不直接传入 |
| tool `description` | 简短能力及关键限制 | 是 |
| tool `input_schema` | 参数校验及默认值补齐 | 以精简参数说明传入；相同参数定义合并 |
| tool `capabilities` / `limitations` / `scope` | 维护文档；需要模型知道的限制须写入 description | 否 |
| intent `name` / `description` | 意图归类，仅用于统计 | 是 |
| intent 其余字段 | 文档参考，不限制工具调用或控制回答策略 | 否 |

`input_schema` 当前支持扁平对象：`type`、`properties`、`required`、`additionalProperties`，以及参数的 `type`（string/integer/number/boolean）、`description`、`enum`、`default`、`minimum`、`maximum`、`pattern`。不支持的 schema 字段或无效默认值会在启动时失败，避免配置被静默忽略。规划时校验具体参数；执行时在上游引用解析后再次校验并补齐默认值。默认值只补缺省字段，不替换显式 null。

配置随应用在启动时加载，修改后需要重新构建并重启，不支持热更新。参数键名须与执行器读取的键一致；修改参数契约时仍需同步执行逻辑。提示词版本包含实际渲染的工具、意图、规划约束和回答模板，便于追踪配置变更。

第一次调用使用 `prompts/dag_planner.txt`，只规划必要查询。部分可查询时保留可用计划并说明其余缺口；序列和明细由对应工具返回，无需明细保留开关；只需统计指标时优先选择统计工具。近期成交返回 `limit` 范围内实际取得的全部记录（最多 100 笔）。

第二次调用统一使用 `prompts/agent_system.txt`，仅根据 query 和本轮 FACTS 回答。历史只用于消解指代。无计划时直接返回缺口说明或追问，不调用回答模型；查询失败和单侧数据缺失同样需要明确说明。工具返回的完整 evidence 直接作为 FACTS，不再按数组长度裁剪明细。各工具自身的查询预算和返回上限仍然适用。

Answer 的 `TIME_CONTEXT` 与工具共用请求开始时固定的时间基准，包含 UTC 时刻、请求时区及当地日期时间；未提供用户时区时采用 UTC+8，并标注为默认值。单个查询显式指定的时区仍以工具返回区间为准。回答阶段不重新计算“昨天”，也不凭模型记忆判断当前日期；时间合法性、覆盖及未收盘状态依据工具结果说明。Answer trace 同时记录这份时间上下文，便于复查跨时区、跨午夜问题。

`get_klines`、`get_market_statistics`、`get_mark_price_history`、`get_mark_price_statistics` 共用缺省粒度策略：用户指定 `interval` 时遵守，未指定时省略参数（不填写 `auto`），由 QueryService 在时间解析后选择。按数据源支持的粒度从细到粗检查对齐后的根数，优先选择不超过 1500 根且不超过总预算的粒度；都超过目标时使用最粗可用粒度，仍需通过总预算和保留期校验。结果的 `candle_interval` 是实际粒度，`interval_source` 标注 automatic/explicit；实际截止时间以 `coverage.covered_until` 和统计的 `actual_range` 为准，不能将最近已收盘数据说成实时结果。同范围、同边界的完整 OHLC 聚合不会因较细粒度而更精确，但粒度影响走势细节和边界贴合程度。OI 等瞬时采样工具不套用此规则。

主动买卖成交量（taker）查询中，Source 在计算范围两端各多取一个 5m 周期，并在分页交界保留重叠、按周期起点去重；外扩受请求时间和历史保留期限制。QueryService 使用公共 `SeriesRangeFilter` 裁回 `effectiveRange`，再调用覆盖校验器，区间内的错位、缺口及分页中止仍会抑制统计输出。扩大取数不会扩大统计范围，也不会把余量计入预期条数；分页页数预算不变，重叠会占用部分容量。其他数据源尚未迁移到这套外扩取数流程。
