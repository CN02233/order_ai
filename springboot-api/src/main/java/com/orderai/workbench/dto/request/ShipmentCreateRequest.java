package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * Creates one parcel for an order.
 *
 * <p>{@code items} is optional: leaving it empty means "ship everything that is still unshipped",
 * which is the common case. Sending it allows a genuine partial shipment.
 */
public record ShipmentCreateRequest(
    @JsonProperty("order_number") String orderNumber,
    String carrier,
    @JsonProperty("tracking_number") String trackingNumber,
    @JsonProperty("estimated_delivery") LocalDate estimatedDelivery,
    @JsonProperty("receiver_name") String receiverName,
    String note,
    List<ShipmentItemRequest> items) {}
