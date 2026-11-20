package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "inquiries")
public class Inquiry {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "idempotency_key", unique = true)
  public String idempotencyKey;

  @Column(name = "customer_email")
  public String customerEmail;

  public String subject;

  @Column(columnDefinition = "TEXT")
  public String body;

  public String category;

  // EN: The category Python originally returned. Manual edits only change `category`, so this
  //     snapshot always preserves the AI's original judgement. Null for pre-V7 records.
  // 中文：Python 最初返回的分类。人工修改只改 `category`，此快照始终保留 AI 原始判断；
  //     V7 之前的历史记录为 null。
  @Column(name = "ai_category", length = 40)
  public String aiCategory;

  // EN: Complete analysis result from Python. All four columns are nullable so historical
  //     inquiries remain readable; new inquiries always receive validated values.
  // 中文：Python 返回的完整分析结果。四列均可空，保证历史咨询记录仍可读；
  //     新咨询记录始终写入经过校验的值。
  // EN: DECIMAL(5,4) is declared literally: Hibernate rejects "scale" on a floating-point field.
  // 中文：直接声明 DECIMAL(5,4)：Hibernate 不允许在浮点字段上使用 scale 属性。
  @Column(name = "confidence", columnDefinition = "DECIMAL(5,4)")
  public Double confidence;

  @Column(name = "needs_human_review")
  public Boolean needsHumanReview;

  @Column(name = "suggested_reply", columnDefinition = "TEXT")
  public String suggestedReply;

  @Column(name = "analysis_source", length = 20)
  public String analysisSource;

  // EN: Current business state. All new state fields are nullable on purpose: historical rows have
  //     no source for them, so V8 leaves them NULL instead of inventing a completed state.
  // 中文：当前业务状态。新增状态字段刻意可空：历史记录没有来源，V8 保持 NULL，不伪造完成状态。
  @Column(name = "status", length = 32)
  public String status;

  @Column(name = "priority", length = 16)
  public String priority;

  @Column(name = "category_source", length = 16)
  public String categorySource;

  @Column(name = "priority_source", length = 16)
  public String prioritySource;

  @Column(name = "analysis_status", length = 16)
  public String analysisStatus;

  // EN: Logical reference to the current analysis row, deliberately NOT a JPA association.
  //     A physical FK here would form a cycle with inquiry_analysis.inquiry_id and would make
  //     deletion of either side require the other. Resolve it through InquiryAnalysisRepository
  //     when the full analysis row is needed; no cascade can touch append-only history.
  // 中文：指向当前分析记录的逻辑引用，刻意不建 JPA 关联。物理外键会与 inquiry_analysis.inquiry_id
  //     形成循环，导致任一方删除都受另一方牵制。需要完整分析记录时通过 InquiryAnalysisRepository 查询；
  //     任何级联都不会触及只追加的历史表。
  @Column(name = "current_analysis_id")
  public Long currentAnalysisId;

  @Column(name = "handled_by", length = 100)
  public String handledBy;

  @Column(name = "handled_at")
  public LocalDateTime handledAt;

  @Column(name = "handling_note", columnDefinition = "TEXT")
  public String handlingNote;

  @UpdateTimestamp
  @Column(
      name = "updated_at",
      nullable = false,
      columnDefinition = "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
  public LocalDateTime updatedAt;

  @Column(name = "resolved_at")
  public LocalDateTime resolvedAt;

  @Column(name = "closed_at")
  public LocalDateTime closedAt;

  @Version
  @Column(name = "version", nullable = false)
  public Long version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  protected Inquiry() {}

  public Inquiry(String key, String email, String subject, String body, String category) {
    this(key, email, subject, body, category, null, null, null, null, null);
  }

  public Inquiry(
      String key,
      String email,
      String subject,
      String body,
      String category,
      Double confidence,
      Boolean needsHumanReview,
      String suggestedReply,
      String analysisSource) {
    this(
        key,
        email,
        subject,
        body,
        category,
        category,
        confidence,
        needsHumanReview,
        suggestedReply,
        analysisSource);
  }

  public Inquiry(
      String key,
      String email,
      String subject,
      String body,
      String category,
      String aiCategory,
      Double confidence,
      Boolean needsHumanReview,
      String suggestedReply,
      String analysisSource) {
    this.idempotencyKey = key;
    this.customerEmail = email;
    this.subject = subject;
    this.body = body;
    this.category = category;
    this.aiCategory = aiCategory;
    this.confidence = confidence;
    this.needsHumanReview = needsHumanReview;
    this.suggestedReply = suggestedReply;
    this.analysisSource = analysisSource;
  }
}
