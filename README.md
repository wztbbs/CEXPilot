# CEXPilot — Crypto / CEX AI Copilot（Phase 1）

当前默认问答流程为 **K 线指标试点**：仅支持 Binance / OKX 的 USDT 报价、USDT 结算永续成交 K 线指标。原 Funding、OI、盘口、ticker 快照和 Ethereum 工具保留代码，暂不进入新 Planner，也不自动回退旧流程。

- 区间指标：开、高、低、收、涨跌幅、基础币成交量、USDT 成交额。
- 序列指标：每根 K 线的开、高、低、收、基础币成交量；粒度支持 5m / 15m / 1h。
- 计算：复用 avg / sum / min / max / compare / difference / ratio / relative_change / annualize。
- 支持多轮追问、流式回答和 Trace；数据覆盖不完整时不提供区间指标，依赖计算不能补算。

## 指标主流程

```text
用户问题 → Planner 输出 {metrics, calculations}
         → 展开 exchanges、推导依赖、绑定 KlineMetricProvider、校验物理 DAG
         → KlineQueryService → KlineQueryResult / Candle / SeriesCoverage
         → Provider 直接生成 MetricResult → 标准指标 JSON → 多层计算 → Answer
```

指标定义、核心概念及绑定在 `src/main/resources/metrics/kline.yml`。Planner 不接收底层取数 Tool 目录；同一指标组共享品种、时间和粒度，仅 exchanges 为列表。结果引用如 `{{m1.binance.value}}`、`{{m1.okx.samples}}`，计算结果引用 `{{c1.value}}`；不输出 depends_on。

`range_statistic` 返回 value 与实际 observation_seconds，`time_series` 返回 samples[{time,value}]。两者都保留单位、覆盖情况和来源；同组分支独立失败，失败会阻止依赖计算。第一版没有跨指标请求合并，预算按展开后的节点计数，各查询服务仍有分页预算。

Trace 中 `PLAN` 保留逻辑计划，`PLAN_COMPILED` 记录物理绑定，`METRIC_RESULT` 记录 Provider 的参数、标准指标结果与耗时，计算算子继续记录 `TOOL_CALL`。原有 trace 仍可回放；新增计划不接受旧 nodes/tool 协议。

K 线绑定只配置 `provider: kline` 和类型化 `selector`，不再配置旧 Tool 字段路径或列名。`KlineMetricProvider` 直接复用查询结果和 `MarketCalculator.rangeStats()`；`MetricResultJson` 是唯一的指标输出序列化边界。旧 `GetKlinesTool` / `GetMarketStatisticsTool` 保留供旧调用使用，新主流程不执行它们，也不把旧 JSON 再读回模型。

可试问：“币安 BTC 昨天涨跌多少？”、“昨天币安 BTC 永续成交额比 OKX 高百分之多少？”、“分别求两所 BTC 昨天 1 小时 K 线收盘价的等权平均，再比较差值。”

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
| `runtime` / `dag` | 工具注册、指标规划、参数校验、DAG 执行与事实回答 |
| `metric` | 指标目录、逻辑计划编译、结果适配与计算来源传播 |
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
| tool `output_schema` | `ToolResult.data` 的输出字段契约；规划期引用路径校验 | 以紧凑层级结构传入，含类型、含义及固定列位置 |
| tool `capabilities` / `limitations` / `scope` | 维护文档；需要模型知道的限制须写入 description | 否 |
| intent `name` / `description` | 意图归类，仅用于统计 | 是 |
| intent 其余字段 | 文档参考，不限制工具调用或控制回答策略 | 否 |

`input_schema` 当前支持扁平对象：`type`、`properties`、`required`、`additionalProperties`，以及参数的 `type`（string/integer/number/boolean）、`description`、`enum`、`default`、`minimum`、`maximum`、`pattern`。不支持的 schema 字段或无效默认值会在启动时失败，避免配置被静默忽略。规划时校验具体参数；执行时在上游引用解析后再次校验并补齐默认值。默认值只补缺省字段，不替换显式 null。

配置随应用在启动时加载，修改后需要重新构建并重启，不支持热更新。参数键名须与执行器读取的键一致；修改参数契约时仍需同步执行逻辑。提示词版本包含实际渲染的工具、意图、规划约束和回答模板，便于追踪配置变更。

