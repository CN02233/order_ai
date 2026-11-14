package com.orderai.workbench.repository;

import com.orderai.workbench.entity.OrderEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrderRepository extends JpaRepository<OrderEntity, Long>, JpaSpecificationExecutor<OrderEntity> {
  Optional<OrderEntity> findByOrderNumber(String orderNumber);

  List<OrderEntity> findByCustomerId(Long customerId);
}
