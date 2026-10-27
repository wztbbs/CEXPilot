# 端到端会话评测

入口 [e2e_eval.py](../e2e_eval.py)，Python 3.9+ 标准库。复用 Planner 的安全 HTTP、严格 JSON 和基础判据校验，以及 Answer 的 Chat Completions 客户端与裁判输出 Schema。没有改 Java、生产 prompt 或旧脚本，没有新增数据库表。

评测的是：**真实用户输入经过规划、执行、回答后，每轮是否得到正确处理，整段会话是否完成约定任务。** 不把 Planner/Answer 的 PASS 简单相乘，也不以 HTTP 200、最后一轮成功或数字有出处代替任务验收。

## 25 条主集

用例文件 [e2e_cases.json](e2e_cases.json)，裁判规则 [e2e_judge.txt](e2e_judge.txt)。共25个会话场景、45轮固定用户输入，尚未执行线上评测。

| 分类 | 用例 | 数量 | 覆盖 |
| --- | --- | ---: | --- |
| 正常可答 answer | E001～E007 | 7 | 口语当前价、OKX标记/指数价、官方24h、纽约自然日、未收盘小时序列、费率avg、跨所relative_change |
| 澄清 clarification | E008～E010、E025 | 4 | 缺币种、缺期数、多候选、连续短回复补齐两个条件 |
| 域内不支持 unsupported | E011～E013 | 3 | 现货、资金费率时间窗口、不同基础币数量求和 |
| 部分可答 partial | E014～E015 | 2 | 已结算与预测费率混问、行情与写作混问 |
| 出域 out_of_domain | E016～E017 | 2 | 天气、写作 |
| 能力咨询 help | E018 | 1 | 能力介绍，不机械要求补币种 |
| 多轮 multiturn | E019～E024 | 6 | 连续改所改币、pinned、latest、旧新比较、五轮干扰回访、出域后返回 |

分类是报告标签，不是按真实流量加权的用户分布。域内不支持仍是 `in_domain=true`；mixed/help单独标注，不硬编码全局in_domain值。混合请求采用本次确认的产品验收口径：完成支持部分，并简短说明其余部分不处理。该口径写在用例中，可独立审核，不代表当前实现已经通过。

四条故障注入（单侧失败、区间缺口、计算失败、恢复后重试）暂不在这25条内。本脚本不注入执行层故障，也不靠实时环境偶发故障宣称覆盖。它不是全算子、全参数或全并发覆盖套件。

## 用例和判分

每个场景包含 `id/title/category/timezone/label_source/turns`。每轮包含：

- `question`：实际发送的用户原文；前置轮与末轮地位相同，全部评分。
- `domain`：in_domain/out_of_domain/mixed/help，表示请求范围。
- `outcome`：answer/clarify/unsupported/partial/out_of_domain/help，表示预期处理方式。
- `expected_fields`：可选的确定性in_domain标签；它不代替语义判断。
- `execution`：fetch/calculation/reuse分别要求required、forbidden或optional；依据真实计数和执行事件检查，不限定唯一节点拓扑或HTTP请求次数。
- `criteria`：每项有唯一id、dimension、自然语言expectation；每轮至少有task与grounding要求。

正常可答任务必须取得所需有效结果，诚实说明取数失败也不代表完成该任务。澄清、拒答、能力介绍是另外的预期行为：正确处理可以通过，且即使没有Answer LLM_CALL也要评分。

采集每个场景都从新会话开始，之后沿用返回的conversationId。脚本按固定用户步骤继续，不让另一个LLM自由扮演用户。语义错误保留并继续采集后续步骤，以观察完整行为；网络、busy、ID不一致等采集失败则停止该会话，剩余轮记NOT_COLLECTED，避免拼接到别的会话。

裁判每次仅收到当前轮和已发生的历史前缀，不看到未来问题及其要求。当前轮保留真实完整Planner和Answer输入，历史轮保留问题、回复、事实、有效计划和执行记录，省去重复大prompt；原始完整trace始终归档。已有错误不会直接替本轮定罪，但如果原样本/任务前提没建立，不能假装复用或回访成功。

裁判逐项返回criteria，同时摘录当前最终API回复的claims；引用必须是已提供输入中真实存在的JSON Pointer，例如 `/turns/0/answer_input/facts/0/data/value`。摘录必须来自当前回复，不能引用回复自身作证或引用未来轮。执行日志可以核对链路，但Answer没看到的工具数据不能为行情断言补证。路径存在不证明内容支持结论，程序不能保证裁判穷尽所有断言。

每轮合并机检、criteria和claims：明确违反为FAIL，不确定为UNCERTAIN；裁判协议错误为JUDGE_ERROR。整条会话任一轮FAIL则整体FAIL，最后一轮通过不能覆盖前面错误。已确认FAIL优先展示，后续异常仍在逐轮结果中保留；无FAIL时按COLLECTION_ERROR、SYSTEM_ERROR、NOT_EVALUABLE、JUDGE_ERROR、UNCERTAIN展示，未采集完则INCOMPLETE。只有所有轮PASS，场景才PASS。

报告按类别分别统计，避免出域/拒答用例抬高可答任务完成率。逐轮保留目标耗时、执行计数、trace、判据和断言依据；目前不设置自动耗时门槛，不把超时等同于语义答错。

## 裁判和目标配置

**默认复用现有 `PLANNER_EVAL_JUDGE_*` 整组配置**，不需要重新设置密钥。没有默认厂商或模型。如需E2E独立裁判，完整设置：

