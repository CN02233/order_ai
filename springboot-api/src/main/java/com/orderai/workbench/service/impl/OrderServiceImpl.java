package com.orderai.workbench.service.impl;

import com.orderai.workbench.dto.request.OrderItemCreateRequest;
import com.orderai.workbench.dto.request.OrderRequest;
import com.orderai.workbench.dto.request.OrderUpdateRequest;
import com.orderai.workbench.dto.response.OrderManagementResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.entity.OrderEntity;
import com.orderai.workbench.entity.OrderItem;
import com.orderai.workbench.entity.Product;
import com.orderai.workbench.entity.RefundRequest;
import com.orderai.workbench.entity.Shipment;
import com.orderai.workbench.repository.CustomerRepository;
import com.orderai.workbench.repository.OrderItemRepository;
import com.orderai.workbench.repository.OrderRepository;
import com.orderai.workbench.repository.RefundRequestRepository;
import com.orderai.workbench.repository.RefundReturnItemRepository;
import com.orderai.workbench.repository.ShipmentEventRepository;
import com.orderai.workbench.repository.ShipmentItemRepository;
import com.orderai.workbench.repository.ShipmentRepository;
import com.orderai.workbench.service.InventoryService;
import com.orderai.workbench.service.OrderService;
import com.orderai.workbench.service.support.OrderViewAssembler;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Order lifecycle.
 *
 * <p>Creating an order reserves stock, cancelling it releases the reservation, and both happen in
 * the same transaction as the order write through {@link InventoryService}. Stock is never touched
 * by a controller directly.
 */
