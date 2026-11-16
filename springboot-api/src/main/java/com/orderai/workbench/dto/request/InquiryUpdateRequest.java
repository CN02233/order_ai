package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InquiryUpdateRequest(
    @JsonProperty("customer_email") String customerEmail,
    String subject,
    String body,
    String category) {}
