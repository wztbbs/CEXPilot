-- 009: 阶段三计数拆分——ask_trace 增加独立计数列。
-- tool_calls 混合了指标与计算节点，不足以衡量外部调用；新增：
--   planner_llm_calls / answer_llm_calls 按 LLM_CALL 的 stage 区分；
--   metric_fetch_count 为 metric 节点真实取数次数（REUSE_FACT 不算）；
--   calculation_count 为算子节点执行次数；
--   http_request_count 为真实 HTTP 请求数（一个节点分页/重试多次请求会记多次）。
-- 存量行默认 0，只表示"未统计"，不与 0 次调用混淆解读。
ALTER TABLE ask_trace ADD COLUMN planner_llm_calls INT NOT NULL DEFAULT 0 AFTER reused_facts;
ALTER TABLE ask_trace ADD COLUMN answer_llm_calls INT NOT NULL DEFAULT 0 AFTER planner_llm_calls;
ALTER TABLE ask_trace ADD COLUMN metric_fetch_count INT NOT NULL DEFAULT 0 AFTER answer_llm_calls;
ALTER TABLE ask_trace ADD COLUMN calculation_count INT NOT NULL DEFAULT 0 AFTER metric_fetch_count;
ALTER TABLE ask_trace ADD COLUMN http_request_count INT NOT NULL DEFAULT 0 AFTER calculation_count;
