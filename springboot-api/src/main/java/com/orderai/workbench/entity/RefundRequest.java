package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "refund_requests")
public class RefundRequest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "idempotency_key", unique = true)
  public String idempotencyKey;

  @Column(name = "order_number")
  public String orderNumber;

  @Column(columnDefinition = "TEXT")
  public String reason;

  @Column(nullable = false, precision = 12, scale = 2)
  public BigDecimal amount = BigDecimal.ZERO;

  @Column(name = "refund_type", nullable = false)
  public String refundType = "REFUND_ONLY";

  @Column(name = "inventory_received", nullable = false)
  public boolean inventoryReceived;

  public String status = "PENDING_MANUAL_APPROVAL";

  /** Set when a human approves or rejects the request; approval never moves money by itself. */
  @Column(name = "reviewed_at")
  public LocalDateTime reviewedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  protected RefundRequest() {}

  public RefundRequest(
      String key, String order, String reason, BigDecimal amount, String refundType) {
    idempotencyKey = key;
    orderNumber = order;
    this.reason = reason;
    this.amount = amount;
    this.refundType = refundType;
  }
}
