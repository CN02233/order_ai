package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * EN: Append-only AI analysis history. Every analysis attempt is a separate row; re-analysis
 * inserts a new version rather than overwriting the previous one. `version` is an inquiry-scoped
 * business counter, NOT a JPA optimistic-lock field.
 *
 * <p>中文：只追加的 AI 分析历史。每次分析独立成行，重新分析插入新版本，绝不覆盖旧记录。`version`
 * 是咨询内业务版本号，不是 JPA 乐观锁字段。
 */
@Entity
@Table(
    name = "inquiry_analysis",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_inquiry_analysis_version",
            columnNames = {"inquiry_id", "version"}),
    indexes = @Index(name = "idx_inquiry_analysis_inquiry", columnList = "inquiry_id"))
public class InquiryAnalysis {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  // EN: No cascade: deleting an inquiry must not silently erase its audit history.
  // 中文：不设置级联：删除咨询不得悄悄抹掉审计历史。
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "inquiry_id", nullable = false)
  public Inquiry inquiry;

  @Column(name = "version", nullable = false)
  public Integer version;

  @Column(name = "category", length = 40)
  public String category;

  @Column(name = "priority", length = 16)
  public String priority;

  @Column(name = "confidence", columnDefinition = "DECIMAL(5,4)")
  public Double confidence;

  @Column(name = "needs_human_review")
  public Boolean needsHumanReview;

  @Column(name = "analysis_reason", columnDefinition = "TEXT")
  public String analysisReason;

  @Column(name = "suggested_reply", columnDefinition = "TEXT")
  public String suggestedReply;

  @Column(name = "analysis_source", length = 20)
  public String analysisSource;

  // EN: PENDING / PROCESSING / SUCCESS / FAILED. Never derived from a human-edited business value.
  // 中文：PENDING / PROCESSING / SUCCESS / FAILED。绝不从人工修改后的业务值推导。
  @Column(name = "status", nullable = false, length = 16)
  public String status;

  @Column(name = "error_message", columnDefinition = "TEXT")
  public String errorMessage;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  @Column(name = "completed_at")
  public LocalDateTime completedAt;

  public InquiryAnalysis() {}
}
