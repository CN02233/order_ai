package com.orderai.workbench.repository;

import com.orderai.workbench.entity.Shipment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRepository extends JpaRepository<Shipment, Long>, JpaSpecificationExecutor<Shipment> {
  Optional<Shipment> findByTrackingNumber(String trackingNumber);

  boolean existsByTrackingNumber(String trackingNumber);

  List<Shipment> findAllByOrderIdOrderByIdAsc(Long orderId);

  List<Shipment> findAllByOrderNumberOrderByIdAsc(String orderNumber);

  /**
   * Locks the whole parcel set of one order.
   *
   * <p>Shipping, delivery and cancellation all change the order header as a side effect, so they
   * must serialise on the order rather than on individual parcels.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from Shipment s where s.orderId = :orderId order by s.id")
  List<Shipment> lockByOrderId(@Param("orderId") Long orderId);
}
