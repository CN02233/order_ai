package com.orderai.workbench.dto.response;
import java.math.BigDecimal;
public record ProductManagementResponse(Long id, String sku, String name, Integer stock, BigDecimal price) {}
