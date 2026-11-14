package com.orderai.workbench.controller;

import com.orderai.workbench.service.ProductService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.ProductResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {
  private final ProductService service;

  /**
   * Initializes this component with its required dependencies.
   * @param s input used by this operation
   */
  public ProductController(ProductService s) {
    service = s;
  }

  @GetMapping("/{sku}")
  /**
   * Finds and returns the requested resource.
   * @param sku input used by this operation
   * @return the result of this operation
   */
  public ApiResponse<ProductResponse> find(@PathVariable String sku) {
    return ApiResponse.success(service.find(sku));
  }
}
