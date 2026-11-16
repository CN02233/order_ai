package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One line of a parcel: how many units of an order line are shipped. */
public record ShipmentItemRequest(
    @JsonProperty("order_item_id") Long orderItemId,
    Integer quantity) {}
