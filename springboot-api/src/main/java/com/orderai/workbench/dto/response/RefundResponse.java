package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/** Represents a refund request and its manual approval state. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundResponse(
    Long id,
    @JsonProperty("order_number") String orderNumber,
    String reason,
    String status,
    Boolean replayed,
    @JsonProperty("created_at") LocalDateTime createdAt) {
  public RefundResponse(Long id,String orderNumber,String reason,String status,Boolean replayed) { this(id,orderNumber,reason,status,replayed,null); }
}
