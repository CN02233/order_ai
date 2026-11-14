package com.orderai.workbench.service;

import com.orderai.workbench.dto.response.ProductResponse;

public interface ProductService {
  ProductResponse find(String sku);
}
