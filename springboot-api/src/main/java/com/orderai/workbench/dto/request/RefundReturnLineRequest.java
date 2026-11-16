package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefundReturnLineRequest(
    @JsonProperty("order_item_id") Long orderItemId,
    Integer quantity) {}
