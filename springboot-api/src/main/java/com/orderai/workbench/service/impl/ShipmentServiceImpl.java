package com.orderai.workbench.service.impl;

import com.orderai.workbench.dto.request.ShipmentCreateRequest;
import com.orderai.workbench.dto.request.ShipmentEventRequest;
import com.orderai.workbench.dto.request.ShipmentItemRequest;
import com.orderai.workbench.dto.request.ShipmentUpdateRequest;
import com.orderai.workbench.dto.response.ShipmentEventResponse;
import com.orderai.workbench.dto.response.ShipmentItemResponse;
import com.orderai.workbench.dto.response.ShipmentResponse;
import com.orderai.workbench.dto.response.ShipmentSummaryResponse;
import com.orderai.workbench.entity.OrderEntity;
import com.orderai.workbench.entity.OrderItem;
import com.orderai.workbench.entity.Shipment;
import com.orderai.workbench.entity.ShipmentEvent;
import com.orderai.workbench.entity.ShipmentItem;
import com.orderai.workbench.repository.OrderItemRepository;
import com.orderai.workbench.repository.OrderRepository;
import com.orderai.workbench.repository.ShipmentEventRepository;
import com.orderai.workbench.repository.ShipmentItemRepository;
import com.orderai.workbench.repository.ShipmentRepository;
import com.orderai.workbench.service.ShipmentService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Parcel lifecycle and its effect on the order header.
 *
 * <p>Rules enforced here:
 *
 * <ul>
 *   <li>a parcel can only be created for an order that is still {@code PROCESSING}, i.e. one that
 *       still has units waiting to leave the warehouse;
 *   <li>the units of one order line split across non-cancelled parcels can never exceed the ordered
 *       quantity, so partial shipping cannot invent goods;
 *   <li>a parcel's status only moves forward, and delivery is terminal;
 *   <li>the order becomes {@code SHIPPED} only when every unit is on a parcel, and {@code
 *       DELIVERED} only when every parcel has arrived. A partially shipped order therefore stays
 *       {@code PROCESSING} and keeps showing up in the shipping dialog. Deleting the last parcel
 *       rolls a shipped order back to {@code PROCESSING}.
 * </ul>
 */
@Service
public class ShipmentServiceImpl implements ShipmentService {
  private static final String PROCESSING = "PROCESSING";
  private static final String SHIPPED = "SHIPPED";
  private static final String CREATED = "CREATED";
  private static final String IN_TRANSIT = "IN_TRANSIT";
  private static final String DELIVERED = "DELIVERED";
  private static final String CANCELLED = "CANCELLED";

  /** Forward-only parcel statuses; a status may always "change" to itself (a plain scan). */
  private static final Map<String, Set<String>> ALLOWED_TRANSITIONS =
      Map.of(
          CREATED,
          Set.of(CREATED, "PICKED_UP", IN_TRANSIT, DELIVERED, "EXCEPTION", CANCELLED),
          "PICKED_UP",
          Set.of("PICKED_UP", IN_TRANSIT, DELIVERED, "EXCEPTION"),
          IN_TRANSIT,
          Set.of(IN_TRANSIT, DELIVERED, "EXCEPTION"),
          "EXCEPTION",
          Set.of("EXCEPTION", IN_TRANSIT, DELIVERED, CANCELLED),
          DELIVERED,
          Set.of(DELIVERED),
          CANCELLED,
          Set.of(CANCELLED));

  private final ShipmentRepository shipments;
  private final ShipmentItemRepository shipmentItems;
  private final ShipmentEventRepository events;
  private final OrderRepository orders;
  private final OrderItemRepository orderItems;

  public ShipmentServiceImpl(
      ShipmentRepository shipments,
      ShipmentItemRepository shipmentItems,
      ShipmentEventRepository events,
      OrderRepository orders,
      OrderItemRepository orderItems) {
    this.shipments = shipments;
    this.shipmentItems = shipmentItems;
    this.events = events;
    this.orders = orders;
    this.orderItems = orderItems;
  }

