package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Compact refund row used by the management lists. */
public record RefundManagementResponse(
    Long id,
    @JsonProperty("order_number") String orderNumber,
    String reason,
    String status,
    @JsonProperty("refund_type") String refundType,
    BigDecimal amount,
    @JsonProperty("inventory_received") boolean inventoryReceived,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("reviewed_at") LocalDateTime reviewedAt) {}