`output_schema` 使用 JSON Schema 结构子集：`type`、`description`、`properties`、`additionalProperties`、`items`、`prefixItems`。对象显式列出字段并设置 `additionalProperties: false`；数组用 `items` 描述元素，二维表的固定列用 `prefixItems` + `items: false` 描述。契约根对应 `data`，例如 `get_ticker` 的价格引用是 `{{n1.data.last_price}}`；资金费率表第二列引用为 `{{n1.data.rates.0.1}}`。新增或修改工具返回字段时须同步更新契约。

Planner 与 PlanValidator 共用这份契约：错误字段、错误层级、数组列越界在工具执行前报出并进入现有规划修复重试。该校验不判断业务口径或计划是否足以回答问题，也不要求声明最终回答结果。契约列出条件字段的并集，不保证每次都存在或非 null；数组实际行数、覆盖完整性和数据可用性仍在运行时检查。无 `type` 的节点表示形状随输入变化（如 `min/max.item`），其内部路径留给运行时解析，避免误拦合法引用。

计算工具与查询工具共用 YAML 注册、DAG 节点和 Trace 链路，目前启用 `avg`、`relative_change`、`annualize`：

| 工具 | `args.input` | 输出 |
| --- | --- | --- |
| `avg` | `{kind: "values", values: [a, b, ...]}`，或 `{kind: "field", collection: 对象数组, field: 字段名}`；二维表还需 `columns` 列名数组 | `value`：等权算术平均，`count`：参与元素数，单位沿用输入 |
| `relative_change` | `{current: 待比较值, baseline: 正数基准}` | `value`：`(current-baseline)/baseline`，`percent`：已乘 100 的百分数值 |
| `annualize` | `{basis, method, rate, rate_unit, period: {value, unit}, year_days?}`，结构见下文 | `value`：年化比例，`percent`：年化百分数，附输入口径、周期、年基准、公式与假设 |

行情输入用 `{{node.data.字段}}` 引用，并声明 `depends_on`；只允许用户明确提供的常量，不支持自由表达式。比如平均最近 N 期费率，先查 `get_funding_rate_history(count=N)`，再把 `rates` 和 `rates_columns` 引用到 `avg`，选择 `field=rate`。已有统计工具能直接输出的指标仍优先直接查询。

算子接受数值及无单位十进制字符串，使用 BigDecimal，结果按 34 位有效数字 HALF_EVEN 舍入；结果数值以十进制字符串输出，可继续引用。空集合、缺失字段、null、非数值均失败；`relative_change` 拒绝零和负基准。规划期检查嵌套参数并接入现有 repair，执行期在引用解析后重新校验实际值。

DAG 在提取字段前拦截标明区间/样本不完整或省略统计的引用源，失败会沿依赖传播。单位、比较方向和业务口径的语义匹配仍需规划器判断，两个裸数通过校验不代表比较有效；回答阶段不得补算失败结果。计算参数与结果进入 `TOOL_CALL` trace，原始引用保留在 `PLAN` 中。本地测试使用模拟模型与数据，不代表已评估真实模型的规划准确率。

`annualize` 的设计口径：

| 参数 | 含义与约束 |
| --- | --- |
| `basis` | 必填：`periodic_rate` 单期费率/收益率外推；`cumulative_rate` 历史窗口费率直接求和；`holding_return` 持有期总收益率 |
| `method` | 必填：`simple` 简单年化，或 `compound` 复利/几何年化；`cumulative_rate` 只允许 `simple` |
| `rate` / `rate_unit` | 原始值与单位均必填；`ratio` 的 0.0001 和 `percent` 的 0.01 均表示 0.01%，代码统一换算 |
| `period` | rate 对应的完整时长，`{value, unit}`；单位为 second/minute/hour/day，1 秒至 365250 天，不接受含义不固定的月份或年份 |
| `year_days` | 可省略，代码默认 365，亦可显式选 360 或 366；固定日数基准，结果披露来源，不自动推断闰年 |

