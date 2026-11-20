package com.orderai.workbench.controller;

import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.request.OrderRequest;
import com.orderai.workbench.dto.request.OrderUpdateRequest;
import com.orderai.workbench.dto.response.OrderManagementResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.dto.response.ShipmentSummaryResponse;
import com.orderai.workbench.entity.OrderEntity;
import com.orderai.workbench.repository.OrderRepository;
import com.orderai.workbench.service.OrderService;
import com.orderai.workbench.service.ShipmentService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
  private final OrderService service;
  private final ShipmentService shipments;
  private final OrderRepository orders;

  public OrderController(OrderService service, ShipmentService shipments, OrderRepository orders) {
    this.service = service;
    this.shipments = shipments;
    this.orders = orders;
  }

  /** Newest first, with every filter optional. */
  @GetMapping
  public ApiResponse<Page<OrderManagementResponse>> list(
      @RequestParam(name = "order_number", required = false) String orderNumber,
      @RequestParam(name = "customer_id", required = false) Long customerId,
      @RequestParam(required = false) String status,
      @RequestParam(name = "created_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime createdFrom,
      @RequestParam(name = "created_to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime createdTo,
      @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "created_from must be before created_to");
    }

    Specification<OrderEntity> spec = (root, query, cb) -> cb.conjunction();
    if (orderNumber != null && !orderNumber.isBlank()) {
      // Prefix match: operators usually paste the head of an order number.
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.like(cb.lower(root.get("orderNumber")), orderNumber.toLowerCase() + "%"));
    }
    if (customerId != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
    }
    if (status != null && !status.isBlank()) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
    }
    if (createdFrom != null) {
      spec =
          spec.and(
              (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
    }
    if (createdTo != null) {
      spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), createdTo));
    }

    return ApiResponse.success(orders.findAll(spec, pageable).map(OrderController::toView));
  }

  @PostMapping
  public ApiResponse<OrderManagementResponse> create(@RequestBody OrderRequest input) {
    return ApiResponse.success(service.create(input));
  }

  @PutMapping("/{number}")
  public ApiResponse<OrderManagementResponse> update(
      @PathVariable String number, @RequestBody OrderUpdateRequest input) {
    return ApiResponse.success(service.update(number, input));
  }

  @DeleteMapping("/{number}")
  public ApiResponse<Void> delete(@PathVariable String number) {
    service.delete(number);
    return ApiResponse.success(null);
  }

  /** Order detail: price-snapshotted lines, refunds and the parcels already sent. */
  @GetMapping("/{number}")
  public ApiResponse<OrderResponse> find(@PathVariable String number) {
    return ApiResponse.success(service.find(number));
  }

  /** Parcels of one order, so the order screen can show logistics without a second list query. */
  @GetMapping("/{number}/shipments")
  public ApiResponse<List<ShipmentSummaryResponse>> shipments(@PathVariable String number) {
    if (orders.findByOrderNumber(number).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
    }
    return ApiResponse.success(shipments.findByOrder(number));
  }

  private static OrderManagementResponse toView(OrderEntity o) {
    return new OrderManagementResponse(
        o.id, o.orderNumber, o.customerId, o.status, o.trackingNumber, o.createdAt, o.totalAmount);
  }
}
