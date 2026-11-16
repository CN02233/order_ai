package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One requested return line.
 *
 * <p>{@code acceptedQuantity} stays zero until the goods are physically inspected; only the accepted
 * quantity is added back to stock.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundReturnItemResponse(
    @JsonProperty("order_item_id") Long orderItemId,
    String sku,
    String name,
    Integer quantity,
    @JsonProperty("accepted_quantity") Integer acceptedQuantity,
    @JsonProperty("unit_price") BigDecimal unitPrice,
    @JsonProperty("line_total") BigDecimal lineTotal,
    @JsonProperty("received_at") LocalDateTime receivedAt) {}
