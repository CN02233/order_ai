package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * One order line.
 *
 * <p>{@code unitPrice} is the price captured when the order was created; it is intentionally not
 * joined back to the product row so later price changes never rewrite history.
 *
 * <p>{@code shippedQuantity} is the units already handed to a carrier in non-cancelled parcels, so
 * a client can show what is still available to ship without guessing.
 */
public record OrderItemResponse(
    Long id,
    @JsonProperty("product_id") Long productId,
    String sku,
    String name,
    Integer quantity,
    @JsonProperty("shipped_quantity") Integer shippedQuantity,
    @JsonProperty("unit_price") BigDecimal unitPrice,
    @JsonProperty("line_total") BigDecimal lineTotal) {}
