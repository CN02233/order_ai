package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Inventory row.
 *
 * <p>{@code orderCount} is how many order lines reference the product: anything above zero means it
 * cannot be deleted, so the list can disable the button before the user tries.
 */
public record ProductManagementResponse(
    Long id,
    String sku,
    String name,
    Integer stock,
    BigDecimal price,
    @JsonProperty("order_count") long orderCount) {}
