-- 005: ask_trace 增加 build_commit 列（构建时打入的 git commit 指纹）
-- 仅已存在的老库需要执行；新库由 schema.sql 直接建出该列，无需执行本文件。
ALTER TABLE ask_trace ADD COLUMN build_commit VARCHAR(64) NULL AFTER prompt_version;
