package com.orderai.workbench.controller;

import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.request.CreateCustomerRequest;
import com.orderai.workbench.dto.response.CustomerResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.service.CustomerService;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
  private final CustomerService service;

  public CustomerController(CustomerService service) {
    this.service = service;
  }

  /** Creates a customer record from the supplied request body. */
  @PostMapping
  public ApiResponse<CustomerResponse> create(@RequestBody CreateCustomerRequest request) {
    return ApiResponse.success(service.create(request));
  }

  /** Lists customers for selection in related order forms. */
  @GetMapping
  public ApiResponse<List<CustomerResponse>> list(@RequestParam(name = "limit", defaultValue = "200") int limit) {
    return ApiResponse.success(service.list(limit));
  }

  @GetMapping("/by-email/{email}")
  public ApiResponse<CustomerResponse> find(@PathVariable String email) {
    Optional<CustomerResponse> customer = service.find(email);
    return customer
        .map(ApiResponse::success)
        .orElseGet(() -> ApiResponse.failure("Customer not found"));
  }

  /** Lists orders associated with a customer; unknown emails return an error envelope. */
  @GetMapping("/by-email/{email}/orders")
  public ApiResponse<List<OrderResponse>> orders(@PathVariable String email) {
    Optional<List<OrderResponse>> customerOrders = service.orders(email);
    return customerOrders
        .map(ApiResponse::success)
        .orElseGet(() -> ApiResponse.failure("Customer not found"));
  }

  /** Removes a customer that never ordered; an order must keep its customer for history. */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    service.delete(id);
    return ApiResponse.success(null);
  }
}
