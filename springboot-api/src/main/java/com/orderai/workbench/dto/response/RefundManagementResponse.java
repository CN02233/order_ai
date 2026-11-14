package com.orderai.workbench.dto.response;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
public record RefundManagementResponse(Long id,@JsonProperty("order_number") String orderNumber,String reason,String status,@JsonProperty("created_at") LocalDateTime createdAt) {}
