# Answer 文件评测

入口 `scripts/answer_eval.py`，Python 3.9+ 标准库；复用 `planner_eval.py` 的HTTP、严格JSON、文件备份和基础裁判结果校验。没有新增数据库表，不改系统Java、Answer prompt或旧脚本。

评测核心是 **答案中的每个重要断言是否由本次真正发给Answer模型的事实支持**。不验证交易所真值，也不把用户假设、旧回答、QUERY_STATUS或其他trace事件当作行情证据。数字相同不代表同一对象/时间/单位，不能用全局数字匹配代替语义核对。

## 两套用例

`answer_cases.json` 共46条，标签依据仓库Answer契约，尚未执行真实模型评测：

| 用例 | 输入来源 | 覆盖 |
| --- | --- | --- |
| A001～A024 | live：CEXPilot真实问答的完整Answer trace，可从Planner raw离线导入 | 身份/单位、时间/覆盖、九类计算表达、部分可答、历史事实复用、缺口成交量 |
| AF001～AF022 | fixture：固定合成FACTS，直接调用单独配置的Answer模型 | 空证据、计算缺失/失败、失败节点残留数字、缺口省略值、费率符号与时区、复用时间、文本注入、历史数字污染、解释与因果边界、跨币单位不兼容 |

固定证据不调用Planner/执行层/交易所。使用仓库当前 `agent_system.txt` 的Answer模板，渲染固定time_context与历史并注入FACTS/QUERY_STATUS；完整实际messages会保存。它能隔离Answer能力，但不等于线上相同部署/模型/参数的结果；要对齐线上模型和参数应显式配置。fixture里的527根、120 USDT等仅是合成测试输入，不是写死线上期望行情。

实时用例不写死答案，只把输出与该次模型可见FACTS比较。`trade.volume/range_statistic`等成品指标可以直接回答，不强制增加sum；缺口是否存在以及计算是否产出，按当前输入分支判定。Planner直接回复而没有Answer调用时记NOT_APPLICABLE，不能冒充Answer PASS。

## 裁判与被测模型配置

**裁判默认复用已有 `PLANNER_EVAL_JUDGE_*` 配置。** 如需独立配置，设置以下三项（不能只设置一项后混用别家API key）：

| 环境变量 | 内容 |
| --- | --- |
| `ANSWER_EVAL_JUDGE_BASE_URL` | OpenAI兼容Chat Completions前缀，或完整 `/chat/completions` 地址 |
| `ANSWER_EVAL_JUDGE_API_KEY` | 裁判API key |
| `ANSWER_EVAL_JUDGE_MODEL` | 裁判模型名 |

可选 `ANSWER_EVAL_JUDGE_EXTRA_BODY`、`_TIMEOUT`（240秒）、`_RETRIES`（0）、`_RESPONSE_FORMAT`（json_schema/json_object/off，默认json_schema）。完全没有配置上述三项时，这些参数也从 `PLANNER_EVAL_JUDGE_*` 读取。EXTRA_BODY不可覆盖model/messages/stream/tools/n/response_format；response_format使用专用变量。严格Schema包含本case合法criterion ID、完整criteria和claims类型；程序继续检查遗漏、重复、答案原文与证据路径。JSON Mode仅约束语法，不能保证evidence数组等字段类型。

**只有固定证据生成需要被测Answer模型配置**，与裁判独立，没有厂商/模型默认值，也不自动把裁判当被测模型：

| 环境变量 | 内容 |
| --- | --- |
| `ANSWER_EVAL_MODEL_BASE_URL` | 被测Answer模型的Chat Completions地址前缀 |
| `ANSWER_EVAL_MODEL_API_KEY` | 被测模型API key |
| `ANSWER_EVAL_MODEL` | 被测模型名，按所用服务配置 |
| `ANSWER_EVAL_MODEL_EXTRA_BODY` | 可选，显式对齐temperature/thinking等线上参数 |
| `ANSWER_EVAL_MODEL_TIMEOUT` / `_RETRIES` | 默认240秒 / 0；传输失败的有限重试 |

被测Answer输出为普通文本，MODEL_RESPONSE_FORMAT为off。所有密钥仅进入Authorization，配置repr、manifest和报告不包含密钥；错误不打印可能回显凭据的HTTP响应体。返回内容若回显当前调用密钥会被脱敏。

## 命令与执行范围

以下在仓库根目录执行。**list/validate/import-trace/import-planner/report离线；其他命令会请求服务并可能产生API费用，只在明确要求执行时运行。** 本次实现只跑了离线测试。