| 环境变量 | 用途 |
| --- | --- |
| `E2E_EVAL_JUDGE_BASE_URL` | Chat Completions兼容地址前缀，或完整/chat/completions地址 |
| `E2E_EVAL_JUDGE_API_KEY` | 裁判API key |
| `E2E_EVAL_JUDGE_MODEL` | 裁判模型名 |

三项只配置一部分会报错，不混用不同配置的凭据。独立整组启用后，可设置 `E2E_EVAL_JUDGE_EXTRA_BODY`、`_TIMEOUT`（默认240秒）、`_RETRIES`（传输重试，默认0）、`_RESPONSE_FORMAT`（默认json_schema，也支持json_object/off）。EXTRA_BODY不能覆盖model/messages/工具/response_format。未启用独立整组时，使用Planner整组及其可选参数。

目标地址使用 `--target-url`，或 `E2E_EVAL_TARGET_URL`，再回退 `PLANNER_EVAL_TARGET_URL`；不默认指向线上。目标 `--timeout` 默认每个HTTP请求240秒，不是整条多轮场景的总时限。目标问答不自动重试，避免重复发送用户输入。

`--judge-output-retries`默认1：只对已返回文本的JSON解析/结构校验失败反馈错误后重试。有效FAIL/UNCERTAIN不会重试；HTTP错误、截断/拒绝等客户端失败不会消费这类格式重试。连接/超时重试由客户端的 `_RETRIES` 独立控制。每次已返回的判定和协议错误都会保存，不将反复采样直到PASS当成修复。

## 命令

以下从仓库根目录执行。**只有收到明确执行指令才运行网络命令。** 本次实现仅执行离线检查。

```bash
# 离线列出、检查25条场景和45轮输入
python3 scripts/e2e_eval.py validate
python3 scripts/e2e_eval.py list --only E008,E020,E025

# 采集，不调用裁判；每轮真实执行完整问答
python3 scripts/e2e_eval.py collect --target-url https://cex.hrscrm.net --only E025

# 采集并逐轮判分；包含澄清和拒答
python3 scripts/e2e_eval.py run --target-url https://cex.hrscrm.net --only E008,E025

# 只调用裁判，复评原始会话，不重新提问或取数
python3 scripts/e2e_eval.py judge RUN_DIR/raw.jsonl --only E025

# 显式改用新标注；问题、顺序和时区必须保持一致
python3 scripts/e2e_eval.py judge RUN_DIR/raw.jsonl \
  --cases scripts/evals/e2e_cases.json --only E025

# 纯离线报告与对照
python3 scripts/e2e_eval.py report NEW_RUN/scores.jsonl --compare OLD_RUN/scores.jsonl

# 导入已有完整会话；必须逐轮匹配原文及同一conversationId
python3 scripts/e2e_eval.py import-session SESSION.json --only E025
```

SESSION.json结构为 `{"turns":[{"index":1,"question":"原文","response":完整/api/ask返回,"trace":完整/api/trace_detail返回}, ...]}`，使用真实JSON对象替换说明文字。可导入从首轮开始的连续前缀，未包含的轮不会计PASS。不能直接把Planner/Answer的scores当作E2E原始输入，也不能只导入末轮或用近似问题套标签。

经明确要求执行全量时，放后台：

```bash
nohup python3 -u scripts/e2e_eval.py run --target-url https://cex.hrscrm.net \
  > /tmp/cexpilot-e2e-eval.log 2>&1 &
tail -f /tmp/cexpilot-e2e-eval.log
```

## 归档与复核

产物根目录 `importants/回归测试/e2e-eval/<上海时间戳>-<随机后缀>/`。新建前将上一轮目录整体备份到 `_backups`，不覆盖历史。

| 文件 | 内容 |
| --- | --- |
| manifest.json | 模式、来源、覆盖、用例/裁判prompt/三个评测脚本指纹、公开裁判配置，不含API key |
| cases.json / judge_prompt.txt | 本轮冻结标注与裁判规则 |
| turns.jsonl | 实时采集的每轮检查点，包含完整API回复和trace |
| raw.jsonl | 按场景归档完整会话；可直接judge复评 |
| turn_scores.jsonl | 每轮判分检查点；判分中断也保留已付费结果 |
| scores.jsonl | 场景级结果和全部逐轮判定，包括裁判调用、重试内容和原因 |
| report.md / report.csv | 分类、逐场景、逐轮结果和差异 |

采集中断会保存当前部分raw并退出130；判分中断保留已完成场景和逐轮检查点。不自动重放用户请求或自动续跑。报告run/judge默认与最近scores比较，也可用 `--compare` 指定。相同评测配置与相同实际输入/回复分别标识，FAIL→PASS另列独立复核清单。变更裁判或标注不自动等于被测系统改善。

退出码：评分0表示所选场景每轮均PASS；1表示失败/不确定/异常/不完整；2表示配置或输入错误；130表示中断。collect的0只表示完整采集，import/report的0只表示离线操作成功，不是评测PASS。

## 离线验证

```bash
python3 -m unittest discover -s scripts/tests -p 'test_e2e_eval.py'
```

测试阻断真实HTTP，使用假trace、假裁判和临时目录验证逐轮评分、会话身份、检查点、异常、引用、复评、备份和报告。它验证脚本实现，不证明实际被测模型或裁判效果。
