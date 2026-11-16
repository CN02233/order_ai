package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RefundReceiveLineRequest(
    @JsonProperty("order_item_id") Long orderItemId,
    @JsonProperty("accepted_quantity") Integer acceptedQuantity) {}
