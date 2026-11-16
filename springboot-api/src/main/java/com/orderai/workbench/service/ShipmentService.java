package com.orderai.workbench.service;

import com.orderai.workbench.dto.request.ShipmentCreateRequest;
import com.orderai.workbench.dto.request.ShipmentEventRequest;
import com.orderai.workbench.dto.request.ShipmentUpdateRequest;
import com.orderai.workbench.dto.response.ShipmentResponse;
import com.orderai.workbench.dto.response.ShipmentSummaryResponse;
import com.orderai.workbench.entity.Shipment;
import java.util.List;

/**
 * Logistics: the parcels that carry an order.
 *
 * <p>Shipping is a fulfilment step, not a stock step — stock was already reserved when the order was
 * created. Every mutation here keeps the order header in sync (status and its display tracking
 * number), because a customer looking at an order expects that summary to be right.
 */
public interface ShipmentService {
  ShipmentResponse find(Long id);

  List<ShipmentSummaryResponse> findByOrder(String orderNumber);

  ShipmentResponse create(ShipmentCreateRequest request);

  ShipmentResponse update(Long id, ShipmentUpdateRequest request);

  /** Appends one scan; a status on the scan moves the parcel through the same transition rules. */
  ShipmentResponse addEvent(Long id, ShipmentEventRequest request);

  void delete(Long id);

  ShipmentSummaryResponse toSummary(Shipment shipment);
}
