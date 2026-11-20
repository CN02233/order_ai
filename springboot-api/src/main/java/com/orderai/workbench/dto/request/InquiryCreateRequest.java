package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InquiryCreateRequest(
    @JsonProperty("customer_email") String customerEmail, String subject, String body) {}