@Service
public class OrderServiceImpl implements OrderService {
  /** Allowed forward transitions only: no jumping straight to DELIVERED, no reopening. */
  private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
      "PROCESSING", Set.of("PROCESSING", "SHIPPED", "CANCELLED"),
      "SHIPPED", Set.of("SHIPPED", "DELIVERED"),
      "DELIVERED", Set.of("DELIVERED"),
      "CANCELLED", Set.of("CANCELLED"));

  private final OrderRepository orders;
  private final OrderItemRepository items;
  private final CustomerRepository customers;
  private final RefundRequestRepository refunds;
  private final RefundReturnItemRepository returnItems;
  private final ShipmentRepository shipments;
  private final ShipmentItemRepository shipmentItems;
  private final ShipmentEventRepository shipmentEvents;
  private final InventoryService inventory;
  private final OrderViewAssembler views;

  public OrderServiceImpl(OrderRepository orders, OrderItemRepository items, CustomerRepository customers,
      RefundRequestRepository refunds, RefundReturnItemRepository returnItems,
      ShipmentRepository shipments, ShipmentItemRepository shipmentItems,
      ShipmentEventRepository shipmentEvents, InventoryService inventory, OrderViewAssembler views) {
    this.orders = orders;
    this.items = items;
    this.customers = customers;
    this.refunds = refunds;
    this.returnItems = returnItems;
    this.shipments = shipments;
    this.shipmentItems = shipmentItems;
    this.shipmentEvents = shipmentEvents;
    this.inventory = inventory;
    this.views = views;
  }

  @Override
  @Transactional(readOnly = true)
  public OrderResponse find(String number) {
    OrderEntity order = orders.findByOrderNumber(number)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    return views.toDetail(order);
  }

  @Override
  @Transactional
  public OrderManagementResponse create(OrderRequest request) {
    if (request.orderNumber() == null || request.orderNumber().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order_number is required");
    }
    if (request.customerId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customer_id is required");
    }
    if (request.items() == null || request.items().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items must contain at least one order line");
    }
    String orderNumber = request.orderNumber().trim();
    if (orders.findByOrderNumber(orderNumber).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Order number already exists");
    }
    if (customers.findById(request.customerId()).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
    }

    // Merge duplicate SKUs and lock them in a stable order so concurrent checkouts cannot deadlock.
    Map<String, Integer> quantities = new TreeMap<>();
    for (OrderItemCreateRequest line : request.items()) {
      if (line == null || line.sku() == null || line.sku().isBlank()
          || line.quantity() == null || line.quantity() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Each item needs a non-empty sku and a positive quantity "
                + "(customer_id is the customer's numeric id, not email)");
      }
      quantities.merge(line.sku().trim(), line.quantity(), Integer::sum);
    }

    OrderEntity order = new OrderEntity();
    order.orderNumber = orderNumber;
    order.customerId = request.customerId();
    order.status = "PROCESSING";
    order.trackingNumber = request.trackingNumber();
    order.stockReserved = true;

    BigDecimal total = BigDecimal.ZERO;
    List<OrderItem> newItems = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
      // reserve() locks the row, refuses negative stock and writes the SALE movement.
      Product product = inventory.reserve(entry.getKey(), entry.getValue(), orderNumber);
      OrderItem item = new OrderItem();
      item.productId = product.id;
      item.skuSnapshot = product.sku;
      item.productNameSnapshot = product.name;
      item.quantity = entry.getValue();
      // Price snapshot: later product price changes must not rewrite this order's amount.
      item.unitPrice = product.price;
      newItems.add(item);
      total = total.add(product.price.multiply(BigDecimal.valueOf(entry.getValue())));
    }

    order.totalAmount = total;
    order = orders.saveAndFlush(order);
    for (OrderItem item : newItems) {
      item.orderId = order.id;
    }
    items.saveAll(newItems);
    return toManagement(order);
  }

  @Override
  @Transactional
  public OrderManagementResponse update(String number, OrderUpdateRequest request) {
    OrderEntity order = orders.findByOrderNumberForUpdate(number)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

    String target = request.status();
    if (target != null && !target.equals(order.status)) {
      Set<String> allowed = ALLOWED_TRANSITIONS.get(order.status);
      if (allowed == null || !allowed.contains(target)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Order status cannot change from " + order.status + " to " + target);
      }
      if ("CANCELLED".equals(target)) {
        cancel(order);
      }
      order.status = target;
    }
    if (request.trackingNumber() != null) {
      order.trackingNumber = request.trackingNumber();
    }
    return toManagement(orders.save(order));
  }

  /** Cancelling is the only path that puts previously reserved stock back on the shelf. */
  private void cancel(OrderEntity order) {
    BigDecimal reservedAmount = refunds.sumReservedAmountByOrder(order.orderNumber);
    if (reservedAmount != null && reservedAmount.signum() > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Order has open or approved refund requests; resolve them before cancelling");
    }
    // A parcel already handed to a carrier cannot be un-shipped by cancelling the order.
    long liveShipments = shipments.findAllByOrderIdOrderByIdAsc(order.id).stream()
        .filter(shipment -> !"CANCELLED".equals(shipment.status))
        .count();
    if (liveShipments > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Order already has shipments; cancel or delete the parcels first");
    }
    if (order.stockReserved) {
      for (OrderItem line : items.findAllByOrderIdOrderById(order.id)) {
        inventory.release(line.productId, line.quantity, order.orderNumber);
      }
      order.stockReserved = false;
    }
  }

  /**
   * Physically removes an order and its child rows. Unlike cancellation this is an administrative
   * cleanup: it is only allowed when no money is still in flight, and any stock still reserved by
   * the order is returned first. Stock movements are kept as an append-only audit trail.
   */
  @Override
  @Transactional
  public void delete(String number) {
    OrderEntity order = orders.findByOrderNumberForUpdate(number)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

    BigDecimal reservedAmount = refunds.sumReservedAmountByOrder(order.orderNumber);
    if (reservedAmount != null && reservedAmount.signum() > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Order has open or approved refund requests; resolve them before deleting");
    }

    List<OrderItem> orderLines = items.findAllByOrderIdOrderById(order.id);
    if (order.stockReserved) {
      for (OrderItem line : orderLines) {
        // Inspected returns are already back on the shelf; releasing them again would double-count.
        Long received = returnItems.sumAcceptedByOrderItem(line.id);
        int stillOut = line.quantity - (received == null ? 0 : received.intValue());
        inventory.release(line.productId, stillOut, order.orderNumber);
      }
      order.stockReserved = false;
    }

    // Children first: foreign keys are RESTRICT, so order_items/refunds/shipments must go before the order.
    for (Shipment shipment : shipments.findAllByOrderIdOrderByIdAsc(order.id)) {
      shipmentEvents.deleteAll(shipmentEvents.findAllByShipmentIdOrderByOccurredAtAscIdAsc(shipment.id));
      shipmentItems.deleteAll(shipmentItems.findAllByShipmentId(shipment.id));
      shipments.delete(shipment);
    }
    for (RefundRequest refund : refunds.findAllByOrderNumberOrderByIdDesc(order.orderNumber)) {
      returnItems.deleteAll(returnItems.findAllByRefundRequestId(refund.id));
    }
    refunds.deleteAll(refunds.findAllByOrderNumberOrderByIdDesc(order.orderNumber));
    items.deleteAll(orderLines);
    orders.delete(order);
  }

  private OrderManagementResponse toManagement(OrderEntity o) {
    return new OrderManagementResponse(o.id, o.orderNumber, o.customerId, o.status, o.trackingNumber,
        o.createdAt, o.totalAmount);
  }
}
