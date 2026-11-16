package com.orderai.workbench.repository;

import com.orderai.workbench.entity.OrderEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<OrderEntity, Long>, JpaSpecificationExecutor<OrderEntity> {
  Optional<OrderEntity> findByOrderNumber(String orderNumber);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from OrderEntity o where o.orderNumber = :number")
  Optional<OrderEntity> findByOrderNumberForUpdate(@Param("number") String number);

  /** Locks the order header so parcel changes and order changes serialise on the same row. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from OrderEntity o where o.id = :id")
  Optional<OrderEntity> lockById(@Param("id") Long id);

  List<OrderEntity> findByCustomerId(Long customerId);
}
