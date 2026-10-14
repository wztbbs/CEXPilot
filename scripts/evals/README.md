# Planner 文件评测

入口 `scripts/planner_eval.py`，仅依赖 Python 3.9+ 标准库。旧脚本、服务端旧 eval、数据库表均保留。

本套件只评 Planner。`collect/run` 通过真实 `/api/ask` 采集，因此仍执行取数与 answer，前置轮也是真实问答；它们不属于本报告的评分对象。当前没有纯 planner HTTP 接口，不用近似重写 Java 的 prompt/编译器来假装测试真实系统。已有完整 trace 可离线导入后仅调用裁判复评。

## 裁判配置

必填，代码没有默认裁判厂商、模型或密钥：

| 环境变量 | 内容 |
| --- | --- |
| `PLANNER_EVAL_JUDGE_BASE_URL` | OpenAI 兼容 Chat Completions 地址前缀，通常以 `/v1` 结束；也接受完整 `/chat/completions` 地址 |
| `PLANNER_EVAL_JUDGE_API_KEY` | 裁判 API key |
| `PLANNER_EVAL_JUDGE_MODEL` | 裁判模型名 |

可选：

| 环境变量 | 默认/用途 |
| --- | --- |
| `PLANNER_EVAL_TARGET_URL` | 无默认值；目标 CEXPilot 站点，可被 `--target-url` 覆盖 |
| `PLANNER_EVAL_JUDGE_TIMEOUT` | `240`，裁判单次 HTTP 超时秒数 |
| `PLANNER_EVAL_JUDGE_RETRIES` | `0`，裁判传输层失败（连接中断/超时）的最大重试次数，指数退避；HTTP 错误码与裁判协议问题不重试；可被 `--judge-retries` 覆盖 |
| `PLANNER_EVAL_JUDGE_RESPONSE_FORMAT` | `json_schema`，裁判输出结构约束：严 Schema 强制 criteria 数组、verdict 枚举、reason/evidence 非空且 evidence 为字符串数组；`json_object` 为弱约束，`off` 关闭。通道不支持严 Schema 会在首次调用以 HTTP 错误显式暴露 |
| `PLANNER_EVAL_JUDGE_EXTRA_BODY` | JSON 对象，例如 `{"reasoning_effort":"high","response_format":{"type":"json_object"}}`；必须符合所选厂商协议，不默认传 temperature 等模型不一定支持的字段 |

密钥只用于 Authorization，不写入 manifest/report；请求失败不打印可能回显凭据的服务端响应体。配置错误在 `run` 采集前检查。目标系统请求不自动重试；裁判传输层失败默认不重试，可用 `PLANNER_EVAL_JUDGE_RETRIES` 或 `--judge-retries` 开启有限重试。返回内容必须是完整 JSON，裁判 `finish_reason` 必须为 `stop`，不接受截断后“抢救”JSON。

上面协议不能直接调用 Anthropic 原生 Messages API；使用支持 Chat Completions 的接入点，或以后单独增加适配器。ChatGPT/Codex 订阅不等于脚本的 API key。

## 命令

以下均在仓库根目录执行。`list/validate/import-trace/report` 不请求网络；`judge` 只调用裁判；`collect/run` 请求目标系统。

```bash
# 离线检查用例格式、列出覆盖维度
python3 scripts/planner_eval.py validate
python3 scripts/planner_eval.py list --only P001,P023,P037

# 显式运行一条：真实问答采集 + 判首次/最后一次输出
python3 scripts/planner_eval.py run --target-url https://cex.hrscrm.net --only P001

# 先采集，不需要旗舰裁判的配置
python3 scripts/planner_eval.py collect --target-url https://cex.hrscrm.net --only P001,P023

# 用上一命令打印的目录替换 RUN_DIR；不会再次调用 /api/ask
python3 scripts/planner_eval.py judge RUN_DIR/raw.jsonl

# 用例标注修改后显式重新评分同一份输入输出，不重复请求目标服务
python3 scripts/planner_eval.py judge RUN_DIR/raw.jsonl \
  --cases scripts/evals/planner_cases.json --only P001

# 已有 /api/trace_detail 的完整 JSON，问题必须与所选 case 对得上
python3 scripts/planner_eval.py import-trace TRACE_FILE.json --only P001

# 本轮与上轮逐条对照；report 纯离线
python3 scripts/planner_eval.py report NEW_RUN/scores.jsonl --compare OLD_RUN/scores.jsonl
```

每条问答可能耗时数分钟，多轮用例更长。只有收到明确执行指令才运行网络命令。全量运行放后台，例如配置环境变量后：

```bash
nohup python3 -u scripts/planner_eval.py run --target-url https://cex.hrscrm.net \
  > /tmp/cexpilot-planner-eval.log 2>&1 &
```

## 用例和人工标注

用例在 `planner_cases.json`，不需要修改 Python。当前42条是依据仓库契约设计的种子用例，标注来源是 `repository_contract`；还没有经过人工/旗舰模型实测校准，不宣称完整覆盖。后续真实失败和业务表达应继续补充。

每条包含 `id/title/question/timezone/setup_questions/tags/coverage` 与非空 `criteria`。每个 criterion 包含唯一 `id`、`dimension` 和自然语言 `expectation`，精确说明对应字段、对象、口径、计算方向与允许的等价方案。维度：decision、instrument、metric_shape、time、parameters、calculation、context、minimality。

