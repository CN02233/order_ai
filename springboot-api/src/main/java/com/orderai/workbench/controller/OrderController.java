package com.orderai.workbench.controller;

import com.orderai.workbench.service.OrderService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
  private final OrderService service;

  /**
   * Initializes this component with its required dependencies.
   * @param s input used by this operation
   */
  public OrderController(OrderService s) {
    service = s;
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
