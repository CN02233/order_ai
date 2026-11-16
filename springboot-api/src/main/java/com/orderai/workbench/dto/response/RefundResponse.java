package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Refund detail, always rendered together with the order it belongs to.
 *
 * <p>Approval only records a manual decision. {@code inventoryReceived} turns true once the returned
 * goods have actually been inspected, which is also the moment stock is increased.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundResponse(
    Long id,
    @JsonProperty("order_number") String orderNumber,
    String reason,
    String status,
    @JsonProperty("refund_type") String refundType,
    BigDecimal amount,
    @JsonProperty("inventory_received") boolean inventoryReceived,
    Boolean replayed,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("reviewed_at") LocalDateTime reviewedAt,
    @JsonProperty("order") RefundOrderSummary order,
    @JsonProperty("return_items") List<RefundReturnItemResponse> returnItems) {}
