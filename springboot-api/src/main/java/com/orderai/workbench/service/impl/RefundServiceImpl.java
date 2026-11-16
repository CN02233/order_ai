package com.orderai.workbench.service.impl;

import com.orderai.workbench.dto.request.RefundCreateRequest;
import com.orderai.workbench.dto.request.RefundReceiveLineRequest;
import com.orderai.workbench.dto.request.RefundReceiveRequest;
import com.orderai.workbench.dto.request.RefundReturnLineRequest;
import com.orderai.workbench.dto.response.RefundResponse;
import com.orderai.workbench.dto.response.RefundReturnItemResponse;
import com.orderai.workbench.entity.*;
import com.orderai.workbench.repository.*;
import com.orderai.workbench.service.InventoryService;
import com.orderai.workbench.service.RefundService;
import com.orderai.workbench.service.support.OrderViewAssembler;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refund workflow: request, manual review, then physical return.
 *
 * <p>Approval only records a decision — no money moves. Stock is increased only when returned goods
 * are actually inspected, and only for the accepted quantity.
 */
@Service
public class RefundServiceImpl implements RefundService {
  private static final String PENDING = "PENDING_MANUAL_APPROVAL";
  private static final String APPROVED = "APPROVED_MANUALLY";
  private static final String REJECTED = "REJECTED";
  private static final String REFUND_ONLY = "REFUND_ONLY";
  private static final String RETURN_AND_REFUND = "RETURN_AND_REFUND";

  /** A cancelled order never had goods to return, so it cannot carry a refund request. */
  private static final Set<String> REFUNDABLE_ORDER_STATUSES =
      Set.of("PROCESSING", "SHIPPED", "DELIVERED");

  private final RefundRequestRepository refunds;
  private final OrderRepository orders;
  private final OrderItemRepository orderItems;
  private final RefundReturnItemRepository returnItems;
  private final InventoryService inventory;
  private final OrderViewAssembler views;

  public RefundServiceImpl(RefundRequestRepository refunds, OrderRepository orders, OrderItemRepository orderItems,
      RefundReturnItemRepository returnItems, InventoryService inventory, OrderViewAssembler views) {
    this.refunds = refunds;
    this.orders = orders;
    this.orderItems = orderItems;
    this.returnItems = returnItems;
    this.inventory = inventory;
    this.views = views;
  }

