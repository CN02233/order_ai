package com.orderai.workbench.controller;

import com.orderai.workbench.service.ProductService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.ProductResponse;
import org.springframework.web.bind.annotation.*;
import com.orderai.workbench.repository.ProductRepository;
import com.orderai.workbench.entity.Product;
import com.orderai.workbench.dto.response.ProductManagementResponse;
import com.orderai.workbench.dto.request.ProductRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.jpa.domain.Specification;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
  public ApiResponse<Page<ProductManagementResponse>> list(@RequestParam(required=false) String sku,@RequestParam(required=false) String name,@RequestParam(required=false) Integer min_stock,@RequestParam(required=false) Integer max_stock,@RequestParam(required=false) BigDecimal min_price,@RequestParam(required=false) BigDecimal max_price,@PageableDefault(size=10,sort="id",direction=Sort.Direction.DESC) Pageable pageable) {
    if(min_stock!=null&&min_stock<0||max_stock!=null&&max_stock<0||min_stock!=null&&max_stock!=null&&min_stock>max_stock||min_price!=null&&min_price.signum()<0||max_price!=null&&max_price.signum()<0||min_price!=null&&max_price!=null&&min_price.compareTo(max_price)>0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid stock or price range");
    Specification<Product> spec=(r,q,cb)->cb.conjunction();
    if(sku!=null&&!sku.isBlank())spec=spec.and((r,q,cb)->cb.like(cb.lower(r.get("sku")),"%"+sku.toLowerCase()+"%"));
    if(name!=null&&!name.isBlank())spec=spec.and((r,q,cb)->cb.like(cb.lower(r.get("name")),"%"+name.toLowerCase()+"%"));
    if(min_stock!=null)spec=spec.and((r,q,cb)->cb.greaterThanOrEqualTo(r.get("stock"),min_stock));
    if(max_stock!=null)spec=spec.and((r,q,cb)->cb.lessThanOrEqualTo(r.get("stock"),max_stock));
    if(min_price!=null)spec=spec.and((r,q,cb)->cb.greaterThanOrEqualTo(r.get("price"),min_price));
    if(max_price!=null)spec=spec.and((r,q,cb)->cb.lessThanOrEqualTo(r.get("price"),max_price));
    return ApiResponse.success(products.findAll(spec,pageable).map(p->new ProductManagementResponse(p.id,p.sku,p.name,p.stock,p.price)));
  }

  @PostMapping
  public ApiResponse<ProductManagementResponse> create(@RequestBody ProductRequest input) { validate(input);Product p=new Product();p.sku=input.sku();p.name=input.name();p.stock=input.stock();p.price=input.price();p=products.save(p);return ApiResponse.success(new ProductManagementResponse(p.id,p.sku,p.name,p.stock,p.price)); }

  @PutMapping("/{sku}")
  public ApiResponse<ProductManagementResponse> update(@PathVariable String sku, @RequestBody ProductRequest input) {
    Product p = products.findBySku(sku).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Product not found"));
    validate(input);p.name=input.name(); p.stock=input.stock(); p.price=input.price(); if(input.sku()!=null) p.sku=input.sku();
    p=products.save(p);return ApiResponse.success(new ProductManagementResponse(p.id,p.sku,p.name,p.stock,p.price));
  }

  private void validate(ProductRequest p){if(p.sku()==null||p.sku().isBlank()||p.name()==null||p.name().isBlank()||p.stock()==null||p.stock()<0||p.price()==null||p.price().compareTo(BigDecimal.ZERO)<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"sku, name, non-negative stock and price are required");}

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
