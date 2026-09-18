-- CEXPilot Phase 1 表结构（MySQL 8，幂等，可重复执行）
-- 设计约定：用于过滤/关联的字段一律是普通列；异构 payload 才放 JSON 列。

CREATE TABLE IF NOT EXISTS conversation (
    conversation_id VARCHAR(64)  NOT NULL PRIMARY KEY,
    title           VARCHAR(255) NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS conversation_query (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    query_no        INT          NOT NULL,
    question        TEXT         NOT NULL,
    answer          MEDIUMTEXT   NULL,
    trace_id        VARCHAR(64)  NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_query_conversation (conversation_id, query_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 一次问答 = 一条 ask_trace（就是 Trace 的主表，👎 之后靠它完整回放）
CREATE TABLE IF NOT EXISTS ask_trace (
    trace_id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    conversation_id   VARCHAR(64)   NULL,
    question          TEXT          NOT NULL,
    intent            VARCHAR(64)   NULL, -- planner 归类的统计 hint（intent 名 / UNKNOWN；出域为 NULL）
    status            VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    answer            MEDIUMTEXT    NULL,
    model             VARCHAR(64)   NULL,
    prompt_version    VARCHAR(16)   NULL,
    llm_steps         INT           NOT NULL DEFAULT 0,
    tool_calls        INT           NOT NULL DEFAULT 0,
    prompt_tokens     INT           NULL,
    completion_tokens INT           NULL,
    cost              DECIMAL(12,8) NULL,
    duration_ms       BIGINT        NULL,
    error             TEXT          NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       DATETIME      NULL,
    KEY idx_trace_conversation (conversation_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 每次 LLM 调用 / Tool 调用 = 一条 trace_event
CREATE TABLE IF NOT EXISTS trace_event (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id          VARCHAR(64) NOT NULL,
    seq               INT         NOT NULL,
    event_type        VARCHAR(16) NOT NULL, -- LLM_CALL / TOOL_CALL / PLAN
    name              VARCHAR(64) NULL,     -- tool 名或 llm
    input_json        JSON        NULL,
    output_json       JSON        NULL,
    duration_ms       BIGINT      NULL,
    prompt_tokens     INT         NULL,
    completion_tokens INT         NULL,
    error             TEXT        NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_event_trace (trace_id, seq)
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

-- in-domain 但未命中任何 intent 的 query（能力缺口数据集，供离线聚类与产品优先级用）
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
