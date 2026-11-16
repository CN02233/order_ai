package com.orderai.workbench.entity;

import jakarta.persistence.*;

/**
 * How much of one order line travels in one parcel.
 *
 * <p>Storing the split explicitly is what makes partial shipping honest: the sum of the non-cancelled
 * rows for an order line can never exceed the quantity that was ordered.
 */
@Entity
@Table(name = "shipment_items",
    uniqueConstraints = @UniqueConstraint(name = "uq_shipment_items_line",
        columnNames = {"shipment_id", "order_item_id"}))
public class ShipmentItem {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "shipment_id", nullable = false)
  public Long shipmentId;

  @Column(name = "order_item_id", nullable = false)
  public Long orderItemId;

  @Column(nullable = false)
  public Integer quantity;

  public ShipmentItem() {}
}
