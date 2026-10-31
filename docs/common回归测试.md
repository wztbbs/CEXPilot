# 统一 common 回归

唯一的通用入口为 `scripts/run_regression.py`。旧 `probe_features.py` 已删除，其时间窗口与结构化证据判定合入统一入口。`probe_calculation.py` 保留为专项分析工具；common 已覆盖其基础算子能力，不重复执行那21条实验用例。

## 用例范围

| 分组 | 数量 | 内容 |
| --- | ---: | --- |
| A | 7 | 当前价、别名、持仓量、盘口、标记/指数价、资金费率原始序列、成交流、官方24h |
| B | 6 | 滚动窗口、自然日/周、指定时段、动态绝对日期、已完结K线序列 |
| C | 8 | avg、sum、min/max、compare、difference、ratio、relative_change、annualize |
| D | 1 | 两所合计后计算占比，验证 sum → ratio 的真实引用 |
| E | 10 | 两所现货快照、官方24h、滚动/自然日K线、盘口、币安现货taker、序列聚合、现货与永续差值 |
| F | 8 | 现货不支持的资金费率/OI/标记/指数，OKX现货taker、费率时间窗口、USDC、1m、领域外、链上查询 |
| H | 15 | 首页非监控候选的完整原文：功能介绍固定回复、永续/现货快照、窗口均价、资金费率聚合、跨所比较及现货永续价差 |

共55条独立单轮问答，原A～F共40条保留，新增H组15条首页原文回归；不包含追问或常驻任务。H组与基础组即使能力相同，也保留首页完整措辞，验证用户点击的真实问题。离线测试对照 home-suggestions.yml 的ID和原文，新增或修改首页候选却未同步断言时失败。唯一排除的常驻候选为“监控币安 BTC/USDT 永续最近一小时跌幅超过 2%，发邮件提醒。”，由用户手工测试。E3用成交额/成交量与现价交叉检查字段解析错误。

H1 验证固定能力介绍、对应 preset 标识与 STATIC_REPLY 记录，以及零模型/工具调用；其余 H 用例检查实际指标、市场、币种、时间窗口、1h K线完结、算子及比较方向。新5条现货相关候选为 H11～H15。套件版本更新为 common-v3-55-home，旧版本报告仍保留，不与不同版本自动混比。

每条使用新的 conversationId，parentTurnId为空字符串，timezone=Asia/Shanghai，isRealUser=false；不共享cookie，不自动重发POST，不隐式增加冒烟请求。筛选精确匹配ID，`only:E1` 不会顺带执行 E10，所有分组都受筛选约束。

B5的日期在发问前生成（当日前第3天），问题带明确年月日和上海时区。时间范围检查基于本次请求时间和返回 requested_range；有效范围可排除未完结数据，但不能将缺样本或 statistics_omitted 当完整结果。

## 判定

C3/H7/F6 的预期保持不变：C3 必须对全体10期分别求 max/min；H7 两所分别求均值再比较；F6 对未接入的1m粒度直接说明能力缺口，不改为澄清或替换成其他粒度。规划提示补齐对应示例，修复反馈按上游实际输出契约给出完整序列聚合 input，去掉一律引用顶层 value 的误导。

程序另对明确的数字“最近N期资金费率最高/最低”增加保守覆盖检查：检查取样期数、完整集合与必需的极值算子。完整 field 引用或完整枚举全部N个下标均可；不一律禁止下标。指定期次、子集、多个不同期数、均值等派生量极值及省略范围的追问不套用此规则。这是限定范围的保护，不代表已解决所有自然语言需求覆盖问题；普通 JSON Schema 和类型检查继续执行。

- 同时检查最终计划、trace终态、query路由、市场/交易所/币种/单位/形态、期数、时间范围和必要算子。完整单轮问题进入澄清，视为FAIL。
- 每次取回对应 trace_detail；接口失败/缺失不能静默判PASS。报告保留完整响应与trace，便于离线重判。
- 使用本次原始证据和 input_references，以Decimal独立重算九种算子，不把输出自述的 left/right 当真值；检查比较方向、平均值分支、极值首个命中行、年化周期和多步计算依赖。
- 数据缺失、覆盖不完整、执行错误明确标记 DATA_ERROR，不计PASS。只将K线序列要求 candle_interval，不强迫持仓量序列携带K线粒度。不再按小数位数或“年份不是今年”一刀切判断错误。
- 现货不得静默替换成永续。币安现货盘口必须使用本地采集时间，不能伪造交易所数据时间。
- 边界用例要求明确缺口、最终NO_PLAN/OUT_OF_DOMAIN且不产生取数；泛化兜底或澄清不算拒绝成功。
- 数学重算可验证计算一致性，不能独立证明交易所行情真值；自然语言语义、单位解释和结论仍需人工复核，尤其是 FAIL → PASS 的项目。

## 执行方式

这些命令会访问真实服务，只有明确要求执行时才运行。全量耗时较长，应放后台并保留日志。

```bash
python3 scripts/run_regression.py only:E1,E2
python3 scripts/run_regression.py group:E
python3 scripts/run_regression.py group:H --list  # 只查看首页候选，不发请求
python3 scripts/run_regression.py group:H         # 用户明确执行时，测全部15条首页非监控候选
python3 scripts/run_regression.py --base http://localhost:8082 only:A1
# 默认串行；-w 2 可并发，但可能增加排队和请求耗时。
```

`--list` 只列用例、不访问服务。离线判据测试运行：

```bash
python3 -m unittest discover -s scripts/tests -p 'test_common_regression.py'
```

离线测试封锁网络，不运行线上脚本main，不产生真实问答或回归报告。

## 时间戳与缓存命中率

运行第一条case前记录 startTime，全部case完成后记录 endTime（Unix毫秒）。默认串行，并发时等待在途请求完成后再记结束时间。之后只读调用一次：

```text
GET /api/stats/cache?startTime=...&endTime=...
```

当前 ask_trace.created_at 是秒精度，所以查询起点向下、终点向上扩展到完整秒，最短1秒；报告同时保留实际运行毫秒边界与统计请求 parameters。不会为预热或提升缓存命中率增加LLM调用。

终端、Markdown和JSON均保存缓存结果：比例、cachedTokens/promptTokens、measuredCalls、unknownCalls。null显示“比例未知”；接口未部署、超时或格式错误显示“缓存统计不可用”，不伪造0%，不丢弃功能用例结果。

该接口是**全站时间窗口统计**，可能包括同期其他用户、脚本或超时后仍在执行的请求，不能称为本批次独占命中率。客户端超时的请求可能在服务器继续执行，因此统计值是调用统计接口时的已落库快照。

## 报告与重跑

产物仍放在 `importants/回归测试/`：`common-YYYY-MM-DD.md` 与 `raw/common-YYYY-MM-DD.json`。同日重跑前先为两份已有产物创建带时刻及随机后缀的备份，再开始执行。

如果同日已有同版本报告，逐条对比共同ID的verdict变化。由FAIL转PASS的用例会提示独立复核。旧版28/36条的ID含义不同，不直接按ID对比；历史报告保留不修改。

退出码：全部选中case通过为0；任一FAIL或中断为1。缓存接口不可用不改变功能用例的退出码。Ctrl+C保存已收集结果；并发时取消未开始的case，等待已发送请求收尾，标记 interrupted，不把部分完成冒充全量完成。
