package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** An order line that bought this product, so the inventory page can show where stock went. */
public record ProductOrderReferenceResponse(
    @JsonProperty("order_item_id") Long orderItemId,
    @JsonProperty("order_number") String orderNumber,
    @JsonProperty("order_status") String orderStatus,
    @JsonProperty("customer_id") Long customerId,
    Integer quantity,
    @JsonProperty("unit_price") BigDecimal unitPrice,
    @JsonProperty("order_created_at") LocalDateTime orderCreatedAt) {}
