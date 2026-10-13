-- 012: 审查修复 R3 一期——轮次级 frame 快照落 conversation_query，历史任务可回访。
-- 背景：此前每成功轮覆盖 conversation_state.active_frame_json，会话只有一份活状态，
-- "回到最开始那个问题"没有可寻址的历史任务状态。轮次行加 frame_json 后，每成功轮
-- 保留当时的完整 QueryFrame 快照，Planner 可通过【近期任务索引】引用（task_action=RESUME）。
-- 仅已存在的老库需要执行；新库由 schema.sql 直接建出，无需执行本文件。
ALTER TABLE conversation_query
    ADD COLUMN frame_json JSON NULL AFTER logical_plan_json;
-- 存量行 frame_json 全 NULL：按"无快照、不进任务索引"处理，读取方兼容，无需回填。
