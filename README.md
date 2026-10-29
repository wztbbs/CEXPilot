# MarketPilot

MarketPilot 是面向加密货币行情查询的 AI Copilot。用户用自然语言描述查询对象、时间和计算需求；Planner 生成指标逻辑计划，Java 编译并执行有界 DAG，回答模型根据本轮事实组织答案。取数、时间消解、完整性检查和数值计算由代码完成。

项目仓库：[wztbbs/MarketPilot](https://github.com/wztbbs/MarketPilot)。

本文及 `docs/` 描述当前工作树的实现。`importants/` 保存文章、设计过程和实验产物；其中的历史计划、能力表和示例不作为当前接口契约。

项目更名后，现有 Java 包名 `com.cexpilot`、Maven artifactId `cexpilot`、数据库名、环境变量和 Cookie 等技术标识继续沿用，文档中的命令及源码链接与当前实现保持一致。线上地址仍为 [cex.hrscrm.net](https://cex.hrscrm.net/)。

## 文档入口

建议先读 Harness 建立整体认识，再读 Plan 和上下文管理了解具体实现；时间、计算和 Trace 按需查阅。

| 文档 | 内容 |
| --- | --- |
| [CEX 领域知识学习](domain/README.md) | 7 篇业务入门文档与 42 个思考 Case，覆盖产品、价格时间、衍生品、盘口、分析、监控及跨所口径 |
| [Harness设计](docs/Harness设计.md) | 核心概念、完整链路、围栏强度、上下文与失败处理、验证方法及当前缺口 |
| [Plan与指标元数据](docs/Plan与指标元数据.md) | 指标目录、查询形态、逻辑计划、物理绑定、引用契约、校验与扩展步骤 |
| [上下文管理](docs/上下文管理.md) | frame、澄清接续、历史事实、Planner 协议、跨轮执行绑定、存储与当前边界 |
| [时间窗口](docs/时间窗口.md) | TimeSpec、固定请求时间、时区、网格对齐、分页、覆盖核对与实际统计范围 |
| [聚合指标](docs/聚合指标.md) | 成品指标与计算节点、九个算子、输入输出、精度、来源传播和年化限制 |
| [Trace搭建](docs/trace搭建.md) | Trace 生命周期、表结构、事件负载、版本指纹、回放接口与定位步骤 |
| [主动监控](docs/主动监控.md) | 受控试用的规则确认、Execution 排期、Work 调度与恢复、Resend 邮件/模拟投递、只读巡检与部署迁移 |
| [路由与监控规划](docs/路由与监控规划.md) | Plan 前独立路由、原 Query 链、Monitor 候选协议、上下文隔离与迁移 |

大模型评测暂未单独展开。

## 当前支持范围

行情主流程只接入 Binance / OKX 的 **USDT 报价、USDT 结算永续合约**。现货、交割合约、币本位或其他报价币不会自动改写为支持的产品。

能力以 [指标目录](src/main/resources/metrics/cex-perpetual.yml) 的 `metric + query_shape + exchange` 绑定为准；底层 Service 或 Tool 存在不等于 Planner 可以调用。

| 指标族 | 已接入形态 | 关键限制 |
| --- | --- | --- |
| 成交价格 `price.*` | 最新价快照、官方 24h 涨跌幅、K 线区间 OHLC / 涨跌幅及 OHLC 序列 | 成交 K 线支持 5m / 15m / 1h；区间收盘价不是实时价 |
| 成交量 / 成交额 `trade.*` | 区间成交量 / 真实成交额、成交量序列、官方 24h 数据 | 官方 24h 成交额仅 Binance；OKX 区间真实成交额可查 |
| 持仓量 `oi.*` | 当前基础币数量、数量序列、期初 / 期末 / 变化量 / 变化率 / 极值 | 历史缺省 1h；Binance 历史保留期按代码限制为最近 30 天 |
| 标记价 / 指数价 `mark.*` / `index.*` | 当前快照、区间 OHLC / 涨跌幅及 OHLC 序列 | 5m / 15m / 1h；不提供成交量、成交额 |
| 盘口 `orderbook.*` | 买一、卖一、价差、不平衡度快照 | depth 默认 20，范围 5～50；不平衡度为最多 10 档买量 / 卖量 |
| 主动买卖 `taker.*` | 区间买量、卖量、买量占比 | 当前仅 Binance，固定官方 5m 统计，不接受自选粒度 |
| 已结算费率 `funding.rate_settled` | 最近 N 期样本 | N 必填，1～100；未接入时间窗口、预测费率和下次结算时间 |

计算支持 `avg / sum / min / max / compare / difference / ratio / relative_change / annualize`，也支持用户明确给出的常量。Ethereum 分析代码及 Tool 仍在仓库中，但不在当前指标 Planner 的可规划能力内。

可查询示例：

- “分别列出币安和 OKX 的 BTC 最新成交价。”
- “昨天币安 BTC 永续成交额比 OKX 高百分之多少？”
- “币安 BTC 昨天 1 小时 K 线收盘价的等权平均是多少？”
- “币安 BTC 最近 10 期已结算资金费率的均值是多少？”

## 一次问答如何执行

```mermaid
flowchart TD
    Q[POST /api/ask 或 /api/ask/stream] --> S[AskService：固定请求时间、建立 Trace]
    M[指标目录 + 算子契约] --> P[DagPlanner：领域判断与逻辑规划]
    S --> R0[IntentRouter：query / monitor / clarify]
    R0 -->|query| P
    R0 -->|monitor| MP[MonitorPlanner：独立提示词与候选校验]
    MP --> MD[待确认规则卡片]
    MD -->|用户明确确认版本、规则与邮箱| MC[启用后台检查与邮件]
    R0 -->|clarify| RC[澄清路由，不执行]
    P --> C[MetricPlanCompiler：绑定、展开交易所、推导依赖]
    C --> V[PlanValidator：参数、引用、环、深度与预算]
    C -->|编译错误，有限修复| P
    V -->|校验错误，有限修复| P
    V --> E[DagExecutor：拓扑分层，同层并行]
    E --> R[MetricProvider → QueryService / Source / Client]
    R --> F[标准指标事实 + 确定性计算结果]
    E --> F
    F --> A[Answer：FACTS + QUERY_STATUS + TIME_CONTEXT]
    P -->|无有效计划| D[直接返回范围、缺口或澄清说明]
```

正常查询通常依次调用 Router、Query Planner、Answer。Monitor 分支通常调用 Router 和独立 Monitor Planner，将候选保存为待确认草稿并展示规则，用户核对规则和通知邮箱、点击确认后才启用后台检查。规划校验失败会增加修复调用；没有有效查询计划时不调用 Answer。模型通过 JSON 文本生成计划，各阶段均不传 API `tools` 参数。原查询 LogicalPlan 协议不变，路由与监控规划协议见上方专题文档。

指标事实统一包含身份、单位和来源；时间查询还包含覆盖状态。区间不完整时 Provider 可返回 `statistics_omitted` 而不返回 `value`，因此 `ok=true` 只表示执行成功。依赖失败或不完整数据的计算不会补算；独立分支可以继续返回结果。Answer 的提示词要求只引用已有指标和算子结果，不能自行补出差值、均值、年化等结论。

## 本地启动

先克隆仓库并进入项目目录：

```bash
git clone git@github.com:wztbbs/MarketPilot.git
cd MarketPilot
```

依赖：JDK 17、Maven、MySQL 8，以及可用的 OpenAI 兼容模型服务。版本和依赖见 [pom.xml](pom.xml)，配置见 [application.yml](src/main/resources/application.yml)。公共行情查询不需要交易所账户 Key。

以下命令从仓库根目录执行，使用 `test` profile：

```bash
mysql -u root -p -e 'CREATE DATABASE IF NOT EXISTS cexpilot_test DEFAULT CHARSET utf8mb4'

export DB_HOST=127.0.0.1
export DB_PORT=3306
export DB_NAME=cexpilot_test
export DB_USER=root
export DB_PASSWORD='<本地数据库密码>'
export LLM_NORMAL_API_KEY='<模型服务密钥>'
export LLM_NORMAL_BASE_URL='https://dashscope.aliyuncs.com/compatible-mode/v1'
export LLM_NORMAL_MODEL='qwen-plus'
export SERVER_PORT=8082

mvn spring-boot:run -Dspring-boot.run.profiles=test
```

访问 [本地问答页](http://localhost:8082/)；Trace 页为 `http://localhost:8082/trace.html?id=<traceId>`。

当前本地和服务器环境使用以下交易所代理，基础配置已将其设为默认值：

```yaml
exchange:
  binance:
    base-url: http://43.160.213.63:18080
  okx:
    base-url: http://43.160.213.63:18081
```

其他部署环境可以通过以下环境变量覆盖为可访问的代理或官方地址。监控业务不校验固定代理地址，连接异常在实际取数时报告。`application-test.yml` 固定上述代理地址，不使用这两个环境变量占位符：

```bash
export BINANCE_BASE_URL='http://43.160.213.63:18080'
export OKX_BASE_URL='http://43.160.213.63:18081'
```

OKX 请求必须携带 `User-Agent`，否则可能收到 `403 error code: 1010`。当前 OkxClient 已统一设置该请求头。历史游标方向与分页细节见 [时间窗口](docs/时间窗口.md)。

新库启动时自动执行 [schema.sql](src/main/resources/db/schema.sql) 建表。**存量库不会自动升级字段**：`CREATE TABLE IF NOT EXISTS` 不执行 ALTER，也没有自动迁移框架；部署前需核对 [migrations](src/main/resources/db/migrations) 并手工执行尚未应用的迁移，不能把所有 ALTER 重复执行。

已有 `next_query_no` 的库需应用 [010_last_query_no.sql](src/main/resources/db/migrations/010_last_query_no.sql)，将计数转换为 `last_query_no = next_query_no - 1`。先停止旧应用并等待在途问答结束，完成迁移后再启动新版本。新库直接使用 `last_query_no`，不执行这条迁移。

新增任务生命周期使用 [013_task_lifecycle.sql](src/main/resources/db/migrations/013_task_lifecycle.sql)：创建独立任务表并保存每轮版本。旧库先完成此前迁移，停旧应用后执行 013，再启动新版；新库无需执行迁移脚本。

构建及运行 JAR：

```bash
mvn clean package -DskipTests
java -jar target/cexpilot-0.1.0.jar --spring.profiles.active=test
```

`prod` 使用 [application-prod.yml](src/main/resources/application-prod.yml) 的线上数据库配置，密码来自 `MYSQL_PASSWORD`；不要将本地 `DB_*` 变量误认为可以替换该 profile 的固定数据库地址。

主动监控仅保留聊天确认入口 `/api/monitor-flow`，运维巡检为 `/api/monitor-health`。沿用已有数据库和模型配置时，模拟邮件部署只需新增 `MONITOR_ENABLED=true`、`RESEND_DRY_RUN=true`，以及巡检所需的 `MONITOR_ACCESS_TOKEN`（至少32字符）。收件邮箱由用户填写并确认，调度随应用启动，不需要额外cron。部署前按 [主动监控](docs/主动监控.md) 执行尚未应用的014～019迁移。

## 常用运行配置

| 配置 / 环境变量 | 当前默认值 | 用途 |
| --- | --- | --- |
| `server.port` / `SERVER_PORT` | 8082 | HTTP 端口 |
| `llm.normal.model` / `LLM_NORMAL_MODEL` | qwen-plus | 首次 Planner 与 Answer 使用模型 1 |
| `llm.flagship.model` / `LLM_FLAGSHIP_MODEL` | qwen-max | Planner 校验失败后，修复调用使用模型 2 |
| `LLM_FLAGSHIP_BASE_URL` / `LLM_FLAGSHIP_API_KEY` | 沿用 normal 的地址 / Key | 可独立覆盖以接入不同服务商 |
| `LLM_NORMAL_ENABLE_THINKING` | false | 模型思考开关 |
| `LLM_TEMPERATURE` / `LLM_SEED` | 0 / 不下发 | 采样参数 |
| `DAG_MAX_NODES` | 8 | 展开后的执行节点上限，还受 `llm.max-tool-calls=8` 限制 |
| `DAG_MAX_DEPTH` | 3 | 依赖深度上限 |
| `DAG_EXECUTOR_THREADS` | 4 | DAG 固定线程池大小 |
| `DAG_NODE_TIMEOUT_MS` | 30000 | 回收单节点 Future 的等待预算，不保证终止底层 HTTP |
| `DAG_PLANNER_MAX_RETRIES` | 2 | 含首次最多 3 次：模型 1 → 模型 2 → 模型 2；绑定失败仍最多修复一次 |
| `DAG_PLANNER_RESPONSE_FORMAT` | 空 | 不下发结构化约束；可选 json_object / json_schema，需模型支持 |
| `LLM_NORMAL_PRICE_INPUT_PER_1K` / `LLM_NORMAL_PRICE_OUTPUT_PER_1K` | 0 / 0 | 模型 1 成本估算单价 |
| `LLM_FLAGSHIP_PRICE_INPUT_PER_1K` / `LLM_FLAGSHIP_PRICE_OUTPUT_PER_1K` | 0 / 0 | 模型 2 成本估算单价，独立于模型 1 |

同一服务商时，设置 `LLM_FLAGSHIP_MODEL` 即可选择模型 2；地址和 Key 没有单独设置时继承 normal。模型名变量未设置时默认 qwen-max。不同服务商时同时设置第二套地址和 Key；两套模型都需支持当前配置的 JSON 输出格式。

只在规划解析/编译/校验实际进入修复循环时切换，正常澄清、出域、能力说明和 HTTP 超时/鉴权错误不会触发这个切换。Answer 始终使用 normal。每次 LLM_CALL 记录实际模型；总费用按各次调用对应模型单价汇总，缺失 usage 时为未知。两套默认单价为 0，不表示实际免费。

配置和提示词随进程加载，不支持热更新；环境变量更改后重启生效，代码或提示词更改后重新构建并重启。

Trace 单条接口保留完整 `events`，另提供 `planner_attempts`、`final_plan`、`final_compiled_plan`。每次尝试直接记录模型、调用状态、校验状态和失败原因；批量 `/api/traces` 也保留轻量 `planner_attempts`，便于比较模型 1 首次失败与模型 2 修复成功。页面只展示最终可执行 Plan；详情约定见 [上下文管理](docs/上下文管理.md)。

## HTTP 接口

以下请求会真实调用服务，仅作为手工调用示例。

```bash
curl -sS 'http://localhost:8082/api/ask' \
  -H 'Content-Type: application/json' \
  -d '{"question":"昨天币安 BTC 永续成交额是多少？","timezone":"Asia/Shanghai"}'

curl -N 'http://localhost:8082/api/ask/stream' \
  -H 'Content-Type: application/json' \
  -d '{"question":"分别列出币安和 OKX 的 BTC 最新成交价。","timezone":"Asia/Shanghai"}'
```

| 方法与路径 | 输入与输出 |
| --- | --- |
| `POST /api/ask` | 基本输入 `question`、可选 IANA `timezone`；返回 answer、evidence、traceId、conversationId、turnId、耗时与计数等，见 [AskResponse](src/main/java/com/cexpilot/api/AskResponse.java) |
| `POST /api/ask/stream` | 同类请求，SSE 事件为 meta、progress、delta、done、error；done 提供标识与统计，答案由 delta 累积 |
| `GET /api/trace/{traceId}` | 返回主记录与按 seq 排序的完整事件 |
| `GET /api/trace_detail/{traceId}` | 当前与单条 trace 接口读取相同完整数据，保留兼容路径 |
| `GET /api/traces?beginHour=4&endHour=0` | 批量查询最近 4 小时；beginHour > endHour ≥ 0，窗口最长 24 小时，内联事件省略输入输出重字段 |
| `POST /api/feedback` | `{traceId, rating, category?, comment?}`，rating 取 up/down |

SSE 发起后即返回流，执行异常通过 `error` 事件传递，不能仅凭 HTTP 200 判断成功。前端入口为 [index.html](src/main/resources/static/index.html)，回放入口为 [trace.html](src/main/resources/static/trace.html)。

线上入口：[cex.hrscrm.net](https://cex.hrscrm.net/)。定位问题先看 Trace 中的逻辑计划、绑定、取数与计算，再看最终答案，详见 [Trace搭建](docs/trace搭建.md)。

## 代码导航与开发检查

| 目录 | 职责 |
| --- | --- |
| `api` | HTTP、SSE 与一次问答编排 |
| `metric` | 指标目录、类型化查询、编译器、Provider、统一结果与来源传播 |
| `dag` | 规划、校验、依赖引用、拓扑执行、事实回答 |
| `time` | TimeSpec 与确定性时间区间消解 |
| `market` | 查询策略、分页 Source、交易所 Client、统一模型与成品指标计算 |
| `calculation` / `runtime` | 九个确定性算子、工具契约、执行结果与 Trace 抽象 |
| `llm` / `prompt` | 兼容模型客户端、完整调用序列化与提示词加载 |
| `trace` / `feedback` | 回放落库、查询与反馈 |
| `conversation` / `evidence` | 结构化会话状态、澄清接续与历史事实引用，见上下文管理文档 |
| `ethereum` / `eval` | 链上分析与评测代码；分别不在当前指标主链 / 暂不展开文档 |

按变更选择直接相关测试，先编译测试代码，再跑指定测试：

```bash
mvn test-compile
mvn test -Dtest=MetricCatalogTest,MetricPlanCompilerRegressionTest,PlanValidatorTest
```

各专题列出对应测试入口。全量 `mvn test` 仅在明确要求或 CI 场景执行。`scripts/` 下探针和回归会调用线上服务，只在明确收到执行指令时运行；运行前备份上一轮报告及原始 JSON，结束后逐条比较 verdict，并独立复核 FAIL → PASS 的数值。约束见 [AGENTS.md](AGENTS.md)。
