package com.orderai.workbench.service.support;

import com.orderai.workbench.dto.response.OrderItemResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.dto.response.RefundManagementResponse;
import com.orderai.workbench.dto.response.RefundOrderSummary;
import com.orderai.workbench.dto.response.ShipmentSummaryResponse;
import com.orderai.workbench.entity.OrderEntity;
import com.orderai.workbench.entity.OrderItem;
import com.orderai.workbench.entity.RefundRequest;
import com.orderai.workbench.entity.Shipment;
import com.orderai.workbench.repository.CustomerRepository;
import com.orderai.workbench.repository.OrderItemRepository;
import com.orderai.workbench.repository.RefundRequestRepository;
import com.orderai.workbench.repository.ShipmentItemRepository;
import com.orderai.workbench.repository.ShipmentRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns order, shipment and refund rows into read models.
 *
 * <p>Kept in one place so the order detail endpoint, the customer order history and the refund detail
 * endpoint all report the same refundable amount and the same parcel state instead of each
 * re-deriving it.
 */
@Component
public class OrderViewAssembler {
  private final OrderItemRepository items;
  private final RefundRequestRepository refunds;
  private final CustomerRepository customers;
  private final ShipmentRepository shipments;
  private final ShipmentItemRepository shipmentItems;

  public OrderViewAssembler(OrderItemRepository items, RefundRequestRepository refunds,
      CustomerRepository customers, ShipmentRepository shipments, ShipmentItemRepository shipmentItems) {
    this.items = items;
    this.refunds = refunds;
    this.customers = customers;
    this.shipments = shipments;
    this.shipmentItems = shipmentItems;
  }

  public OrderResponse toDetail(OrderEntity order) {
    return toDetail(order, customerEmail(order.customerId));
  }

  public OrderResponse toDetail(OrderEntity order, String customerEmail) {
    BigDecimal pending = amount(order, "PENDING_MANUAL_APPROVAL");
    BigDecimal approved = amount(order, "APPROVED_MANUALLY");
    List<RefundManagementResponse> refundViews =
        refunds.findAllByOrderNumberOrderByIdDesc(order.orderNumber).stream().map(this::toRefundRow).toList();
    List<Shipment> parcels = shipments.findAllByOrderIdOrderByIdAsc(order.id);
    List<ShipmentSummaryResponse> shipmentViews = parcels.stream().map(this::toShipmentRow).toList();
    return new OrderResponse(
        order.id,
        order.orderNumber,
        order.customerId,
        customerEmail,
        order.status,
        order.trackingNumber == null ? "" : order.trackingNumber,
        order.stockReserved,
        order.createdAt,
        amount(order.totalAmount),
        pending,
        approved,
        refundable(order, pending, approved),
        shipmentStatus(parcels),
        items.findAllByOrderIdOrderById(order.id).stream().map(this::toLine).toList(),
        shipmentViews,
        refundViews);
  }

  public RefundManagementResponse toRefundRow(RefundRequest refund) {
    return new RefundManagementResponse(
        refund.id,
        refund.orderNumber,
        refund.reason,
        refund.status,
        refund.refundType,
        amount(refund.amount),
        refund.inventoryReceived,
        refund.createdAt,
        refund.reviewedAt);
  }

  public ShipmentSummaryResponse toShipmentRow(Shipment shipment) {
    return new ShipmentSummaryResponse(
        shipment.id,
        shipment.orderNumber,
        shipment.carrier,
        shipment.trackingNumber,
        shipment.status,
        shipment.shippedAt,
        shipment.estimatedDelivery,
        shipment.deliveredAt);
  }

  /**
   * The one status a customer-facing summary should show when several parcels exist: an exception
   * outranks everything, then "still moving" until the last parcel is delivered.
   */
  private String shipmentStatus(List<Shipment> parcels) {
    if (parcels.isEmpty()) {
      return null;
    }
    List<Shipment> active = parcels.stream()
        .filter(parcel -> !"CANCELLED".equals(parcel.status))
        .toList();
    if (active.isEmpty()) {
      return "CANCELLED";
    }
    if (active.stream().anyMatch(parcel -> "EXCEPTION".equals(parcel.status))) {
      return "EXCEPTION";
    }
    if (active.stream().allMatch(parcel -> "DELIVERED".equals(parcel.status))) {
      return "DELIVERED";
    }
    return active.get(active.size() - 1).status;
  }

  /** Money still available for a new refund request after pending and approved requests. */
  public BigDecimal refundable(OrderEntity order) {
    return refundable(order, amount(order, "PENDING_MANUAL_APPROVAL"), amount(order, "APPROVED_MANUALLY"));
  }

  public RefundOrderSummary summarize(OrderEntity order) {
    if (order == null) {
      return null;
    }
    return new RefundOrderSummary(
        order.orderNumber, order.status, customerEmail(order.customerId), amount(order.totalAmount), refundable(order));
  }

  public String customerEmail(Long customerId) {
    if (customerId == null) {
      return null;
    }
    return customers.findById(customerId).map(c -> c.email).orElse(null);
  }

  public OrderItemResponse toLine(OrderItem item) {
    BigDecimal unitPrice = amount(item.unitPrice);
    Long shipped = shipmentItems.sumShippedByOrderItem(item.id);
    return new OrderItemResponse(
        item.id,
        item.productId,
        item.skuSnapshot,
        item.productNameSnapshot,
        item.quantity,
        shipped == null ? 0 : shipped.intValue(),
        unitPrice,
        unitPrice.multiply(BigDecimal.valueOf(item.quantity)));
  }

  private BigDecimal amount(OrderEntity order, String status) {
    return amount(refunds.sumAmountByOrderAndStatus(order.orderNumber, status));
  }

  private BigDecimal refundable(OrderEntity order, BigDecimal pending, BigDecimal approved) {
    BigDecimal remaining = amount(order.totalAmount).subtract(pending).subtract(approved);
    return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
