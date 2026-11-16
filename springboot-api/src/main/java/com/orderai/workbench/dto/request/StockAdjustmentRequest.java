package com.orderai.workbench.dto.request;

/** Manual stock correction. {@code delta} is signed: -3 removes three units. */
public record StockAdjustmentRequest(Integer delta, String note) {}
