package com.orderai.workbench.dto.request;

/** One requested line of a new order: a SKU and how many units. */
public record OrderItemCreateRequest(String sku, Integer quantity) {}
