package com.orderai.workbench.repository;

import com.orderai.workbench.entity.OrderEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
  Optional<OrderEntity> findByOrderNumber(String orderNumber);

  List<OrderEntity> findByCustomerId(Long customerId);
}
