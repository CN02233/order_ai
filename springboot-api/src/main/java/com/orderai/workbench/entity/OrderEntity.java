package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "orders")
public class OrderEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "order_number", unique = true)
  public String orderNumber;

  @Column(name = "customer_id")
  public Long customerId;

  public String status;

  @Column(name = "tracking_number")
  public String trackingNumber;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  /**
   * Initializes this component with its required dependencies.
   */
  public OrderEntity() {}
}
