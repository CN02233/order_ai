package com.orderai.workbench.service.impl;

import com.orderai.workbench.dto.response.ProductOrderReferenceResponse;
import com.orderai.workbench.dto.response.ProductResponse;
import com.orderai.workbench.dto.response.StockMovementResponse;
import com.orderai.workbench.entity.OrderEntity;
import com.orderai.workbench.entity.OrderItem;
import com.orderai.workbench.entity.Product;
import com.orderai.workbench.repository.OrderItemRepository;
import com.orderai.workbench.repository.OrderRepository;
import com.orderai.workbench.repository.ProductRepository;
import com.orderai.workbench.service.InventoryService;
import com.orderai.workbench.service.ProductService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductServiceImpl implements ProductService {
  private final ProductRepository repo;
  private final OrderItemRepository orderItems;
  private final OrderRepository orders;
  private final InventoryService inventory;

  public ProductServiceImpl(ProductRepository repo, OrderItemRepository orderItems, OrderRepository orders,
      InventoryService inventory) {
    this.repo = repo;
    this.orderItems = orderItems;
    this.orders = orders;
    this.inventory = inventory;
  }

  @Override
  @Transactional(readOnly = true)
  public ProductResponse find(String sku) {
    Product p = repo.findBySku(sku)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    return new ProductResponse(p.id, p.sku, p.name, p.stock, p.price);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProductOrderReferenceResponse> orders(String sku, int limit) {
    Product product = repo.findBySku(sku)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    List<OrderItem> lines = orderItems.findByProductIdOrderByIdDesc(product.id, PageRequest.of(0, clamp(limit)));
    Map<Long, OrderEntity> byId = orders.findAllById(lines.stream().map(line -> line.orderId).toList()).stream()
        .collect(Collectors.toMap(order -> order.id, Function.identity()));
    return lines.stream()
        .map(line -> {
          OrderEntity order = byId.get(line.orderId);
          return new ProductOrderReferenceResponse(
              line.id,
              order == null ? null : order.orderNumber,
              order == null ? null : order.status,
              order == null ? null : order.customerId,
              line.quantity,
              line.unitPrice,
              order == null ? null : order.createdAt);
        })
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<StockMovementResponse> movements(String sku, int limit) {
    Product product = repo.findBySku(sku)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    return inventory.recentMovements(product.id, limit).stream()
        .map(m -> new StockMovementResponse(m.id, m.skuSnapshot, m.delta, m.balanceAfter, m.movementType,
            m.reference, m.note, m.createdAt))
        .toList();
  }

  private int clamp(int limit) {
    return Math.min(Math.max(limit, 1), 100);
  }
}
