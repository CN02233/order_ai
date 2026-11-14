package com.orderai.workbench.entity;

import jakarta.persistence.*;

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

  /**
   * Initializes this component with its required dependencies.
   */
  protected OrderEntity() {}
}
