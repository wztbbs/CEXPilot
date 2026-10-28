# 07 · Binance / OKX 业务口径差异

学习目标：先回答两个数是否可比，再计算差多少。字段都叫 price、volume 或 OI，只说明名字相似，不足以说明业务口径相同。

## 1. 概念与业务机制

### 1.1 比较前先对齐六件事

**产品身份、统计范围、单位、时间窗口、采样或结算周期、完整性与新鲜度。** 任意一项不同，都可能让数值比较失去原意。

例如一边是 BTC USDT 永续、一边是全部 BTC 合约，转成 BTC 数量后也仍不能直接回答“这两个永续品种谁的 OI 更高”。同样，USD 与 USDT 并非可不加说明地混用。

### 1.2 关键口径对照

资料核对日期：**2026-10-27**。下表只讨论本项目涉及的 USDT 永续，不外推到所有产品。来源 [B]、[O] 等列在表后；工程限制与交易所事实分别标明。

| 对象 | Binance | OKX | 比较条件 / 项目选择 |
| --- | --- | --- | --- |
| 产品身份 | `BTCUSDT` 配合永续产品信息 [B] | `BTC-USDT-SWAP` [O] | 还须核对报价、结算与产品类型 |
| OI 数量 | 当前 `openInterest`；历史 `sumOpenInterest` [B] | `oi` 为张数，`oiCcy` 为币数，`oiUsd` 为 USD 金额 [O] | 项目对齐基础币数量，不能直接比较张数与币数 |
| OI 金额 | 历史有 `sumOpenInterestValue` [B] | 有 `oiUsd` [O] | 需统一估值币种、价格基准和时刻；项目未将它们绑定为可查询的金额 OI |
| 成交 K 线数量 | 基础币量与报价币成交额分别提供 [B] | 本产品下 `vol` 为张，`volCcy` 为币，`volCcyQuote` 为报价币额 [O] | 项目取币数量与真实 USDT 成交额 |
| 官方 24h | ticker 有滚动统计 [B] | ticker 的合约张数与币量不可混用 [O] | 项目仅 Binance 接入官方 24h 真实成交额；OKX 可查 K 线区间真实成交额 |
| 已结算 Funding | 历史结算记录的 `fundingRate` [B] | 历史记录取 `realizedRate` [O] | 项目统一为 ratio；还须核对结算时间与对应周期 |
| Mark / Index | 官方计算的两类参考价 [B] | 同样存在，但公式与成分可能不同 [M] | 价格类型一致后也不是完全相同的估值来源 |
| 主动买卖 | 有官方聚合统计 [B] | 接口是否存在与项目是否接入不同 | 当前指标目录只绑定 Binance，不能声称两所 Taker 已可对照 |

来源：

