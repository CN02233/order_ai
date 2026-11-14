package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/** Represents order details returned by the workbench API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
    Long id,
    @JsonProperty("order_number") String orderNumber,
    String status,
    @JsonProperty("tracking_number") String trackingNumber,
    @JsonProperty("customer_email") String customerEmail,
    @JsonProperty("created_at") LocalDateTime createdAt) {}
