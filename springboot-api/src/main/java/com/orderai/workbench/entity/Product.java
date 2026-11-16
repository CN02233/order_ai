package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, unique = true)
  public String sku;

  public String name;
  public Integer stock;
  public BigDecimal price;

  public Product() {}
}
