package com.orderai.workbench.controller;

import com.orderai.workbench.service.OrderService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import org.springframework.web.bind.annotation.*;
import com.orderai.workbench.repository.OrderRepository;
import com.orderai.workbench.entity.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
  public ApiResponse<Page<OrderEntity>> list(Pageable pageable) { return ApiResponse.success(orders.findAll(pageable)); }

  @PostMapping
  public ApiResponse<OrderEntity> create(@RequestBody OrderEntity input) { input.id=null; return ApiResponse.success(orders.save(input)); }

  @PutMapping("/{number}")
  public ApiResponse<OrderEntity> update(@PathVariable String number, @RequestBody OrderEntity input) {
    OrderEntity order = orders.findByOrderNumber(number).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Order not found"));
    if (input.status != null) order.status = input.status;
    if (input.trackingNumber != null) order.trackingNumber = input.trackingNumber;
    return ApiResponse.success(orders.save(order));
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
