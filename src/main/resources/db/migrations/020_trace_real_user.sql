-- 来源标记：存量库上线前执行一次。先以 NULL 添加，避免将历史脚本流量误标为真实用户。
ALTER TABLE ask_trace ADD COLUMN is_real_user BOOLEAN NULL DEFAULT NULL;
-- 只改变以后 INSERT 的默认值，不回填已有行。应用对新请求显式写入 true/false。
ALTER TABLE ask_trace ALTER COLUMN is_real_user SET DEFAULT TRUE;