  @Override
  @Transactional(readOnly = true)
  public ShipmentResponse find(Long id) {
    return toResponse(shipments.findById(id).orElseThrow(() -> notFound("Shipment not found")));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ShipmentSummaryResponse> findByOrder(String orderNumber) {
    return shipments.findAllByOrderNumberOrderByIdAsc(orderNumber).stream()
        .map(this::toSummary)
        .toList();
  }

  @Override
  @Transactional
  public ShipmentResponse create(ShipmentCreateRequest request) {
    if (request.orderNumber() == null || request.orderNumber().isBlank()) {
      throw badRequest("order_number is required");
    }
    if (request.carrier() == null || request.carrier().isBlank()) {
      throw badRequest("carrier is required");
    }
    if (request.trackingNumber() == null || request.trackingNumber().isBlank()) {
      throw badRequest("tracking_number is required");
    }

    OrderEntity order =
        orders
            .findByOrderNumberForUpdate(request.orderNumber().trim())
            .orElseThrow(() -> notFound("Order not found"));
    if (CANCELLED.equals(order.status)) {
      throw conflict("Cancelled orders cannot be shipped");
    }
    if (DELIVERED.equals(order.status)) {
      throw conflict("Order is already delivered");
    }
    if (!PROCESSING.equals(order.status)) {
      throw conflict("Only processing orders accept a parcel; this order has already been shipped");
    }

    String trackingNumber = request.trackingNumber().trim();
    if (shipments.existsByTrackingNumber(trackingNumber)) {
      throw conflict("Tracking number already exists");
    }

    List<OrderItem> lines = orderItems.findAllByOrderIdOrderById(order.id);
    if (lines.isEmpty()) {
      throw conflict("Order has no items to ship");
    }
    Map<Long, Integer> plan = planShipment(lines, request.items());

    LocalDateTime now = LocalDateTime.now();
    Shipment shipment = new Shipment();
    shipment.orderId = order.id;
    shipment.orderNumber = order.orderNumber;
    shipment.carrier = request.carrier().trim();
    shipment.trackingNumber = trackingNumber;
    shipment.status = CREATED;
    shipment.shippedAt = now;
    shipment.estimatedDelivery = request.estimatedDelivery();
    shipment.receiverName = request.receiverName();
    shipment.note = request.note();
    shipment = shipments.saveAndFlush(shipment);

    List<ShipmentItem> rows = new ArrayList<>();
    for (Map.Entry<Long, Integer> entry : plan.entrySet()) {
      ShipmentItem row = new ShipmentItem();
      row.shipmentId = shipment.id;
      row.orderItemId = entry.getKey();
      row.quantity = entry.getValue();
      rows.add(row);
    }
    shipmentItems.saveAll(rows);

    recordEvent(shipment, CREATED, null, "Shipment created", now);
    syncOrder(order);
    return toResponse(shipment);
  }

  @Override
  @Transactional
  public ShipmentResponse update(Long id, ShipmentUpdateRequest request) {
    Shipment shipment = shipments.findById(id).orElseThrow(() -> notFound("Shipment not found"));
    OrderEntity order =
        orders.lockById(shipment.orderId).orElseThrow(() -> notFound("Order not found"));

    if (request.carrier() != null && !request.carrier().isBlank()) {
      shipment.carrier = request.carrier().trim();
    }
    if (request.trackingNumber() != null) {
      String trackingNumber = request.trackingNumber().trim();
      if (trackingNumber.isEmpty()) {
        throw badRequest("tracking_number cannot be blank");
      }
      if (!trackingNumber.equals(shipment.trackingNumber)) {
        if (shipments.existsByTrackingNumber(trackingNumber)) {
          throw conflict("Tracking number already exists");
        }
        shipment.trackingNumber = trackingNumber;
      }
    }
    if (request.estimatedDelivery() != null) {
      shipment.estimatedDelivery = request.estimatedDelivery();
    }
    if (request.receiverName() != null) {
      shipment.receiverName = request.receiverName();
    }
    if (request.note() != null) {
      shipment.note = request.note();
    }

    String target = request.status();
    if (target != null && !target.isBlank() && !target.equals(shipment.status)) {
      applyStatus(shipment, target, request.location(), request.description(), LocalDateTime.now());
    }

    shipment = shipments.saveAndFlush(shipment);
    syncOrder(order);
    return toResponse(shipment);
  }

  @Override
  @Transactional
  public ShipmentResponse addEvent(Long id, ShipmentEventRequest request) {
    Shipment shipment = shipments.findById(id).orElseThrow(() -> notFound("Shipment not found"));
    OrderEntity order =
        orders.lockById(shipment.orderId).orElseThrow(() -> notFound("Order not found"));
    if (CANCELLED.equals(shipment.status)) {
      throw conflict("Cancelled shipments accept no new tracking events");
    }

    LocalDateTime occurredAt =
        request.occurredAt() == null ? LocalDateTime.now() : request.occurredAt();
    String target = request.status();
    if (target != null && !target.isBlank() && !target.equals(shipment.status)) {
      applyStatus(shipment, target, request.location(), request.description(), occurredAt);
    } else {
      recordEvent(shipment, shipment.status, request.location(), request.description(), occurredAt);
    }

    shipment = shipments.saveAndFlush(shipment);
    syncOrder(order);
    return toResponse(shipment);
  }

  @Override
  @Transactional
  public void delete(Long id) {
    Shipment shipment = shipments.findById(id).orElseThrow(() -> notFound("Shipment not found"));
    OrderEntity order =
        orders.lockById(shipment.orderId).orElseThrow(() -> notFound("Order not found"));
    if (DELIVERED.equals(shipment.status)) {
      throw conflict("Delivered shipments are kept as delivery records");
    }
    if (IN_TRANSIT.equals(shipment.status) || "PICKED_UP".equals(shipment.status)) {
      throw conflict(
          "Only parcels that have not been picked up can be deleted; cancel the shipment instead");
    }

    // Children first: the foreign keys are RESTRICT.
    events.deleteAll(events.findAllByShipmentIdOrderByOccurredAtAscIdAsc(id));
    shipmentItems.deleteAll(shipmentItems.findAllByShipmentId(id));
    shipments.delete(shipment);
    shipments.flush();
    syncOrder(order);
  }

  @Override
  public ShipmentSummaryResponse toSummary(Shipment shipment) {
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
   * Works out how many units of each order line go into the new parcel.
   *
   * <p>Empty request means "everything still unshipped"; an explicit request is capped by what is
   * left on each line, so two parcels can never together ship more than was ordered.
   */
  private Map<Long, Integer> planShipment(
      List<OrderItem> lines, List<ShipmentItemRequest> requested) {
    Map<Long, OrderItem> byId =
        lines.stream()
            .collect(
                Collectors.toMap(
                    line -> line.id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    Map<Long, Integer> plan = new TreeMap<>();

    if (requested == null || requested.isEmpty()) {
      for (OrderItem line : lines) {
        int remaining = remaining(line);
        if (remaining > 0) {
          plan.put(line.id, remaining);
        }
      }
    } else {
      for (ShipmentItemRequest line : requested) {
        if (line == null
            || line.orderItemId() == null
            || line.quantity() == null
            || line.quantity() <= 0) {
          throw badRequest("Each shipment line needs an order_item_id and a positive quantity");
        }
        OrderItem orderLine = byId.get(line.orderItemId());
        if (orderLine == null) {
          throw badRequest(
              "order_item_id " + line.orderItemId() + " does not belong to this order");
        }
        plan.merge(orderLine.id, line.quantity(), Integer::sum);
      }
      for (Map.Entry<Long, Integer> entry : plan.entrySet()) {
        OrderItem line = byId.get(entry.getKey());
        int remaining = remaining(line);
        if (entry.getValue() > remaining) {
          throw conflict(
              "Shipment exceeds the unshipped quantity of "
                  + line.skuSnapshot
                  + " (remaining "
                  + remaining
                  + ")");
        }
      }
    }

    if (plan.isEmpty()) {
      throw conflict("Every unit of this order has already been shipped");
    }
    return plan;
  }

  private int remaining(OrderItem line) {
    Long shipped = shipmentItems.sumShippedByOrderItem(line.id);
    return line.quantity - (shipped == null ? 0 : shipped.intValue());
  }

  /**
   * Keeps the order header truthful after any parcel change: status, and the tracking number shown
   * by older screens (the newest active parcel wins).
   */
  /**
   * Keeps the order header in step with its parcels.
   *
   * <p>The order only turns {@code SHIPPED} once every ordered unit is on a parcel, so a partially
   * shipped order stays {@code PROCESSING} and can still receive the next parcel. Delivery flips to
   * {@code DELIVERED} only when the whole order is out and every parcel has arrived.
   */
  private void syncOrder(OrderEntity order) {
    List<Shipment> active =
        shipments.findAllByOrderIdOrderByIdAsc(order.id).stream()
            .filter(shipment -> !CANCELLED.equals(shipment.status))
            .toList();

    if (active.isEmpty()) {
      if (SHIPPED.equals(order.status)) {
        order.status = PROCESSING;
      }
      order.trackingNumber = null;
      orders.save(order);
      return;
    }

    if (fullyShipped(order)) {
      if (PROCESSING.equals(order.status)) {
        order.status = SHIPPED;
      }
      if (SHIPPED.equals(order.status)
          && active.stream().allMatch(shipment -> DELIVERED.equals(shipment.status))) {
        order.status = DELIVERED;
      }
    }
    order.trackingNumber = active.get(active.size() - 1).trackingNumber;
    orders.save(order);
  }

  private boolean fullyShipped(OrderEntity order) {
    Long ordered = orderItems.sumQuantityByOrder(order.id);
    Long shipped = shipmentItems.sumShippedByOrder(order.id);
    long orderedUnits = ordered == null ? 0 : ordered;
    long shippedUnits = shipped == null ? 0 : shipped;
    return orderedUnits > 0 && shippedUnits >= orderedUnits;
  }

  private void applyStatus(
      Shipment shipment,
      String target,
      String location,
      String description,
      LocalDateTime occurredAt) {
    Set<String> allowed =
        ALLOWED_TRANSITIONS.getOrDefault(shipment.status, Set.of(shipment.status));
    if (!allowed.contains(target)) {
      throw conflict("Shipment status cannot change from " + shipment.status + " to " + target);
    }
    shipment.status = target;
    if (DELIVERED.equals(target) && shipment.deliveredAt == null) {
      shipment.deliveredAt = occurredAt;
    }
    recordEvent(shipment, target, location, description, occurredAt);
  }

  private void recordEvent(
      Shipment shipment,
      String status,
      String location,
      String description,
      LocalDateTime occurredAt) {
    ShipmentEvent event = new ShipmentEvent();
    event.shipmentId = shipment.id;
    event.status = status;
    event.location = location;
    event.description = description;
    event.occurredAt = occurredAt;
    events.save(event);
  }

  private ShipmentResponse toResponse(Shipment shipment) {
    Map<Long, OrderItem> lines =
        orderItems.findAllByOrderIdOrderById(shipment.orderId).stream()
            .collect(Collectors.toMap(line -> line.id, Function.identity(), (a, b) -> a));
    List<ShipmentItemResponse> itemViews =
        shipmentItems.findAllByShipmentId(shipment.id).stream()
            .map(
                row -> {
                  OrderItem line = lines.get(row.orderItemId);
                  return new ShipmentItemResponse(
                      row.id,
                      row.orderItemId,
                      line == null ? null : line.skuSnapshot,
                      line == null ? null : line.productNameSnapshot,
                      row.quantity);
                })
            .toList();
    List<ShipmentEventResponse> eventViews =
        events.findAllByShipmentIdOrderByOccurredAtAscIdAsc(shipment.id).stream()
            .map(
                event ->
                    new ShipmentEventResponse(
                        event.id,
                        event.status,
                        event.location,
                        event.description,
                        event.occurredAt,
                        event.createdAt))
            .toList();
    return new ShipmentResponse(
        shipment.id,
        shipment.orderId,
        shipment.orderNumber,
        shipment.carrier,
        shipment.trackingNumber,
        shipment.status,
        shipment.shippedAt,
        shipment.estimatedDelivery,
        shipment.deliveredAt,
        shipment.receiverName,
        shipment.note,
        shipment.createdAt,
        shipment.updatedAt,
        itemViews,
        eventViews);
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
