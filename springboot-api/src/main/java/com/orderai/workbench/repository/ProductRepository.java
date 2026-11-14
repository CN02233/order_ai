package com.orderai.workbench.repository;

import com.orderai.workbench.entity.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
  Optional<Product> findBySku(String sku);
}
