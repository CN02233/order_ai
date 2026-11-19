-- Separate the AI's original category from the business category a human may edit.
-- EN: The column stays NULL on purpose: historical rows have no reliable AI snapshot, so they
--     must not be backfilled from `category`. Java sets ai_category = category for new inquiries.
-- 中文：该列刻意保持 NULL：历史记录没有可信的 AI 快照，不能用 `category` 回填。
--     Java 在创建新咨询时令 ai_category = category。
ALTER TABLE inquiries
  ADD COLUMN ai_category VARCHAR(40) NULL;