  @Override
  @Transactional
  public RefundResponse create(RefundCreateRequest request, String key) {
    RefundRequest previous = refunds.findByIdempotencyKey(key).orElse(null);
    if (previous != null) {
      return response(previous, true);
    }

    if (request.orderNumber() == null || request.orderNumber().isBlank() || request.reason() == null
        || request.reason().isBlank() || request.amount() == null || request.amount().signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "order_number, reason and positive amount are required");
    }
    String type = request.refundType() == null ? REFUND_ONLY : request.refundType();
    if (!Set.of(REFUND_ONLY, RETURN_AND_REFUND).contains(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported refund_type");
    }

    // Locking the order serialises concurrent requests for the same order, so two requests cannot
    // both consume the last refundable cent.
    OrderEntity order = orders.findByOrderNumberForUpdate(request.orderNumber().trim())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    if (!REFUNDABLE_ORDER_STATUSES.contains(order.status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Only processing, shipped or delivered orders can be refunded");
    }
    BigDecimal refundable = views.refundable(order);
    if (request.amount().compareTo(refundable) > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Refund amount exceeds the remaining refundable amount of " + refundable);
    }

    List<RefundReturnLineRequest> lines = request.returnItems() == null ? List.of() : request.returnItems();
    if (RETURN_AND_REFUND.equals(type) && lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Return items are required for return_and_refund");
    }
    if (REFUND_ONLY.equals(type) && !lines.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Return items are only valid for return_and_refund");
    }

    RefundRequest refund = refunds.saveAndFlush(new RefundRequest(key, order.orderNumber,
        request.reason().trim(), request.amount(), type));

    Set<Long> seen = new HashSet<>();
    for (RefundReturnLineRequest line : lines) {
      if (line.orderItemId() == null || line.quantity() == null || line.quantity() <= 0
          || !seen.add(line.orderItemId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Return lines require unique order_item_id values and positive quantities");
      }
      OrderItem item = orderItems.findByIdAndOrderId(line.orderItemId(), order.id)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "Return item does not belong to this order"));
      long alreadyRequested = Optional.ofNullable(
          returnItems.sumRequestedByOrderItemExcludingRefund(item.id, refund.id)).orElse(0L);
      if (line.quantity() + alreadyRequested > item.quantity) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Return quantity exceeds the " + (item.quantity - alreadyRequested)
                + " unit(s) still returnable for this line");
      }
      RefundReturnItem returnItem = new RefundReturnItem();
      returnItem.refundRequestId = refund.id;
      returnItem.orderItemId = item.id;
      returnItem.quantity = line.quantity();
      returnItems.save(returnItem);
    }
    return response(refund, false);
  }

  @Override
  @Transactional(readOnly = true)
  public RefundResponse find(long id) {
    return response(refunds.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found")), null);
  }

  @Override
  @Transactional
  public RefundResponse approve(long id) {
    RefundRequest refund = pendingForUpdate(id);
    refund.status = APPROVED;
    refund.reviewedAt = LocalDateTime.now();
    return response(refunds.save(refund), null);
  }

  @Override
  @Transactional
  public RefundResponse reject(long id) {
    RefundRequest refund = pendingForUpdate(id);
    refund.status = REJECTED;
    refund.reviewedAt = LocalDateTime.now();
    return response(refunds.save(refund), null);
  }

  @Override
  @Transactional
  public RefundResponse receive(long id, RefundReceiveRequest request) {
    RefundRequest refund = refunds.findByIdForUpdate(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found"));
    if (!APPROVED.equals(refund.status) || !RETURN_AND_REFUND.equals(refund.refundType)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Only approved return-and-refund requests can receive inventory");
    }
    if (refund.inventoryReceived) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Returned inventory was already received");
    }
    if (request == null || request.items() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accepted return quantities are required");
    }

    Map<Long, Integer> accepted = new HashMap<>();
    for (RefundReceiveLineRequest line : request.items()) {
      if (line.orderItemId() == null || line.acceptedQuantity() == null || line.acceptedQuantity() < 0
          || accepted.put(line.orderItemId(), line.acceptedQuantity()) != null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Accepted return lines are invalid");
      }
    }

    List<RefundReturnItem> lines = returnItems.findAllByRefundRequestId(refund.id);
    if (lines.size() != accepted.size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Provide an accepted quantity for every requested return item");
    }
    for (RefundReturnItem line : lines) {
      Integer count = accepted.get(line.orderItemId);
      if (count == null || count > line.quantity) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Accepted quantity must be between zero and the requested quantity");
      }
      OrderItem orderItem = orderItems.findById(line.orderItemId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Order item not found"));
      // Only inspected goods re-enter stock, and they re-enter it here — not on approval.
      if (count > 0) {
        inventory.receiveReturn(orderItem.productId, count, "REFUND-" + refund.id);
      }
      line.acceptedQuantity = count;
      line.receivedAt = LocalDateTime.now();
      returnItems.save(line);
    }
    refund.inventoryReceived = true;
    return response(refunds.save(refund), null);
  }

  @Override
  @Transactional
  public RefundResponse update(long id, String reason) {
    RefundRequest refund = pendingForUpdate(id);
    if (reason == null || reason.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
    }
    refund.reason = reason.trim();
    return response(refunds.save(refund), null);
  }

  @Override
  @Transactional
  public void delete(long id) {
    RefundRequest refund = refunds.findByIdForUpdate(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found"));
    if (!PENDING.equals(refund.status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Processed refunds are retained for audit");
    }
    returnItems.deleteAll(returnItems.findAllByRefundRequestId(id));
    refunds.delete(refund);
  }

  private RefundRequest pendingForUpdate(long id) {
    RefundRequest refund = refunds.findByIdForUpdate(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found"));
    if (!PENDING.equals(refund.status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund already processed");
    }
    return refund;
  }

  private RefundResponse response(RefundRequest refund, Boolean replayed) {
    OrderEntity order = orders.findByOrderNumber(refund.orderNumber).orElse(null);
    return new RefundResponse(
        refund.id,
        refund.orderNumber,
        refund.reason,
        refund.status,
        refund.refundType,
        refund.amount == null ? BigDecimal.ZERO : refund.amount,
        refund.inventoryReceived,
        replayed,
        refund.createdAt,
        refund.reviewedAt,
        views.summarize(order),
        returnItems.findAllByRefundRequestId(refund.id).stream().map(this::toReturnLine).toList());
  }

  private RefundReturnItemResponse toReturnLine(RefundReturnItem line) {
    OrderItem item = orderItems.findById(line.orderItemId).orElse(null);
    BigDecimal unitPrice = item == null || item.unitPrice == null ? BigDecimal.ZERO : item.unitPrice;
    return new RefundReturnItemResponse(
        line.orderItemId,
        item == null ? null : item.skuSnapshot,
        item == null ? null : item.productNameSnapshot,
        line.quantity,
        line.acceptedQuantity,
        unitPrice,
        unitPrice.multiply(BigDecimal.valueOf(line.quantity)),
        line.receivedAt);
  }
}
