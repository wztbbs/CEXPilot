-- CEXPilot Phase 1 表结构（MySQL 8，幂等，可重复执行）
-- 设计约定：用于过滤/关联的字段一律是普通列；异构 payload 才放 JSON 列。

CREATE TABLE IF NOT EXISTS conversation (
    conversation_id VARCHAR(64)  NOT NULL PRIMARY KEY,
    visitor_id      VARCHAR(64)  NULL, -- 访客标识（cexpilot_uid cookie），当前约定 1 访客 1 会话
    title           VARCHAR(255) NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_conversation_visitor (visitor_id, last_active_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS conversation_query (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    query_no        INT          NOT NULL,
    question        TEXT         NOT NULL,
    answer          MEDIUMTEXT   NULL,
    trace_id        VARCHAR(64)  NULL,
    -- 轮次生命周期（多轮追问阶段一 a）：
    -- turn_id 全局唯一标识一轮问答；parent_turn_id 记录前端声明的上一轮；turn_status 取值
    -- RUNNING / SUCCEEDED / FAILED（后续可扩展 NEEDS_CLARIFICATION）；started_at/finished_at 为服务端时间。
    -- 旧数据这些列全为 NULL，renderContext 等读取方按 NULL=历史成功轮次处理。
    turn_id         VARCHAR(64)  NULL,
    parent_turn_id  VARCHAR(64)  NULL,
    turn_status     VARCHAR(32)  NULL,
    started_at      DATETIME(6)  NULL,
    finished_at     DATETIME(6)  NULL,
    -- 校验通过的 LogicalPlan 原文 JSON（Planner 草稿不落这里；规划未通过校验时为空）
    logical_plan_json JSON       NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_query_turn (turn_id),
    UNIQUE KEY uk_query_conv_no (conversation_id, query_no),
    KEY idx_query_conversation (conversation_id, query_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 会话状态行：原子序号分配 + 同会话串行执行占用（多轮追问阶段一 a）。
-- last_query_no 存"最后已分配的序号"，初始为 0，分配时原子 +1；running_turn_id/lease_until 为执行占用，
-- fencing_token 单调递增，提交时校验——租约过期被新请求接管后，旧执行者凭过期 token 无法覆盖状态。
CREATE TABLE IF NOT EXISTS conversation_state (
    conversation_id VARCHAR(64)  NOT NULL PRIMARY KEY,
    version         BIGINT       NOT NULL DEFAULT 0,
    last_query_no   BIGINT       NOT NULL DEFAULT 0,
    running_turn_id VARCHAR(64)  NULL,
    lease_until     DATETIME(6)  NULL,
    fencing_token   BIGINT       NOT NULL DEFAULT 0,
    -- 活动 QueryFrame（多轮追问阶段一 b）：成功轮次提交时覆盖，失败轮次不覆盖；
    -- 下一轮由 ConversationContextService 读取注入 Planner 并做继承对账（阶段一 c）；
    -- 写入同样带 running_turn_id + fencing_token 条件，过期执行者不得覆盖新 frame
    active_frame_json JSON       NULL,
    -- 待补澄清任务（审查 R1）：独立于活动 frame 保存——新会话没有 frame 也能接续澄清，
    -- 澄清不挂在旧任务上。结构 {question, clarify, turnId}；成功轮次提交时清空。
    pending_task_json JSON       NULL,
    -- 最近失败尝试（审查 R2）："再试一次/那 OKX 呢"接续失败任务的依据。
    -- 结构 {turnId, question, status, traceId, has_valid_plan}；成功轮次提交时清空。
    last_attempt_json JSON       NULL,
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============ 已有部署的迁移注意点（DBA 手工执行，schema 演进不自动跑） ============
-- 1. conversation_query 加列：
--      ALTER TABLE conversation_query
--          ADD COLUMN turn_id VARCHAR(64) NULL,
--          ADD COLUMN parent_turn_id VARCHAR(64) NULL,
--          ADD COLUMN turn_status VARCHAR(32) NULL,
--          ADD COLUMN started_at DATETIME(6) NULL,
--          ADD COLUMN finished_at DATETIME(6) NULL;
-- 2. 加 uk_query_turn 唯一键前无需处理（旧数据 turn_id 全 NULL，MySQL 唯一键允许多个 NULL）：
--      ALTER TABLE conversation_query ADD UNIQUE KEY uk_query_turn (turn_id);
-- 3. 加 uk_query_conv_no 前必须检查历史重复序号（旧代码 MAX+1 并发下可能产生重复），
--    有重复需先人工清理/重排，代码不替 DBA 做决定：
--      SELECT conversation_id, query_no, COUNT(*) c FROM conversation_query
--      GROUP BY conversation_id, query_no HAVING c > 1;
--      ALTER TABLE conversation_query ADD UNIQUE KEY uk_query_conv_no (conversation_id, query_no);
-- 4. conversation_state 为新表，CREATE TABLE IF NOT EXISTS 即可，存量会话首次提问时自动建行。
-- 5. 阶段一(b) 增量列（旧部署需手工 ALTER；新列均可空，存量行不受影响）：
--      ALTER TABLE conversation_query ADD COLUMN logical_plan_json JSON NULL;
--      ALTER TABLE conversation_state ADD COLUMN active_frame_json JSON NULL;
-- 6. 阶段二（migrations/008_conversation_artifact.sql）：trace_event.event_type 加宽到 VARCHAR(32)
--    （EVIDENCE_REUSE_DECISION 等长事件名），ask_trace 加 reused_facts 列，新建 conversation_artifact。
--    必须先迁移后部署，否则新事件名写库会被截断报错。
-- 7. 阶段三（migrations/009_turn_counters.sql）：ask_trace 加 planner_llm_calls / answer_llm_calls /
--    metric_fetch_count / calculation_count / http_request_count 计数列。
-- 8. 序号语义调整（migrations/010_last_query_no.sql）：旧库 next_query_no 转为 last_query_no，值减 1。
-- 9. 审查修复 R1/R2（migrations/011_pending_task_and_last_attempt.sql）：conversation_state 加
--    pending_task_json / last_attempt_json 两列（澄清任务与失败尝试独立于活动 frame 保存）。
--    停止旧应用并等待在途问答结束后迁移，再启动新版本；新库无需执行该迁移。

-- 一次问答 = 一条 ask_trace（就是 Trace 的主表，👎 之后靠它完整回放）
CREATE TABLE IF NOT EXISTS ask_trace (
    trace_id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    conversation_id   VARCHAR(64)   NULL,
    question          TEXT          NOT NULL,
    intent            VARCHAR(64)   NULL, -- 历史意图标签，仅兼容旧数据；当前流程不再写入
    visitor_id        VARCHAR(64)   NULL, -- 访客标识（cexpilot_uid cookie）
    status            VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    answer            MEDIUMTEXT    NULL,
    model             VARCHAR(64)   NULL,
    prompt_version    VARCHAR(16)   NULL,
    build_commit      VARCHAR(64)   NULL, -- 构建时打入的 git commit 指纹（定位答案对应的代码版本）
    llm_steps         INT           NOT NULL DEFAULT 0,
    tool_calls        INT           NOT NULL DEFAULT 0,
    reused_facts      INT           NOT NULL DEFAULT 0, -- 本轮复用的历史事实数（阶段二 FACT_REUSED 计数）
    planner_llm_calls INT           NOT NULL DEFAULT 0, -- planner 阶段 LLM 调用次数（含修复重试）
    answer_llm_calls  INT           NOT NULL DEFAULT 0, -- answer 阶段 LLM 调用次数（0 或 1）
    metric_fetch_count INT          NOT NULL DEFAULT 0, -- metric 节点真实取数次数（REUSE_FACT 不算）
    calculation_count INT           NOT NULL DEFAULT 0, -- 算子节点执行次数
    http_request_count INT          NOT NULL DEFAULT 0, -- 真实 HTTP 请求数（分页/重试逐次计）
    prompt_tokens     INT           NULL,
    completion_tokens INT           NULL,
    cost              DECIMAL(12,8) NULL,
    duration_ms       BIGINT        NULL,
    error             TEXT          NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       DATETIME      NULL,
    KEY idx_trace_conversation (conversation_id, created_at),
    KEY idx_trace_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 每次 LLM 调用 / Tool 调用 = 一条 trace_event
CREATE TABLE IF NOT EXISTS trace_event (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id          VARCHAR(64) NOT NULL,
    seq               INT         NOT NULL,
    event_type        VARCHAR(32) NOT NULL, -- LLM_CALL / TOOL_CALL / PLAN / METRIC_RESULT / EVIDENCE_REUSE_DECISION / FACT_REUSED 等
    name              VARCHAR(64) NULL,     -- tool 名或 llm
    input_json        JSON        NULL,
    output_json       JSON        NULL,
    duration_ms       BIGINT      NULL,
    prompt_tokens     INT         NULL,
    completion_tokens INT         NULL,
    ttft_ms           BIGINT      NULL, -- 首 token 时间（prefill 耗时），仅 LLM_CALL 流式路径有值
    cached_tokens     INT         NULL, -- prompt 缓存命中量，仅 LLM_CALL 有值
    error             TEXT        NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_event_trace (trace_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 会话级取数事实（多轮追问阶段二）：一轮成功取数的完整 MetricResultJson 输出，
-- 供后续轮次以 reference_mode=pinned_result + evidence_ref 绑定复用（REUSE_FACT）。
-- kind 本期固定 'FACT'；payload 超 256KB 不缓存（payload_json=NULL，metadata_json 记原因）；
-- retained_until = 保存时 +24h，过期清理后引用走"原快照不可恢复"澄清路径，禁止重查冒充。
CREATE TABLE IF NOT EXISTS conversation_artifact (
    artifact_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    kind            VARCHAR(16)  NOT NULL DEFAULT 'FACT',
    source_turn_id  VARCHAR(64)  NULL,
    source_trace_id VARCHAR(64)  NULL,
    lookup_key      CHAR(64)     NOT NULL,
    schema_version  VARCHAR(16)  NOT NULL,
    derived         TINYINT(1)   NOT NULL DEFAULT 0,
    metadata_json   JSON         NOT NULL,
    payload_json    JSON         NULL,
    fetched_at      DATETIME(6)  NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retained_until  DATETIME(6)  NOT NULL,
    KEY idx_artifact_conv (conversation_id, kind, lookup_key),
    KEY idx_artifact_retained (retained_until)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS feedback (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id   VARCHAR(64) NOT NULL,
    rating     VARCHAR(8)  NOT NULL, -- up / down
    category   VARCHAR(32) NULL,     -- data_error / reasoning_error / missing_info / not_understood / context_error / tool_error / other
    comment    TEXT        NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_feedback_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 历史未分类 query 表，保留旧数据；当前流程不再写入
CREATE TABLE IF NOT EXISTS unmatched_query (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id        VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NULL,
    question        TEXT         NOT NULL,
    parse_json      JSON         NULL, -- 意图识别 LLM 的完整解析结果
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_unmatched_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS eval_run (
    run_id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    eval_type         VARCHAR(32)   NOT NULL,
    category          VARCHAR(32)   NULL,
    total             INT           NOT NULL DEFAULT 0,
    passed            INT           NOT NULL DEFAULT 0,
    tool_accuracy     DECIMAL(5,4)  NULL,
    grounding_pass    INT           NOT NULL DEFAULT 0,
    model             VARCHAR(64)   NULL,
    prompt_version    VARCHAR(16)   NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS eval_case_result (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id           VARCHAR(64) NOT NULL,
    case_id          VARCHAR(64) NOT NULL,
    question         TEXT        NOT NULL,
    passed           TINYINT(1)  NOT NULL DEFAULT 0,
    expected_tools   JSON        NULL,
    actual_tools     JSON        NULL,
    missing_tools    JSON        NULL,
    forbidden_hit    JSON        NULL,
    evidence_missing JSON        NULL,
    grounding_passed TINYINT(1)  NULL,
    detail           JSON        NULL,
    trace_id         VARCHAR(64) NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_eval_result_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
