package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record RefundCreateRequest(
    @JsonProperty("order_number") String orderNumber,
    String reason,
    BigDecimal amount,
    @JsonProperty("refund_type") String refundType,
    @JsonProperty("return_items") List<RefundReturnLineRequest> returnItems) {}
