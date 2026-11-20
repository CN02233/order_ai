package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full parcel view: carrier, status dates, which order lines are inside and the tracking timeline.
 *
 * <p>An order can have several parcels, so the order detail exposes {@code shipments} rather than a
 * single tracking number; {@code orders.tracking_number} is kept in sync with the newest parcel
 * only so older screens keep working.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentResponse(
    Long id,
    @JsonProperty("order_id") Long orderId,
    @JsonProperty("order_number") String orderNumber,
    String carrier,
    @JsonProperty("tracking_number") String trackingNumber,
    String status,
    @JsonProperty("shipped_at") LocalDateTime shippedAt,
    @JsonProperty("estimated_delivery") LocalDate estimatedDelivery,
    @JsonProperty("delivered_at") LocalDateTime deliveredAt,
    @JsonProperty("receiver_name") String receiverName,
    String note,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("updated_at") LocalDateTime updatedAt,
    List<ShipmentItemResponse> items,
    List<ShipmentEventResponse> events) {}
