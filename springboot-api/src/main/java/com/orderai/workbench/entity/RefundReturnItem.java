package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One return line of a refund request.
 *
 * <p>{@code quantity} is what the customer says they are sending back; {@code acceptedQuantity} is
 * what the warehouse actually counted, and only that number goes back into stock.
 */
@Entity
@Table(
    name = "refund_return_items",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_refund_return_line",
            columnNames = {"refund_request_id", "order_item_id"}))
public class RefundReturnItem {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "refund_request_id", nullable = false)
  public Long refundRequestId;

  @Column(name = "order_item_id", nullable = false)
  public Long orderItemId;

  @Column(nullable = false)
  public Integer quantity;

  @Column(name = "accepted_quantity", nullable = false)
  public Integer acceptedQuantity = 0;

  @Column(name = "received_at")
  public LocalDateTime receivedAt;

  public RefundReturnItem() {}
}
