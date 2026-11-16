package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record InquiryManagementResponse(
    Long id,
    @JsonProperty("customer_email") String customerEmail,
    String subject,
    String body,
    String category,
    @JsonProperty("created_at") LocalDateTime createdAt) {}