- [B] [Binance 官方 Market Data](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data)：Open Interest、Kline、Funding History、24hr Ticker 等章节。
- [O] [OKX 官方 API 文档](https://my.okx.com/docs-v5/en/)：Public Data 与 Market Data 章节中的产品、OI、K 线、Funding、Ticker 字段。该链接是官方区域文档入口；使用具体产品规则仍须核对适用地区，不把区域账户规则泛化。
- [M] [OKX Mark / Last 说明](https://www.okx.com/en-us/help/ii-mark-price-and-last-price)。
- 项目能力以 [指标目录](../src/main/resources/metrics/cex-perpetual.yml)、[BinanceClient](../src/main/java/com/cexpilot/market/BinanceClient.java)、[OkxClient](../src/main/java/com/cexpilot/market/OkxClient.java) 为准。

### 1.3 同样的单期费率，不同的持仓成本

假设 A 所每 8h 收 0.01%，B 所每 4h 收 0.008%，费率与名义仓位在一天内固定，且持续持仓经过所有结算：A 的教学累计费率为 0.03%，B 为 0.048%。B 单期更低，按一天比较却更高。

这是等周期假设下的归一化示例。真实周期和费率会变，真实费用还取决于每次结算时仓位的名义价值。比较历史累计成本应逐次结算计算；不能把最新一期乘固定次数当成已经发生的费用。动态周期规则可参照 [Binance Funding 官方说明](https://www.binance.com/en/support/faq/detail/360033525031)。

### 1.4 数据可得范围与分页限制

保留期决定能查多早；单页上限决定一次返回多少；项目预算决定愿意拉多少页。三者不同，“一页 100 条”不等于“只能查最近 100 条”。

| 数据 | 官方或当前来源情况 | CEXPilot 当前约束 |
| --- | --- | --- |
| Binance 历史 OI | 官方历史接口仅近期范围，见 [Open Interest Statistics](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Open-Interest-Statistics) | 代码限制最近 30 天；500 条/页、最多 8 页 |
| OKX 历史 OI | 按具体合约与 period 查询；不能由接口存在推断永久保留 | 100 条/页、最多 40 页；未设置 retentionDays 不等于无限历史 |
| 成交 / Mark K 线 | 官方可用粒度与近期、历史接口限制各异 | 项目只接入 5m/15m/1h；具体分页和预算见 [时间窗口](../docs/时间窗口.md) |
| Funding 样本 | 两所历史接口与结算规则各有范围限制 | 当前只开放最近 N 期，N=1～100；不是指定日期窗口 |

代码常量是首版项目快照，后续修改应以 Source 的 capability 为准；本次没有向交易所实测保留边界。返回条数相同也不代表完整，可能有重复、缺点或错位。

## 2. 典型业务场景

“昨天币安 BTC 成交额比 OKX 高多少？”应查相同时区、相同自然日、相同永续产品的真实报价币成交额，再比较。不能一边用官方 24h、一边用昨天；更不能对 OKX 用币量乘现价冒充真实额。

“两所价差能套利吗？”最新成交价差只能说明某些成交价格不同。真实套利至少需要可执行的买卖方向报价、数量深度、时间同步、手续费、滑点、资金或转账约束和持仓成本。本项目不执行交易，不能据两次 Last 快照承诺无风险收益。

## 3. 易错点与理解边界

官方字段定义、项目支持范围和历史实测记录要分开。底层类存在不等于 Planner 可调用；某次历史查到数据也不保证长期保留。

分页返回空数组也不是“市场没有交易”的证明。OKX 的 candles 与 Funding 历史接口中，`before` 表示取更新的数据，为下界；`after` 表示取更早的数据，为上界。OI 某些统计接口使用 `begin/end`，不能套用参数名。网格也需核对，例如项目 OI 适配对 6h/12h/1d 使用 UTC 变体，不能将 UTC+8 网格直接冒充 UTC 网格。

这些细节只在影响业务解释时需要掌握，具体适配见 [时间窗口](../docs/时间窗口.md)。如以后手工访问 API，必须遵守仓库 [AGENTS.md](../AGENTS.md) 的代理设置，OKX 带 User-Agent；本目录不提供直连官方 API 的示例。

## 4. 对应 CEXPilot Case 与学习练习

已有 `B6 进阶-跨所`、`G15/G16 OKX-OI`、`G18/G19 OKX-成交额` 与 `G20 币安-OI对照`，见 [回归题目](../scripts/run_regression.py)。它们可用来检查口径，不代表本轮已运行或已验证。

### X1 · 100,000 和 2,000 谁大？【离线推演】

同产品同刻，A 所 OI=2,000 BTC，B 所 OI=100,000 张；假设 B 每张 0.01 BTC、乘数 1。谁的基础币 OI 更大？

<details>
<summary>预测后展开参考思路</summary>

B 为 1,000 BTC，所以 A 更大，是 B 的两倍。原始数字直接比较会得出相反结论；真实合约面值必须查元数据，不能照搬教学假设。

</details>

### X2 · 单期更低就更便宜？【离线推演】

A 每 8h 为 0.01%，B 每 4h 为 0.008%。假设费率与 100,000 USDT 名义仓位全天固定、均为多头且经过全部结算。一天各支付多少？

<details>
<summary>预测后展开参考思路</summary>

A 支付 30 USDT，B 支付 48 USDT。比较需对齐时间长度与支付方向，不能只看一期数值；实际历史费用不能靠该假设代算。

</details>

### X3 · 跨所真实成交额怎么比？【手工查询】

问题：“按 Asia/Shanghai 的昨天、5m K 线，查询币安与 OKX 的 BTC USDT 永续真实成交额，并计算币安比 OKX 高百分之多少。”

<details>
<summary>预测后展开参考思路</summary>

核对同窗、真实报价币额、完整性，再以 OKX 为基准计算 `(币安/OKX−1)×100%`。如果 OKX 额更高，结果应为负；不能取绝对值改成‘高’。

</details>

### X4 · 当前 OI 可比到什么程度？【手工查询】

问题：“分别查询币安和 OKX 的 BTC USDT 永续当前 OI 基础币数量，注明单位和数据时间。”即使都显示 BTC，还要检查什么？

<details>
<summary>预测后展开参考思路</summary>

检查确为同一产品范围、数据新鲜且时刻接近。两次请求不保证同步；不能将金额或张数混入，也不能把两所相加称作全市场总 OI。

</details>

### X5 · 查更久就是把 limit 调大？【能力边界】

问题：“给我币安 BTC 永续 90 天前的 5m OI。”如果接口只返回近期记录，是否可以替代原问题？OKX 没写 retentionDays 是否就一定能查到？

<details>
<summary>预测后展开参考思路</summary>

不可以。当前 Binance 历史 OI 受 30 天限制，需要另有历史归档来源才能回答。OKX 未声明保留限制只是项目未配置，不是数据可用保证；都不能悄悄缩短区间。

</details>

### X6 · Last 价差是可交易利润吗？【离线推演】

A 所 Last=60,100，B 所 Last=60,000；有人说买 B 卖 A 可赚 100。实际 A 买一=59,990，B 卖一=60,010，忽略费用且只看小额，立即成交的毛价差是多少？

<details>
<summary>预测后展开参考思路</summary>

卖 A 应看 A 买一，买 B 应看 B 卖一，毛价差 `59,990−60,010=−20 USDT/BTC`。Last 的差额并非可成交收益，费用与深度还会进一步影响执行。

</details>
