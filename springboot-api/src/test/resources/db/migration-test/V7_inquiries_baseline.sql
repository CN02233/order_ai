-- TEST FIXTURE ONLY. Not a production migration.
-- EN: Reproduces the post-V7 `inquiries` table (the definition that schema.sql carried before V8)
--     so an upgrade test can start from a genuine V7 state. Production migrations are never edited.
-- 中文：复现 V7 完成后的 `inquiries` 表（V8 之前 schema.sql 中的定义），使升级测试能真正从 V7 状态开始。
--     生产迁移脚本不会被修改。
CREATE TABLE inquiries(
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  idempotency_key VARCHAR(128) UNIQUE NOT NULL,
  customer_email VARCHAR(255),
  subject VARCHAR(500),
  body TEXT,
  category VARCHAR(40),
  ai_category VARCHAR(40) NULL,
  confidence DECIMAL(5,4) NULL,
  needs_human_review BOOLEAN NULL,
  suggested_reply TEXT NULL,
  analysis_source VARCHAR(20) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
