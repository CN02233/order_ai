package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** The order a refund hangs off, with the amount that is still refundable right now. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundOrderSummary(
    @JsonProperty("order_number") String orderNumber,
    String status,
    @JsonProperty("customer_email") String customerEmail,
    @JsonProperty("total_amount") BigDecimal totalAmount,
    @JsonProperty("refundable_amount") BigDecimal refundableAmount) {}
