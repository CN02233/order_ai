package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OrderRequest(
    @JsonProperty("order_number") String orderNumber,
    @JsonProperty("customer_id") Long customerId,
    String status,
    @JsonProperty("tracking_number") String trackingNumber,
    List<OrderItemCreateRequest> items) {}