令 `r` 为换算后的小数比例，`n = year_days * 86400 / period_seconds`，简单年化为 `r*n`，复利年化为 `(1+r)^n-1`。复利使用 [big-math 2.3.2](https://github.com/eobermuhlner/big-math) 的十进制 log/exp，支持非整数 n，并为极小费率保留额外精度；年化对数增长绝对值超过 200 时失败。复利及持有期收益率输入不可小于 -100%，等于 -100% 的复利结果仍为 -100%。

例如用户明确给出“每 8 小时 0.01%，简单年化”，节点参数为：

```json
{"input":{"basis":"periodic_rate","method":"simple","rate":"0.01","rate_unit":"percent","period":{"value":8,"unit":"hour"}}}
```

结果 `value="0.1095"`、`percent="10.95"`，默认 365 天。方法、对象或周期不明确时提示词要求先澄清；代码只能拦截缺失/非法参数，无法证明模型填写的口径来自用户原意。资金费率保持原始付费方向，不自动当作多头收益；复利外推需要复投假设，结果不是实际或未来收益承诺。

历史资金费率简单年化引用 `get_funding_rate_statistics.statistics.sum` 与 `statistics.observation_seconds`，后者由代码按完整查询窗口计算，不能用首末结算点之差。历史价格收益年化引用 `get_market_statistics.statistics.change_pct`（`rate_unit=percent`）及其 `statistics.observation_seconds`，后者按实际参与的 K 线窗口计算。当前费率快照未提供可靠对应周期，不能猜 8 小时；明确查询最近一期已结算费率时，可引用 history(count=1) 的费率及结算周期。

第一次调用使用 `prompts/dag_planner.txt`，只规划必要查询。部分可查询时保留可用计划并说明其余缺口；序列和明细由对应工具返回，无需明细保留开关；只需统计指标时优先选择统计工具。近期成交返回 `limit` 范围内实际取得的全部记录（最多 100 笔）。

第二次调用统一使用 `prompts/agent_system.txt`，仅根据 query 和本轮 FACTS 回答。历史只用于消解指代。无计划时直接返回缺口说明或追问，不调用回答模型；查询失败和单侧数据缺失同样需要明确说明。工具返回的完整 evidence 直接作为 FACTS，不再按数组长度裁剪明细。各工具自身的查询预算和返回上限仍然适用。

Answer 的 `TIME_CONTEXT` 与工具共用请求开始时固定的时间基准，包含 UTC 时刻、请求时区及当地日期时间；未提供用户时区时采用 UTC+8，并标注为默认值。单个查询显式指定的时区仍以工具返回区间为准。回答阶段不重新计算“昨天”，也不凭模型记忆判断当前日期；时间合法性、覆盖及未收盘状态依据工具结果说明。Answer trace 同时记录这份时间上下文，便于复查跨时区、跨午夜问题。

`get_klines`、`get_market_statistics`、`get_mark_price_history`、`get_mark_price_statistics` 共用缺省粒度策略：用户指定 `interval` 时遵守，未指定时省略参数（不填写 `auto`），由 QueryService 在时间解析后选择。按数据源支持的粒度从细到粗检查对齐后的根数，优先选择不超过 1500 根且不超过总预算的粒度；都超过目标时使用最粗可用粒度，仍需通过总预算和保留期校验。结果的 `candle_interval` 是实际粒度，`interval_source` 标注 automatic/explicit；实际截止时间以 `coverage.covered_until` 和统计的 `actual_range` 为准，不能将最近已收盘数据说成实时结果。同范围、同边界的完整 OHLC 聚合不会因较细粒度而更精确，但粒度影响走势细节和边界贴合程度。OI 等瞬时采样工具不套用此规则。

主动买卖成交量（taker）查询中，Source 在计算范围两端各多取一个 5m 周期，并在分页交界保留重叠、按周期起点去重；外扩受请求时间和历史保留期限制。QueryService 使用公共 `SeriesRangeFilter` 裁回 `effectiveRange`，再调用覆盖校验器，区间内的错位、缺口及分页中止仍会抑制统计输出。扩大取数不会扩大统计范围，也不会把余量计入预期条数；分页页数预算不变，重叠会占用部分容量。其他数据源尚未迁移到这套外扩取数流程。
