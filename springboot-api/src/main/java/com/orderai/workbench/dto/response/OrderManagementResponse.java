package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderManagementResponse(
    Long id,
    @JsonProperty("order_number") String orderNumber,
    @JsonProperty("customer_id") Long customerId,
    String status,
    @JsonProperty("tracking_number") String trackingNumber,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("total_amount") BigDecimal totalAmount) {}
