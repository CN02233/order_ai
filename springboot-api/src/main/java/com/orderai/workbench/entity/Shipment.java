package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One parcel carrying (part of) an order.
 *
 * <p>The parcel, not the order, is what a carrier tracks: a large order may leave in several
 * shipments, each with its own carrier and tracking number. {@code orderNumber} is a denormalised
 * copy of the order header so the logistics list can filter without joining orders.
 */
@Entity
@Table(name = "shipments")
public class Shipment {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "order_id", nullable = false)
  public Long orderId;

  @Column(name = "order_number", nullable = false)
  public String orderNumber;

  @Column(nullable = false)
  public String carrier;

  @Column(name = "tracking_number", nullable = false, unique = true)
  public String trackingNumber;

  /** CREATED, PICKED_UP, IN_TRANSIT, DELIVERED, EXCEPTION or CANCELLED. */
  @Column(nullable = false)
  public String status;

  /** When the parcel was handed to the carrier. */
  @Column(name = "shipped_at")
  public LocalDateTime shippedAt;

  @Column(name = "estimated_delivery")
  public LocalDate estimatedDelivery;

  @Column(name = "delivered_at")
  public LocalDateTime deliveredAt;

  @Column(name = "receiver_name")
  public String receiverName;

  @Column(length = 500)
  public String note;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public LocalDateTime updatedAt;

  public Shipment() {}
}
