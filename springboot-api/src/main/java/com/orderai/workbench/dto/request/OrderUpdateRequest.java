package com.orderai.workbench.dto.request;
import com.fasterxml.jackson.annotation.JsonProperty;
public record OrderUpdateRequest(String status, @JsonProperty("tracking_number") String trackingNumber) {}
