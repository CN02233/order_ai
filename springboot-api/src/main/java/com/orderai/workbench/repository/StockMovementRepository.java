package com.orderai.workbench.repository;

import com.orderai.workbench.entity.StockMovement;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
  boolean existsByProductId(Long productId);

  List<StockMovement> findByProductIdOrderByIdDesc(Long productId, Pageable pageable);
}
