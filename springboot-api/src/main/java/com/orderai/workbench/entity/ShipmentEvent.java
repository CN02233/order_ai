package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One scan on the parcel's journey: created, picked up, in transit, delivered, exception.
 *
 * <p>Append-only, written in the same transaction as the shipment status change, so the timeline and
 * the current status can never disagree.
 */
@Entity
@Table(name = "shipment_events")
public class ShipmentEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "shipment_id", nullable = false)
  public Long shipmentId;

  @Column(nullable = false)
  public String status;

  /** City or hub where the scan happened. */
  @Column(length = 200)
  public String location;

  @Column(length = 500)
  public String description;

  @Column(name = "occurred_at", nullable = false)
  public LocalDateTime occurredAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  public ShipmentEvent() {}
}
