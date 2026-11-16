package com.orderai.workbench.service.impl;

import com.orderai.workbench.entity.Product;
import com.orderai.workbench.entity.StockMovement;
import com.orderai.workbench.repository.ProductRepository;
import com.orderai.workbench.repository.StockMovementRepository;
import com.orderai.workbench.service.InventoryService;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryServiceImpl implements InventoryService {
  public static final String SALE = "SALE";
  public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
  public static final String RETURN_RECEIVED = "RETURN_RECEIVED";
  public static final String ADJUSTMENT = "ADJUSTMENT";

  private final ProductRepository products;
  private final StockMovementRepository movements;

  public InventoryServiceImpl(ProductRepository products, StockMovementRepository movements) {
    this.products = products;
    this.movements = movements;
  }

  @Override
  @Transactional
  public Product reserve(String sku, int quantity, String orderNumber) {
    if (quantity <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reserved quantity must be positive");
    }
    Product product = products.findBySkuForUpdate(sku)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + sku));
    if (product.stock < quantity) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient stock for SKU " + product.sku);
    }
    apply(product, -quantity, SALE, orderNumber, "Order reservation");
    return product;
  }

  @Override
  @Transactional
  public void release(Long productId, int quantity, String orderNumber) {
    if (quantity <= 0) {
      return;
    }
    Product product = lockById(productId);
    apply(product, quantity, ORDER_CANCELLED, orderNumber, "Order cancelled");
  }

  @Override
  @Transactional
  public void receiveReturn(Long productId, int quantity, String refundReference) {
    if (quantity <= 0) {
      return;
    }
    Product product = lockById(productId);
    apply(product, quantity, RETURN_RECEIVED, refundReference, "Returned goods accepted");
  }

  @Override
  @Transactional
  public StockMovement adjust(String sku, int delta, String note) {
    if (delta == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adjustment delta must not be zero");
    }
    if (note == null || note.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required for manual stock adjustments");
    }
    Product product = products.findBySkuForUpdate(sku)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + sku));
    return apply(product, delta, ADJUSTMENT, null, note.trim());
  }

  @Override
  @Transactional(readOnly = true)
  public List<StockMovement> recentMovements(Long productId, int limit) {
    int size = Math.min(Math.max(limit, 1), 200);
    return movements.findByProductIdOrderByIdDesc(productId, PageRequest.of(0, size));
  }

  private Product lockById(Long productId) {
    return products.findByIdForUpdate(productId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
            "Product no longer exists for this order line"));
  }

  /** Applies the delta, refuses negative stock and records the ledger row. */
  private StockMovement apply(Product product, int delta, String type, String reference, String note) {
    int balance = product.stock + delta;
    if (balance < 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Stock for SKU " + product.sku + " cannot become negative");
    }
    product.stock = balance;
    products.save(product);

    StockMovement movement = new StockMovement();
    movement.productId = product.id;
    movement.skuSnapshot = product.sku;
    movement.delta = delta;
    movement.balanceAfter = balance;
    movement.movementType = type;
    movement.reference = reference;
    movement.note = note;
    return movements.save(movement);
  }
}
