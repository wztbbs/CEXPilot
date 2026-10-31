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

共40条独立单轮问答，不包含追问、常驻任务或重复稳定性请求。E3在原设计的官方24h成交量/成交额之外增加当前价，用成交额/成交量与现价的量级交叉检查捕捉OKX现货字段解析错误。

每条使用新的 conversationId，parentTurnId为空字符串，timezone=Asia/Shanghai，isRealUser=false；不共享cookie，不自动重发POST，不隐式增加冒烟请求。筛选精确匹配ID，`only:E1` 不会顺带执行 E10，所有分组都受筛选约束。

B5的日期在发问前生成（当日前第3天），问题带明确年月日和上海时区。时间范围检查基于本次请求时间和返回 requested_range；有效范围可排除未完结数据，但不能将缺样本或 statistics_omitted 当完整结果。

## 判定

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
