package com.orderai.workbench.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "inquiries")
public class Inquiry {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "idempotency_key", unique = true)
  public String idempotencyKey;

  @Column(name = "customer_email")
  public String customerEmail;

  public String subject;

  @Column(columnDefinition = "TEXT")
  public String body;

  public String category;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public LocalDateTime createdAt;

  protected Inquiry() {}

  public Inquiry(String key, String email, String subject, String body, String category) {
    this.idempotencyKey = key;
    this.customerEmail = email;
    this.subject = subject;
    this.body = body;
    this.category = category;
  }
}
