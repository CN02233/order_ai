package com.orderai.workbench.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "customers")
public class Customer {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, unique = true, length = 255)
  public String email;

  @Column(nullable = false, length = 200)
  public String name;

  protected Customer() {}

  /** Creates a customer entity with its required fields. */
  public Customer(String email, String name) {
    this.email = email;
    this.name = name;
  }
}
