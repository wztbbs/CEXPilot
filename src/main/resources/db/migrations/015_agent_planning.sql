-- 存量库手工执行一次；需先完成此前迁移。路由/监控规划上下文独立于查询 TaskState。
ALTER TABLE conversation_state
    ADD COLUMN active_agent_type VARCHAR(16) NULL,
    ADD COLUMN monitor_planning_json JSON NULL;
ALTER TABLE conversation_query
    ADD COLUMN agent_type VARCHAR(16) NULL,
    ADD COLUMN agent_payload_json JSON NULL;
ALTER TABLE ask_trace
    ADD COLUMN agent_type VARCHAR(16) NULL,
    ADD COLUMN router_llm_calls INT NOT NULL DEFAULT 0;
