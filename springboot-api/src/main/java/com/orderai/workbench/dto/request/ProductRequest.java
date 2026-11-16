package com.orderai.workbench.dto.request;

import java.math.BigDecimal;

public record ProductRequest(String sku, String name, Integer stock, BigDecimal price) {}
