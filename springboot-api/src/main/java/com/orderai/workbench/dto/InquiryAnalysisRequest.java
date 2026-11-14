package com.orderai.workbench.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** JSON contract sent from the Java business API to the Python analysis service. */
public record InquiryAnalysisRequest(
    @JsonProperty("customer_email") String customerEmail,
    String subject,
    String body) {}
