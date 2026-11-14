package com.orderai.workbench.entity;

import jakarta.persistence.*;
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

  public String status = "PENDING_MANUAL_APPROVAL";

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  /**
   * Initializes this component with its required dependencies.
   */
  protected RefundRequest() {}

  /**
   * Initializes this component with its required dependencies.
   * @param key input used by this operation
   * @param order input used by this operation
   * @param reason input used by this operation
   */
  public RefundRequest(String key, String order, String reason) {
    idempotencyKey = key;
    orderNumber = order;
    this.reason = reason;
  }
}
