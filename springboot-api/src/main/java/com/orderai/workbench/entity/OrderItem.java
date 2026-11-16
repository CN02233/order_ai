package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * One line of an order, with the price and product name frozen at the moment of purchase.
 *
 * <p>The snapshots are deliberate: editing a product later must never rewrite the history of an
 * existing order, so nothing here is joined back to the product row.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "order_id", nullable = false)
  public Long orderId;

  @Column(name = "product_id", nullable = false)
  public Long productId;

  @Column(name = "sku_snapshot", nullable = false)
  public String skuSnapshot;

  @Column(name = "product_name_snapshot", nullable = false)
  public String productNameSnapshot;

  @Column(nullable = false)
  public Integer quantity;

  @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
  public BigDecimal unitPrice;

  public OrderItem() {}
}
