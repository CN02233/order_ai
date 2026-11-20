package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * EN: Append-only manual handling history. old_value/new_value hold JSON documents as TEXT, which
 * is portable across the supported MySQL versions and H2.
 *
 * <p>中文：只追加的人工处理历史。old_value/new_value 用 TEXT 保存 JSON 文档，兼容目标 MySQL 版本与 H2。
 */
@Entity
@Table(
    name = "inquiry_handling_record",
    indexes =
        @Index(
            name = "idx_inquiry_handling_inquiry_created",
            columnList = "inquiry_id, created_at"))
public class InquiryHandlingRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "inquiry_id", nullable = false)
  public Inquiry inquiry;

  @Column(name = "operator_id", length = 100)
  public String operatorId;

  @Column(name = "action_type", nullable = false, length = 40)
  public String actionType;

  @Column(name = "old_value", columnDefinition = "TEXT")
  public String oldValue;

  @Column(name = "new_value", columnDefinition = "TEXT")
  public String newValue;

  @Column(name = "note", columnDefinition = "TEXT")
  public String note;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  public InquiryHandlingRecord() {}
}
