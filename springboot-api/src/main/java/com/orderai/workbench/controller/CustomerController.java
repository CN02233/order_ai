package com.orderai.workbench.controller;

import com.orderai.workbench.service.CustomerService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.CustomerResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.dto.request.CreateCustomerRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
  private final CustomerService service;

  /**
   * Initializes this component with its required dependencies.
   * @param s input used by this operation
   */
  public CustomerController(CustomerService s) {
    service = s;
  }

  @PostMapping
  /** Creates a customer record from the supplied request body. */
  public ApiResponse<CustomerResponse> create(@RequestBody CreateCustomerRequest request) {
    return ApiResponse.success(service.create(request));
  }

  @GetMapping("/by-email/{email}")
  /**
   * Finds and returns the requested resource.
   * @param email input used by this operation
   * @return the result of this operation
   */
  public ApiResponse<CustomerResponse> find(@PathVariable String email) {
    Optional<CustomerResponse> customer = service.find(email);
    return customer
        .map(ApiResponse::success)
        .orElseGet(() -> ApiResponse.failure("Customer not found"));
  }

  @GetMapping("/by-email/{email}/orders")
  /**
   * Lists orders associated with a customer.
   * @param email input used by this operation
   * @return the result of this operation
   */
  public ApiResponse<List<OrderResponse>> orders(@PathVariable String email) {
    Optional<List<OrderResponse>> customerOrders = service.orders(email);
    return customerOrders
        .map(ApiResponse::success)
        .orElseGet(() -> ApiResponse.failure("Customer not found"));
  }
}
