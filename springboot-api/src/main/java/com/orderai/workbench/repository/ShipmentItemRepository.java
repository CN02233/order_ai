package com.orderai.workbench.repository;

import com.orderai.workbench.entity.ShipmentItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentItemRepository extends JpaRepository<ShipmentItem, Long> {
  List<ShipmentItem> findAllByShipmentId(Long shipmentId);

  /** Quantity of one order line already on its way in parcels that were not cancelled. */
  @Query(
      "select coalesce(sum(i.quantity),0) from ShipmentItem i, Shipment s "
          + "where s.id=i.shipmentId and i.orderItemId=:orderItemId and s.status<>'CANCELLED'")
  Long sumShippedByOrderItem(@Param("orderItemId") Long orderItemId);

  /** Same total for a whole order, used to decide whether the order can be marked delivered. */
  @Query(
      "select coalesce(sum(i.quantity),0) from ShipmentItem i, Shipment s "
          + "where s.id=i.shipmentId and s.orderId=:orderId and s.status<>'CANCELLED'")
  Long sumShippedByOrder(@Param("orderId") Long orderId);
}
