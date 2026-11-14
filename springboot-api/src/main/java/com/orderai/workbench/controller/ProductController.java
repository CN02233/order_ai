package com.orderai.workbench.controller;

import com.orderai.workbench.service.ProductService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.ProductResponse;
import org.springframework.web.bind.annotation.*;
import com.orderai.workbench.repository.ProductRepository;
import com.orderai.workbench.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@RestController
@RequestMapping("/api/products")
public class ProductController {
  private final ProductService service;
  private final ProductRepository products;

  /**
   * Initializes this component with its required dependencies.
   * @param s input used by this operation
   */
  public ProductController(ProductService s, ProductRepository products) {
    service = s;
    this.products = products;
  }

  @GetMapping
  public ApiResponse<Page<Product>> list(Pageable pageable) { return ApiResponse.success(products.findAll(pageable)); }

  @PostMapping
  public ApiResponse<Product> create(@RequestBody Product input) { input.id = null; return ApiResponse.success(products.save(input)); }

  @PutMapping("/{sku}")
  public ApiResponse<Product> update(@PathVariable String sku, @RequestBody Product input) {
    Product p = products.findBySku(sku).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Product not found"));
    p.name=input.name; p.stock=input.stock; p.price=input.price; if(input.sku!=null) p.sku=input.sku;
    return ApiResponse.success(products.save(p));
  }

  @DeleteMapping("/{sku}")
  public ApiResponse<Void> delete(@PathVariable String sku) { Product p=products.findBySku(sku).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Product not found")); products.delete(p); return ApiResponse.success(null); }

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
