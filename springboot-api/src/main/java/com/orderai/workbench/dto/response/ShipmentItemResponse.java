package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/** How many units of one order line travel in a parcel, with the snapshotted product identity. */
public record ShipmentItemResponse(
    Long id,
    @JsonProperty("order_item_id") Long orderItemId,
    String sku,
    String name,
    Integer quantity) {}
