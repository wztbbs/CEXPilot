-- 合并调用改造：ask_trace 增加 intent 列（planner 归类的统计 hint：intent 名 / UNKNOWN；出域为 NULL）。
-- 用于已按旧版 schema.sql 建好的库；新库直接由 schema.sql 建出该列，无需执行本文件。
ALTER TABLE ask_trace ADD COLUMN intent VARCHAR(64) NULL AFTER question;
