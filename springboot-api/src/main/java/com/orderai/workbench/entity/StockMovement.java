package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Append-only record of one stock change.
 *
 * <p>Rows are only written by {@code InventoryServiceImpl}, in the same transaction as the stock
 * update it describes, so the ledger always explains why a product's stock differs from its
 * opening balance.
 */
@Entity
@Table(name = "stock_movements")
public class StockMovement {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "product_id", nullable = false)
  public Long productId;

  @Column(name = "sku_snapshot", nullable = false)
  public String skuSnapshot;

  /** Signed change applied to the product stock. */
  @Column(nullable = false)
  public Integer delta;

  @Column(name = "balance_after", nullable = false)
  public Integer balanceAfter;

  /** SALE, ORDER_CANCELLED, RETURN_RECEIVED or ADJUSTMENT. */
  @Column(name = "movement_type", nullable = false)
  public String movementType;

  /** Order number or refund reference this movement belongs to. */
  public String reference;

  public String note;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  public StockMovement() {}
}
