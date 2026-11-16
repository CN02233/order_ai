package com.orderai.workbench.repository;

import com.orderai.workbench.entity.ShipmentEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentEventRepository extends JpaRepository<ShipmentEvent, Long> {
  List<ShipmentEvent> findAllByShipmentIdOrderByOccurredAtAscIdAsc(Long shipmentId);
}
