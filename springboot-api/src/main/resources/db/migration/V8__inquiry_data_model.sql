-- V8: complete the inquiry data model with three tables.
-- EN: inquiries keeps only the CURRENT business state; inquiry_analysis and
--     inquiry_handling_record are append-only histories. Historical analysis evidence is migrated
--     into inquiry_analysis instead of being left behind on the main row. Unknown historical
--     states are deliberately left NULL: they must not be invented as SUCCESS/PENDING.
--     current_analysis_id is a LOGICAL reference to inquiry_analysis(id), with no physical FK, so
--     the current row and the append-only history never form a cyclic dependency or a cascade
--     delete chain.
-- 中文：inquiries 只保存当前业务状态；inquiry_analysis 与 inquiry_handling_record 是只追加历史表。
--     历史分析证据迁入 inquiry_analysis，不留在主表。无法确定历史状态一律留 NULL，绝不伪造为
--     SUCCESS/PENDING。current_analysis_id 是指向 inquiry_analysis(id) 的逻辑引用，不建物理外键，
--     避免当前行与历史表形成循环依赖或级联删除链。

-- EN: One ADD COLUMN per statement: supported by both MySQL and H2.
-- 中文：每次 ALTER 只加一列，MySQL 与 H2 都支持。
ALTER TABLE inquiries ADD COLUMN status VARCHAR(32) NULL;
ALTER TABLE inquiries ADD COLUMN priority VARCHAR(16) NULL;
ALTER TABLE inquiries ADD COLUMN category_source VARCHAR(16) NULL;
ALTER TABLE inquiries ADD COLUMN priority_source VARCHAR(16) NULL;
ALTER TABLE inquiries ADD COLUMN analysis_status VARCHAR(16) NULL;
ALTER TABLE inquiries ADD COLUMN current_analysis_id BIGINT NULL;
ALTER TABLE inquiries ADD COLUMN handled_by VARCHAR(100) NULL;
ALTER TABLE inquiries ADD COLUMN handled_at TIMESTAMP NULL;
ALTER TABLE inquiries ADD COLUMN handling_note TEXT NULL;
ALTER TABLE inquiries ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE inquiries ADD COLUMN resolved_at TIMESTAMP NULL;
ALTER TABLE inquiries ADD COLUMN closed_at TIMESTAMP NULL;
ALTER TABLE inquiries ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- EN: One row per AI analysis attempt. version is scoped to the inquiry, not a JPA optimistic lock.
-- 中文：每次 AI 分析一行。version 在咨询内编号，不是 JPA 乐观锁版本号。
CREATE TABLE inquiry_analysis (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  inquiry_id BIGINT NOT NULL,
  version INT NOT NULL,
  category VARCHAR(40) NULL,
  priority VARCHAR(16) NULL,
  confidence DECIMAL(5,4) NULL,
  needs_human_review BOOLEAN NULL,
  analysis_reason TEXT NULL,
  suggested_reply TEXT NULL,
  analysis_source VARCHAR(20) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  error_message TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL,
  CONSTRAINT uq_inquiry_analysis_version UNIQUE (inquiry_id, version),
  CONSTRAINT fk_inquiry_analysis_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiries(id),
  CONSTRAINT chk_inquiry_analysis_status CHECK (status IN ('PENDING','PROCESSING','SUCCESS','FAILED')),
  CONSTRAINT chk_inquiry_analysis_version CHECK (version > 0)
);

CREATE INDEX idx_inquiry_analysis_inquiry ON inquiry_analysis(inquiry_id);

-- EN: old_value/new_value hold JSON documents as TEXT: portable across the supported MySQL versions
--     and H2, and large enough for a category/priority/status snapshot.
-- 中文：old_value/new_value 用 TEXT 存 JSON 文档，兼容目标 MySQL 版本与 H2，容量足够保存分类/优先级/状态快照。
CREATE TABLE inquiry_handling_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  inquiry_id BIGINT NOT NULL,
  operator_id VARCHAR(100) NULL,
  action_type VARCHAR(40) NOT NULL,
  old_value TEXT NULL,
  new_value TEXT NULL,
  note TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_inquiry_handling_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiries(id),
  CONSTRAINT chk_inquiry_handling_action CHECK (action_type IN
    ('ASSIGN','STATUS_CHANGE','CATEGORY_CHANGE','PRIORITY_CHANGE','NOTE_ADD',
     'REPLY_DRAFT','RESOLVE','CLOSE','REOPEN'))
);