```bash
# 离线验证，包含校准样本格式；可用--only选一组
python3 scripts/answer_eval.py validate
python3 scripts/answer_eval.py list --only A001,A012,AF001,AF016

# 优先复用已有Planner采集：只导入query/时区/前置轮完全匹配的live case
# 默认只导入匹配项；显式--only时任何缺失都报错，不静默少跑
python3 scripts/answer_eval.py import-planner PLANNER_RUN/raw.jsonl --only A001,A012,A023

# 调用裁判复评；不请求CEXPilot，也不重新生成Answer
python3 scripts/answer_eval.py judge ANSWER_IMPORT_RUN/raw.jsonl

# 标签改动后显式复评；query/时区/setup_questions/fixture变化不能套到旧答案
python3 scripts/answer_eval.py judge ANSWER_RUN/raw.jsonl \
  --cases scripts/evals/answer_cases.json --only A001

# 单条完整/api/trace_detail回放文件的离线导入
python3 scripts/answer_eval.py import-trace TRACE.json --only A001

# 默认仅24条live：真实问答+裁判，不需要ANSWER_EVAL_MODEL配置
python3 scripts/answer_eval.py collect --target-url https://cex.hrscrm.net --only A001
python3 scripts/answer_eval.py run --target-url https://cex.hrscrm.net --only A001,A012

# 默认仅22条fixture：直接生成Answer；generate不调用裁判
python3 scripts/answer_eval.py generate --only AF001,AF016
python3 scripts/answer_eval.py run-fixtures --only AF001,AF016

# 只调用裁判评16条预写的正确/错误答案，不请求目标和被测模型
python3 scripts/answer_eval.py calibrate

# 报告重建与逐条对照，纯离线
python3 scripts/answer_eval.py report NEW_RUN/scores.jsonl --compare OLD_RUN/scores.jsonl
```

目标地址可从 `ANSWER_EVAL_TARGET_URL` 或已有 `PLANNER_EVAL_TARGET_URL` 读取；`--target-url`优先。采集 `--timeout` 默认每个HTTP请求240秒，前置轮也真实执行，总用例时长不受单次超时限制。全量网络运行应放后台：

```bash
nohup python3 -u scripts/answer_eval.py run --target-url https://cex.hrscrm.net \
  > /tmp/cexpilot-answer-eval.log 2>&1 &

# 固定证据套件需要事先配置被测模型三项环境变量
nohup python3 -u scripts/answer_eval.py run-fixtures \
  > /tmp/cexpilot-answer-fixtures.log 2>&1 &
```

## 评分与裁判校准

从 `LLM_CALL/answer` 的实际messages和content提取数据，支持JSON字符串或对象形式。读取最后一次Answer调用，最后一次失败不由前面的成功遮盖。历史缺完整messages记NOT_EVALUABLE，不用API answer/evidence猜测输入。cached提取结果不作为评分来源。

裁判逐项给criteria，同时列出答案原文quote、SUPPORTED/UNSUPPORTED/UNCERTAIN、reason与source_refs。source_refs是实际评测输入的JSON Pointer，如 `/facts/0/data/value`、`/query_status/missing`、`/time_context/timezone`；路径必须存在。引用整个系统prompt/历史也不能证明行情数值正确，内容关系仍由裁判核对。程序验证路径存在及quote来自真实答案，不能代替语义蕴含判断。

任一criterion FAIL或claim UNSUPPORTED即整体FAIL；没有失败但有UNCERTAIN则整体UNCERTAIN；所有判据与断言有依据才PASS。裁判遗漏、重复ID、非法格式、空claims、编造原文或证据路径记JUDGE_ERROR；模型调用失败/空输出记ANSWER_ERROR；采集失败记COLLECTION_ERROR。异常和NOT_APPLICABLE不计PASS。

`answer_calibration.json` 包含8组同证据的正确/错误答案：空FACTS、失败节点、历史数字、涨跌方向、计算失败、缺口字段、注入、旧观测时间。正确/错误标签与理由可人工审查；**标签不传给裁判**。calibrate的退出码按是否命中标签，而不是按答案是否PASS：正确识别预写坏答案为FAIL属于校准成功。输出仍保留实际裁判verdict，不改成假PASS。换裁判/参数后可复跑校准，检查错误答案是否被虚绿；小样本命中不等于真实准确率，也不证明claims穷尽了所有断言。

## 文件产物

产物位于 `importants/回归测试/answer-eval/<上海时间戳>-<随机后缀>/`，不覆盖旧文件；新建前备份上一轮整个目录到 `_backups`。

- `manifest.json`：评测/共享脚本、用例、裁判规则和配置指纹；生成模式还记录被测模型配置与Answer模板指纹。
- `cases.json`、`judge_prompt.txt`：冻结标签与裁判规则；生成模式另存 `answer_prompt.txt`。
- `raw.jsonl`：逐条保存完整原始trace、真实可见messages和输出；fixture保留固定输入，模型失败也保留发送的messages。
- `scores.jsonl`：逐项判断、原文断言和证据引用、Answer/裁判模型与用量、错误分类；校准还保留期望标签及命中情况。
- `report.md/report.csv`：样本结果和原因，校准命中、逐条变化及FAIL→PASS复核清单。

run/judge/run-fixtures/calibrate每条落盘并更新报告，默认对照最近scores；`--compare`可指定文件（calibrate默认自动对照）。配置相同与输入/答案相同分别报告，实时数据或生成答案变化不等于同样本裁判重复性。规则/模型变动后的FAIL→PASS必须独立审查，不自动宣称系统修好。只有全部所选有效样本PASS才正常评测退出0；未覆盖/异常退出1；输入/配置错误退出2；中断退出130。

## 离线检查

```bash
python3 -m unittest discover -s scripts/tests -p 'test_answer_eval.py'
```

单测使用合成trace、假裁判、临时目录和网络阻断，覆盖输入来源、失败分类、claim原文/路径、冻结输入、校准标签隔离、参数/密钥处理及复评。它验证评测代码，不能代替真实Answer模型和裁判效果测试。
