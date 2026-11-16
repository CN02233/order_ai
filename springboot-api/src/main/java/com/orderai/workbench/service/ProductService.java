package com.orderai.workbench.service;

import com.orderai.workbench.dto.response.ProductOrderReferenceResponse;
import com.orderai.workbench.dto.response.ProductResponse;
import com.orderai.workbench.dto.response.StockMovementResponse;
import java.util.List;

public interface ProductService {
  ProductResponse find(String sku);

  /** Recent order lines that bought this product, newest first. */
  List<ProductOrderReferenceResponse> orders(String sku, int limit);

  /** Recent stock ledger entries for this product, newest first. */
  List<StockMovementResponse> movements(String sku, int limit);
}
