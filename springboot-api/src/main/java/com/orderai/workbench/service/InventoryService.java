package com.orderai.workbench.service;

import com.orderai.workbench.entity.Product;
import com.orderai.workbench.entity.StockMovement;
import java.util.List;

/**
 * The single entry point for every stock change.
 *
 * <p>Controllers must never write {@code products.stock} directly: reservations, cancellations,
 * returned goods and manual corrections all go through here so that the stock value, the
 * non-negative guarantee and the movement ledger stay consistent inside one transaction.
 */
public interface InventoryService {
  /**
   * Locks the product by SKU, checks availability, decrements stock and records a SALE movement.
   */
  Product reserve(String sku, int quantity, String orderNumber);

  /** Returns reserved stock to the catalogue when an order is cancelled. */
  void release(Long productId, int quantity, String orderNumber);

  /** Adds inspected returned goods back to sellable stock. */
  void receiveReturn(Long productId, int quantity, String refundReference);

  /** Manual correction (damage, recount, opening balance) with a mandatory reason. */
  StockMovement adjust(String sku, int delta, String note);

  List<StockMovement> recentMovements(Long productId, int limit);
}
