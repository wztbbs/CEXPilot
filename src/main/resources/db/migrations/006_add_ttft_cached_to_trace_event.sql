-- 006: trace_event 增加 ttft_ms / cached_tokens 列（LLM 首 token 时间与 prompt 缓存命中量）
-- 仅已存在的老库需要执行；新库由 schema.sql 直接建出该列，无需执行本文件。
ALTER TABLE trace_event
    ADD COLUMN ttft_ms       BIGINT NULL AFTER completion_tokens,
    ADD COLUMN cached_tokens INT    NULL AFTER ttft_ms;
