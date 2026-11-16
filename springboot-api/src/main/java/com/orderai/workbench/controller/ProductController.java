package com.orderai.workbench.controller;

import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.request.ProductRequest;
import com.orderai.workbench.dto.request.StockAdjustmentRequest;
import com.orderai.workbench.dto.response.ProductManagementResponse;
import com.orderai.workbench.dto.response.ProductOrderReferenceResponse;
import com.orderai.workbench.dto.response.ProductResponse;
import com.orderai.workbench.dto.response.StockMovementResponse;
import com.orderai.workbench.entity.Product;
import com.orderai.workbench.entity.StockMovement;
import com.orderai.workbench.repository.OrderItemRepository;
import com.orderai.workbench.repository.ProductRepository;
import com.orderai.workbench.repository.StockMovementRepository;
import com.orderai.workbench.service.InventoryService;
import com.orderai.workbench.service.ProductService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Product catalogue and inventory view.
 *
 * <p>Stock is never written through this controller's create/update payload beyond the opening
 * balance: every later change goes through {@link InventoryService} so it is locked, checked and
 * recorded in the ledger.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {
  private final ProductService service;
  private final ProductRepository products;
  private final OrderItemRepository orderItems;
  private final StockMovementRepository stockMovements;
  private final InventoryService inventory;

  public ProductController(ProductService service, ProductRepository products, OrderItemRepository orderItems,
      StockMovementRepository stockMovements, InventoryService inventory) {
    this.service = service;
    this.products = products;
    this.orderItems = orderItems;
    this.stockMovements = stockMovements;
    this.inventory = inventory;
  }

  @GetMapping
  public ApiResponse<Page<ProductManagementResponse>> list(
      @RequestParam(required = false) String sku,
      @RequestParam(required = false) String name,
      @RequestParam(name = "min_stock", required = false) Integer minStock,
      @RequestParam(name = "max_stock", required = false) Integer maxStock,
      @RequestParam(name = "min_price", required = false) BigDecimal minPrice,
      @RequestParam(name = "max_price", required = false) BigDecimal maxPrice,
      @PageableDefault(size = 10, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
    requireSaneRanges(minStock, maxStock, minPrice, maxPrice);

    Specification<Product> spec = (root, query, cb) -> cb.conjunction();
    if (sku != null && !sku.isBlank()) {
      spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("sku")),
          "%" + sku.toLowerCase() + "%"));
    }
    if (name != null && !name.isBlank()) {
      spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")),
          "%" + name.toLowerCase() + "%"));
    }
    if (minStock != null) {
      spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("stock"), minStock));
    }
    if (maxStock != null) {
      spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("stock"), maxStock));
    }
    if (minPrice != null) {
      spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), minPrice));
    }
    if (maxPrice != null) {
      spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), maxPrice));
    }

    Page<Product> page = products.findAll(spec, pageable);
    Map<Long, Long> orderCounts = orderCounts(page.getContent());
    return ApiResponse.success(page.map(
        product -> toView(product, orderCounts.getOrDefault(product.id, 0L))));
  }

  /** The stock in the request becomes the opening balance and is recorded as an ADJUSTMENT movement. */
  @PostMapping
  public ApiResponse<ProductManagementResponse> create(@RequestBody ProductRequest input) {
    requireCatalogueFields(input);
    String sku = input.sku().trim();
    // Without this the unique constraint surfaces as a 500 instead of a usable conflict.
    if (products.findBySku(sku).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Product SKU already exists");
    }
    Product product = new Product();
    product.sku = sku;
    product.name = input.name().trim();
    product.stock = 0;
    product.price = input.price();
    product = products.saveAndFlush(product);
    if (input.stock() > 0) {
      inventory.adjust(product.sku, input.stock(), "Opening balance");
    }
    // A brand-new SKU cannot be referenced by an order yet.
    return ApiResponse.success(toView(products.findById(product.id).orElseThrow(), 0L));
  }

  /**
   * Catalogue edit only. Stock moves through {@code POST /{sku}/stock-adjustments} so an accidental
   * inline edit cannot silently overwrite the physical stock count.
   */
  @PutMapping("/{sku}")
  public ApiResponse<ProductManagementResponse> update(@PathVariable String sku, @RequestBody ProductRequest input) {
    Product product = products.findBySku(sku)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    if (input.name() == null || input.name().isBlank() || input.price() == null || input.price().signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and non-negative price are required");
    }
    if (input.stock() != null && !input.stock().equals(product.stock)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Stock cannot be edited here; use POST /api/products/" + product.sku + "/stock-adjustments");
    }
    if (input.sku() != null && !input.sku().isBlank() && !input.sku().trim().equals(product.sku)) {
      if (orderItems.existsByProductId(product.id)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "A SKU referenced by orders cannot be renamed");
      }
      product.sku = input.sku().trim();
    }
    product.name = input.name().trim();
    product.price = input.price();
    Product saved = products.save(product);
    return ApiResponse.success(toView(saved, orderItems.countByProductId(saved.id)));
  }

  private void requireCatalogueFields(ProductRequest p) {
    boolean missingSku = p.sku() == null || p.sku().isBlank();
    boolean missingName = p.name() == null || p.name().isBlank();
    boolean invalidStock = p.stock() == null || p.stock() < 0;
    boolean invalidPrice = p.price() == null || p.price().compareTo(BigDecimal.ZERO) < 0;
    if (missingSku || missingName || invalidStock || invalidPrice) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "sku, name, non-negative stock and price are required");
    }
  }

  /** A range filter is only meaningful when it is not inverted and not negative. */
  private static void requireSaneRanges(Integer minStock, Integer maxStock, BigDecimal minPrice,
      BigDecimal maxPrice) {
    boolean negativeStock = (minStock != null && minStock < 0) || (maxStock != null && maxStock < 0);
    boolean invertedStock = minStock != null && maxStock != null && minStock > maxStock;
    boolean negativePrice = (minPrice != null && minPrice.signum() < 0)
        || (maxPrice != null && maxPrice.signum() < 0);
    boolean invertedPrice = minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0;
    if (negativeStock || invertedStock || negativePrice || invertedPrice) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid stock or price range");
    }
  }

  @DeleteMapping("/{sku}")
  public ApiResponse<Void> delete(@PathVariable String sku) {
    Product p = products.findBySku(sku)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    if (orderItems.existsByProductId(p.id)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Products referenced by orders cannot be deleted");
    }
    // The ledger is append-only: a product with stock history must stay so its movements stay explainable.
    if (stockMovements.existsByProductId(p.id)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Products with stock movements cannot be deleted; keep them and adjust the stock instead");
    }
    products.delete(p);
    return ApiResponse.success(null);
  }

  @GetMapping("/{sku}")
  public ApiResponse<ProductResponse> find(@PathVariable String sku) {
    return ApiResponse.success(service.find(sku));
  }

  /** Orders that bought this product, so an inventory manager can see where the stock went. */
  @GetMapping("/{sku}/orders")
  public ApiResponse<List<ProductOrderReferenceResponse>> orders(
      @PathVariable String sku, @RequestParam(name = "limit", defaultValue = "20") int limit) {
    return ApiResponse.success(service.orders(sku, limit));
  }

  /** Append-only ledger of every stock change for this product. */
  @GetMapping("/{sku}/stock-movements")
  public ApiResponse<List<StockMovementResponse>> movements(
      @PathVariable String sku, @RequestParam(name = "limit", defaultValue = "50") int limit) {
    return ApiResponse.success(service.movements(sku, limit));
  }

  @PostMapping("/{sku}/stock-adjustments")
  public ApiResponse<StockMovementResponse> adjust(@PathVariable String sku,
      @RequestBody StockAdjustmentRequest request) {
    if (request == null || request.delta() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delta is required");
    }
    StockMovement movement = inventory.adjust(sku, request.delta(), request.note());
    return ApiResponse.success(new StockMovementResponse(movement.id, movement.skuSnapshot, movement.delta,
        movement.balanceAfter, movement.movementType, movement.reference, movement.note, movement.createdAt));
  }

  private ProductManagementResponse toView(Product p, long orderCount) {
    return new ProductManagementResponse(p.id, p.sku, p.name, p.stock, p.price, orderCount);
  }

  /** Reference counts for a page of products, resolved with one grouped query. */
  private Map<Long, Long> orderCounts(List<Product> page) {
    if (page.isEmpty()) {
      return Map.of();
    }
    Map<Long, Long> counts = new HashMap<>();
    for (Object[] row : orderItems.countByProductIdIn(page.stream().map(p -> p.id).toList())) {
      counts.put((Long) row[0], (Long) row[1]);
    }
    return counts;
  }
}