`expected_fields` 可选，用于无歧义的信封字段：in_domain、needs_clarification、new_frame、task_action。后三者缺省按false/false/CONTINUE处理。这部分由程序比较，模型裁判不能覆盖机检 FAIL。节点 ID、排列顺序、同条件查询组的拆分不作为硬编码标准；指标、时间、参数、引用对象和计算方向由逐项 criterion 判断。

人工直接修改 expectation/expected_fields，并可修改 label_source 标记审核来源。`judge --cases 新标注文件` 会保存新旧标注指纹并采用新标注。只改标注可以复评；更改 question/timezone/setup_questions 需要重新采集，不能把新问题套到旧trace。默认 judge 使用采集时冻结的标注，不自动读取今天改过的文件。

覆盖包含五种查询形态、四种TimeSpec、九个算子、多层引用、最小必要计划、默认/显式条件、支持/澄清/出域/部分可答，以及多轮继承、pinned/latest、歧义和回访。维度/feature计数用于定位已声明覆盖，不是全组合覆盖率或证明所有分支均被触发。历史复用和任务回访依赖前置轮真的建立事实/任务；输入前提缺失或线上目录与标注冲突时裁判必须给UNCERTAIN，避免虚绿。

## 判据与报告

脚本从 `LLM_CALL/dag_planner` 读取实际 `messages/content/response_format`，不读旧 `plan.nodes`，也不从最终answer或工具调用数猜plan。`first/final` 是首次和最后一次模型输出；单次调用只判一次，不重复付费。服务端PLAN校验结果单独记录，最后一次模型输出判PASS不等于服务端已接受；这种不一致整体记PLANNER_REJECTED，不计整体PASS。

程序检查基础信封、节点ID、查询形态基础参数及完整引用语法/交易所分支；空计划（metrics 与 calculations 均空）与 Java `evaluateReplyWithoutPlan` 同口径，等价于缺 plan，无明确 reply/clarify 即协议违约。Java编译器仍是完整能力约束和执行校验的权威；Python不复制Provider、算子或时间解析实现。

评分分两层分别展示：**协议层**（plan=null 口径、空计划、字段类型、引用格式，以及 new_frame/task_action 等声明类 expected_fields 机检）由程序判定；**语义层**（是否该澄清、指标/时间/计算方向是否正确，以及 in_domain/needs_clarification 等决策类 expected_fields 机检）由裁判判定、机检 FAIL 优先于裁判结论。协议失败不免除语义判分，但裁判不能覆盖程序的协议判定：协议 FAIL 即整体 FAIL。输出不是完整 JSON 时协议 FAIL、语义记 NOT_EVALUABLE。服务端的程序兜底（如 CLARIFY_FALLBACK 代写澄清文案）单独列出，不记为模型能力。

裁判收到完整实际planner输入及逐项标注，不收到最终answer、行情FACTS或服务端编译结论。仅提取同trace的固定request_time_context用于核验时间，明确区分它与模型当时可见的信息。忽略示例id/排列/话术差异，核对语义等价、baseline方向、历史ref及任务缺口。

裁判必须给每个criterion一个 PASS/FAIL/UNCERTAIN 和具体reason/evidence。少判、重复ID、无依据、格式异常、截断、拒绝均记JUDGE_ERROR，不转为PASS。历史trace缺完整prompt记NOT_EVALUABLE；采集失败记COLLECTION_ERROR；planner调用失败记PLANNER_ERROR；服务端拒绝但裁判通过记PLANNER_REJECTED；结构非法或明确语义错误记FAIL。没有任何失败或不确定才PASS。

产物位于 `importants/回归测试/planner-eval/<上海时间戳>-<随机后缀>/`：

- `manifest.json`：模式、用例/脚本/裁判prompt指纹、配置指纹、上轮备份路径；不包含API key。
- `cases.json`、`judge_prompt.txt`：本轮冻结的标注和裁判规则。
- `raw.jsonl`：逐条保存原始问答、前置轮、完整trace和planner输入输出。
- `scores.jsonl`：首次/最后一次输出的判据结果、服务端接受状态、裁判调用用量/耗时、请求与返回的模型名。
- `report.md`、`report.csv`：阶段PASS比例、异常分布、维度结果和逐条原因。

每次新建目录，不覆盖已有产物；创建前把上一轮目录（包含raw和报告）复制到 `_backups`。逐条落盘；run/judge每完成一条更新报告，取消后已完成结果保留。run/judge默认与产物根目录中最近的scores逐条对照；`--compare`可指定run/judge/report的对照文件。报告列出首次/最后一次verdict与逐项判据变化、新增、缺失和评测口径变化。标注、裁判、参数、prompt或评测代码变了时标不可直接同比。FAIL→PASS单独列出人工复核清单，不自动宣称已修复；本套件不评行情数值真值。

网络命令退出码：0=所选样本均PASS（collect为均采集完成），1=有FAIL/UNCERTAIN/异常，2=配置/输入错误，130=中断。不要为得到绿色报告排除异常样本。

## 离线验证

评测器测试使用构造trace、假裁判和临时目录，阻止HTTP请求；不执行线上回归：

```bash
python3 -m unittest discover -s scripts/tests -p 'test_planner_eval.py' -v
```
