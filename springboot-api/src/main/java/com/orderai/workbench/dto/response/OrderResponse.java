package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Represents order details returned by the workbench API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
    @JsonProperty("order_number") String orderNumber,
    String status,
    @JsonProperty("tracking_number") String trackingNumber,
    @JsonProperty("customer_email") String customerEmail) {}
