package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One parcel in a list: enough to answer "where is this order" without loading its timeline. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentSummaryResponse(
    Long id,
    @JsonProperty("order_number") String orderNumber,
    String carrier,
    @JsonProperty("tracking_number") String trackingNumber,
    String status,
    @JsonProperty("shipped_at") LocalDateTime shippedAt,
    @JsonProperty("estimated_delivery") LocalDate estimatedDelivery,
    @JsonProperty("delivered_at") LocalDateTime deliveredAt) {}
