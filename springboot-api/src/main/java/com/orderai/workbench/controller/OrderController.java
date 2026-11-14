package com.orderai.workbench.controller;

import com.orderai.workbench.service.OrderService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import org.springframework.web.bind.annotation.*;
import com.orderai.workbench.repository.OrderRepository;
import com.orderai.workbench.entity.OrderEntity;
import com.orderai.workbench.dto.response.OrderManagementResponse;
import com.orderai.workbench.dto.request.OrderRequest;
import com.orderai.workbench.dto.request.OrderUpdateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
  private final OrderService service;
  private final OrderRepository orders;

  /**
   * Initializes this component with its required dependencies.
   * @param s input used by this operation
   */
  public OrderController(OrderService s, OrderRepository orders) {
    service = s;
    this.orders = orders;
  }

  @GetMapping
  public ApiResponse<Page<OrderManagementResponse>> list(
      @RequestParam(required=false) String order_number,
      @RequestParam(required=false) Long customer_id,
      @RequestParam(required=false) String status,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime created_from,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime created_to,
      @PageableDefault(size=10, sort="createdAt", direction=Sort.Direction.DESC) Pageable pageable) {
    if(created_from!=null&&created_to!=null&&created_from.isAfter(created_to))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"created_from must be before created_to");
    Specification<OrderEntity> spec = (root,q,cb) -> cb.conjunction();
    if(order_number!=null&&!order_number.isBlank()) spec=spec.and((r,q,cb)->cb.like(cb.lower(r.get("orderNumber")), order_number.toLowerCase()+"%"));
    if(customer_id!=null) spec=spec.and((r,q,cb)->cb.equal(r.get("customerId"),customer_id));
    if(status!=null&&!status.isBlank()) spec=spec.and((r,q,cb)->cb.equal(r.get("status"),status));
    if(created_from!=null) spec=spec.and((r,q,cb)->cb.greaterThanOrEqualTo(r.get("createdAt"),created_from));
    if(created_to!=null) spec=spec.and((r,q,cb)->cb.lessThanOrEqualTo(r.get("createdAt"),created_to));
    return ApiResponse.success(orders.findAll(spec,pageable).map(o -> new OrderManagementResponse(o.id,o.orderNumber,o.customerId,o.status,o.trackingNumber,o.createdAt)));
  }

  @PostMapping
  public ApiResponse<OrderManagementResponse> create(@RequestBody OrderRequest input) { if(input.orderNumber()==null||input.orderNumber().isBlank()||input.customerId()==null||input.status()==null||input.status().isBlank())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"order_number, customer_id and status are required");OrderEntity o=new OrderEntity(); o.orderNumber=input.orderNumber();o.customerId=input.customerId();o.status=input.status();o.trackingNumber=input.trackingNumber();o=orders.save(o);return ApiResponse.success(new OrderManagementResponse(o.id,o.orderNumber,o.customerId,o.status,o.trackingNumber,o.createdAt)); }

  @PutMapping("/{number}")
  public ApiResponse<OrderManagementResponse> update(@PathVariable String number, @RequestBody OrderUpdateRequest input) {
    OrderEntity order = orders.findByOrderNumber(number).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Order not found"));
    if (input.status() != null) order.status = input.status();
    if (input.trackingNumber() != null) order.trackingNumber = input.trackingNumber();
    order=orders.save(order); return ApiResponse.success(new OrderManagementResponse(order.id,order.orderNumber,order.customerId,order.status,order.trackingNumber,order.createdAt));
  }

  @DeleteMapping("/{number}")
  public ApiResponse<Void> delete(@PathVariable String number) {
    OrderEntity order = orders.findByOrderNumber(number).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Order not found"));
    orders.delete(order); return ApiResponse.success(null);
  }

  @GetMapping("/{number}")
  /**
   * Finds and returns the requested resource.
   * @param number input used by this operation
   * @return the result of this operation
   */
  public ApiResponse<OrderResponse> find(@PathVariable String number) {
    return ApiResponse.success(service.find(number));
  }
}
