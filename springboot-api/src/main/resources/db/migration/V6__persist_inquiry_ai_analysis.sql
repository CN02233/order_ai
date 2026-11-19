-- Persist the complete inquiry analysis result returned by the Python service.
-- EN: Every column is nullable so inquiries created before this migration remain readable.
--     Java writes all four values together only after the whole analysis response passes validation.
-- 中文：所有列均可空，保证迁移前创建的咨询记录仍可读。
--     Java 只在完整分析结果通过校验后，才将四个值一起写入。
ALTER TABLE inquiries
  ADD COLUMN confidence DECIMAL(5,4) NULL,
  ADD COLUMN needs_human_review BOOLEAN NULL,
  ADD COLUMN suggested_reply TEXT NULL,
  ADD COLUMN analysis_source VARCHAR(20) NULL;