CREATE INDEX idx_inquiry_handling_inquiry_created ON inquiry_handling_record(inquiry_id, created_at);

ALTER TABLE inquiries ADD CONSTRAINT chk_inquiries_status CHECK (status IS NULL OR status IN
  ('PENDING','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','CLOSED'));
ALTER TABLE inquiries ADD CONSTRAINT chk_inquiries_priority CHECK (priority IS NULL OR priority IN
  ('LOW','MEDIUM','HIGH','URGENT'));
ALTER TABLE inquiries ADD CONSTRAINT chk_inquiries_analysis_status CHECK (analysis_status IS NULL OR analysis_status IN
  ('PENDING','PROCESSING','SUCCESS','FAILED'));
ALTER TABLE inquiries ADD CONSTRAINT chk_inquiries_category_source CHECK (category_source IS NULL OR category_source IN ('AI','HUMAN'));
ALTER TABLE inquiries ADD CONSTRAINT chk_inquiries_priority_source CHECK (priority_source IS NULL OR priority_source IN ('AI','HUMAN'));

CREATE INDEX idx_inquiries_status ON inquiries(status);
CREATE INDEX idx_inquiries_analysis_status ON inquiries(analysis_status);
CREATE INDEX idx_inquiries_updated_at ON inquiries(updated_at);

-- EN: A historical row has analysis evidence if ANY analysis field is populated. Each such row gets
--     exactly one version=1 analysis record. The AI's original category is ai_category when present;
--     pre-V7 rows have no snapshot, so their stored `category` is the only AI result available.
--     priority/analysis_reason/error_message have no historical source and stay NULL.
-- 中文：任一分析字段非空即视为有分析证据，每张这样的记录生成一条 version=1 的分析历史。
--     AI 原始分类优先取 ai_category；V7 之前无快照，只能用现有 category。
--     priority/analysis_reason/error_message 无历史来源，保持 NULL。
INSERT INTO inquiry_analysis
  (inquiry_id, version, category, confidence, needs_human_review, suggested_reply,
   analysis_source, status, created_at, completed_at)
SELECT i.id,
       1,
       COALESCE(i.ai_category, i.category),
       i.confidence,
       i.needs_human_review,
       i.suggested_reply,
       i.analysis_source,
       'SUCCESS',
       i.created_at,
       i.created_at
FROM inquiries i
WHERE i.analysis_source IS NOT NULL
   OR i.confidence IS NOT NULL
   OR i.needs_human_review IS NOT NULL
   OR i.suggested_reply IS NOT NULL
   OR i.ai_category IS NOT NULL;

-- EN: Point each inquiry at the analysis row just created for it. Never guess for rows without one.
-- 中文：每笔咨询指向刚为其创建的分析记录；没有分析证据的历史记录绝不猜测引用。
UPDATE inquiries i
SET i.current_analysis_id = (
      SELECT a.id FROM inquiry_analysis a WHERE a.inquiry_id = i.id AND a.version = 1
    )
WHERE EXISTS (SELECT 1 FROM inquiry_analysis a WHERE a.inquiry_id = i.id);

-- EN: Only rows with a real migrated analysis are SUCCESS. Rows without analysis stay NULL, so a
--     future upgrade cannot mistake them for completed analyses.
-- 中文：只有确实迁入分析记录的行才标记 SUCCESS；无分析证据的行保持 NULL，后续升级不会误判为分析完成。
UPDATE inquiries
SET analysis_status = 'SUCCESS'
WHERE current_analysis_id IS NOT NULL;

-- EN: The source of the CURRENT category is inferable only when the AI snapshot exists:
--     equal -> unchanged AI judgement; different -> a human edited it. Pre-V7 rows stay NULL.
-- 中文：只有当 AI 快照存在时才能推断当前分类来源：相同表示未修改的 AI 结论；不同表示人工改过。
--     V7 之前的记录保持 NULL。
UPDATE inquiries
SET category_source = CASE
      WHEN ai_category IS NOT NULL AND category = ai_category THEN 'AI'
      WHEN ai_category IS NOT NULL AND category <> ai_category THEN 'HUMAN'
      ELSE NULL
    END
WHERE ai_category IS NOT NULL;
