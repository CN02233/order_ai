package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/** One row of the stock ledger: what changed, why, and the balance it produced. */
public record StockMovementResponse(
    Long id,
    String sku,
    Integer delta,
    @JsonProperty("balance_after") Integer balanceAfter,
    @JsonProperty("movement_type") String movementType,
    String reference,
    String note,
    @JsonProperty("created_at") LocalDateTime createdAt) {}
