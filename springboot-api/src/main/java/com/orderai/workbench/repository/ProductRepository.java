package com.orderai.workbench.repository;

import com.orderai.workbench.entity.Product;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
  Optional<Product> findBySku(String sku);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Product p where p.sku = :sku")
  Optional<Product> findBySkuForUpdate(@Param("sku") String sku);

  /** Used when stock is returned by product id, so renaming a SKU never breaks a return or a cancellation. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Product p where p.id = :id")
  Optional<Product> findByIdForUpdate(@Param("id") Long id);
}
