package com.orderai.workbench.dto.request;
import com.fasterxml.jackson.annotation.JsonProperty;
public record RefundCreateRequest(@JsonProperty("order_number") String orderNumber,String reason) {}
