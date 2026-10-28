-- 已完成018的存量库执行一次；新库直接使用schema.sql，不执行本迁移。
-- 停止所有旧实例后迁移，再启动新版；不要混跑。RuleVersion.spec_hash保留用于确认。
ALTER TABLE monitor_execution DROP COLUMN spec_hash;
-- 固定DAG存于monitor_rule_version.rule_spec_json.dag，无需增加列。
-- 已确认旧版本没有冻结物理DAG，不能静默重编译/改写；请通过聊天重新创建并确认。
-- 旧checkpoint_json保留供审计，但schemaVersion=1不会用于新格式恢复（schemaVersion=2）。
