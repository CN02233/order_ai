package com.orderai.workbench.service.impl;

import com.orderai.workbench.entity.Product;
import com.orderai.workbench.dto.response.ProductResponse;

import com.orderai.workbench.repository.ProductRepository;
import com.orderai.workbench.service.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductServiceImpl implements ProductService {
  private final ProductRepository repo;

  /**
   * Initializes this component with its required dependencies.
   * @param r input used by this operation
   */
  public ProductServiceImpl(ProductRepository r) {
    repo = r;
  }

  /**
   * Finds and returns the requested resource.
   * @param sku input used by this operation
   * @return the result of this operation
   */
  public ProductResponse find(String sku) {
    Product p =
        repo.findBySku(sku)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    return new ProductResponse(p.sku, p.name, p.stock, p.price);
  }
}
