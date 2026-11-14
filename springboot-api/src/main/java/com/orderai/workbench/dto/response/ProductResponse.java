package com.orderai.workbench.dto.response;

import java.math.BigDecimal;

/** Represents product stock and price details returned by the workbench API. */
public record ProductResponse(String sku, String name, Integer stock, BigDecimal price) {}
