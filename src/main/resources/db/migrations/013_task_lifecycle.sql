-- 013: R3/R1/R2 统一任务生命周期。新库使用 schema.sql，无需执行本文件。
-- 老库先完成 010/011/012，停止旧应用、等待在途问答结束后执行，再启动新版。
-- DDL 会隐式提交；中途失败需检查完成到哪一步，不要忽略错误继续或整份重复执行。
-- 旧版每轮 UUID 无法可靠推断任务归属：旧成功快照分别作为 LEGACY 任务保留，不猜测合并。

CREATE TABLE conversation_frame (
    frame_id VARCHAR(64) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    first_query_no BIGINT NOT NULL,
    last_query_no BIGINT NOT NULL,
    last_turn_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    state_json JSON NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_frame_recent (conversation_id, last_query_no),
    KEY idx_frame_first (conversation_id, first_query_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE conversation_state ADD COLUMN active_frame_id VARCHAR(64) NULL AFTER active_frame_json;
ALTER TABLE conversation_query
    ADD COLUMN frame_id VARCHAR(64) NULL AFTER frame_json,
    ADD COLUMN frame_version BIGINT NULL AFTER frame_id,
    ADD COLUMN base_frame_version BIGINT NULL AFTER frame_version,
    ADD COLUMN task_action VARCHAR(16) NULL AFTER base_frame_version,
    ADD COLUMN task_state_json JSON NULL AFTER task_action;

-- 升级 012 前已有的活动快照：写回其来源轮次（其余无快照老轮次不能凭空恢复）。
UPDATE conversation_query q
JOIN conversation_state s ON s.conversation_id = q.conversation_id
    AND q.turn_id = JSON_UNQUOTE(JSON_EXTRACT(s.active_frame_json, '$.sourceTurnId'))
SET q.frame_json = s.active_frame_json
WHERE q.frame_json IS NULL AND s.active_frame_json IS NOT NULL;

UPDATE conversation_query
SET frame_id = JSON_UNQUOTE(JSON_EXTRACT(frame_json, '$.frameId')),
    frame_version = COALESCE(JSON_EXTRACT(frame_json, '$.version'), 1),
    base_frame_version = 0, task_action = 'LEGACY',
    task_state_json = JSON_OBJECT(
        'frameId', JSON_UNQUOTE(JSON_EXTRACT(frame_json, '$.frameId')),
        'conversationId', conversation_id,
        'version', COALESCE(JSON_EXTRACT(frame_json, '$.version'), 1),
        'originQuestion', question, 'firstQueryNo', query_no, 'lastQueryNo', query_no,
        'turnId', turn_id, 'status', COALESCE(turn_status, 'SUCCEEDED'),
        'requestTime', JSON_UNQUOTE(JSON_EXTRACT(frame_json, '$.requestTime')),
        'timezone', NULLIF(JSON_UNQUOTE(JSON_EXTRACT(frame_json, '$.timezone')), 'null'),
        'frame', frame_json, 'successfulFrame', frame_json, 'logicalPlan', logical_plan_json,
        'dialogue', JSON_ARRAY())
WHERE frame_json IS NOT NULL AND JSON_EXTRACT(frame_json, '$.frameId') IS NOT NULL;

-- 把仍在等待澄清/重试的旧记录转成独立任务。旧失败的有效计划从 query 表取，不再只存 has_valid_plan。
UPDATE conversation_query q
JOIN conversation_state s ON s.conversation_id = q.conversation_id
SET q.frame_id = CONCAT('legacy_', q.turn_id), q.frame_version = 1,
    q.base_frame_version = 0, q.task_action = 'LEGACY',
    q.task_state_json = JSON_OBJECT(
        'frameId', CONCAT('legacy_', q.turn_id), 'conversationId', q.conversation_id,
        'version', 1, 'originQuestion', q.question, 'firstQueryNo', q.query_no, 'lastQueryNo', q.query_no,
        'turnId', q.turn_id, 'status', q.turn_status,
        'requestTime', DATE_FORMAT(q.started_at, '%Y-%m-%dT%H:%i:%s.%fZ'), 'timezone', NULL,
        'frame', NULL, 'successfulFrame', NULL, 'logicalPlan', q.logical_plan_json,
        'dialogue', JSON_ARRAY(JSON_OBJECT('turnId', q.turn_id, 'question', q.question,
            'response', CASE WHEN q.turn_status = 'NEEDS_CLARIFICATION'
                THEN JSON_UNQUOTE(JSON_EXTRACT(s.pending_task_json, '$.clarify')) ELSE '上次执行失败' END)))
WHERE q.task_state_json IS NULL AND (
    (q.turn_status = 'NEEDS_CLARIFICATION'
        AND q.turn_id = JSON_UNQUOTE(JSON_EXTRACT(s.pending_task_json, '$.turnId')))
    OR (q.turn_status = 'FAILED'
        AND q.turn_id = JSON_UNQUOTE(JSON_EXTRACT(s.last_attempt_json, '$.turnId'))));

-- 其余已有完整 logical_plan 的轮次也保留为可回访任务；不伪造未知的 frame/时区。
-- started_at 为应用按 UTC 写入的请求时刻；缺该字段的更老记录不猜测日期锚点。
UPDATE conversation_query q
SET q.frame_id = CONCAT('legacy_', q.turn_id), q.frame_version = 1,
    q.base_frame_version = 0, q.task_action = 'LEGACY',
    q.task_state_json = JSON_OBJECT(
        'frameId', CONCAT('legacy_', q.turn_id), 'conversationId', q.conversation_id,
        'version', 1, 'originQuestion', q.question, 'firstQueryNo', q.query_no, 'lastQueryNo', q.query_no,
        'turnId', q.turn_id, 'status', q.turn_status,
        'requestTime', DATE_FORMAT(q.started_at, '%Y-%m-%dT%H:%i:%s.%fZ'), 'timezone', NULL,
        'frame', NULL, 'successfulFrame', NULL, 'logicalPlan', q.logical_plan_json,
        'dialogue', CASE WHEN q.turn_status = 'FAILED'
            THEN JSON_ARRAY(JSON_OBJECT('turnId', q.turn_id, 'question', q.question, 'response', '上次执行失败'))
            ELSE JSON_ARRAY() END)
WHERE q.task_state_json IS NULL AND q.logical_plan_json IS NOT NULL
    AND q.turn_id IS NOT NULL AND q.started_at IS NOT NULL
    AND q.turn_status IN ('SUCCEEDED', 'FAILED');

INSERT INTO conversation_frame
    (frame_id, conversation_id, version, first_query_no, last_query_no, last_turn_id, status, state_json)
SELECT frame_id, conversation_id, frame_version, query_no, query_no, turn_id,
    COALESCE(turn_status, 'SUCCEEDED'), task_state_json
FROM conversation_query WHERE task_state_json IS NOT NULL;

ALTER TABLE conversation_query
    ADD UNIQUE KEY uk_query_frame_version (conversation_id, frame_id, frame_version);

-- 当前指针采用最近一项有可恢复状态的任务，包括未完成任务；其他任务继续保留。
UPDATE conversation_state s
JOIN conversation_frame f ON f.conversation_id = s.conversation_id
JOIN (SELECT conversation_id, MAX(last_query_no) AS last_no FROM conversation_frame GROUP BY conversation_id) newest
    ON newest.conversation_id = f.conversation_id AND newest.last_no = f.last_query_no
SET s.active_frame_id = f.frame_id,
    s.active_frame_json = NULLIF(JSON_EXTRACT(f.state_json, '$.frame'), CAST('null' AS JSON)),
    s.pending_task_json = CASE WHEN f.status = 'NEEDS_CLARIFICATION' THEN f.state_json ELSE NULL END,
    s.last_attempt_json = CASE WHEN f.status = 'FAILED' THEN f.state_json ELSE NULL END;
