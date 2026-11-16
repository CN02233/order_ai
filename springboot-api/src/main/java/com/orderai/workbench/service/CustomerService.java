package com.orderai.workbench.service;

import com.orderai.workbench.dto.request.CreateCustomerRequest;
import com.orderai.workbench.dto.response.CustomerResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import java.util.List;
import java.util.Optional;

public interface CustomerService {
  CustomerResponse create(CreateCustomerRequest request);

  /** Customers available for selection in order forms, capped so the dropdown stays manageable. */
  List<CustomerResponse> list(int limit);

  Optional<CustomerResponse> find(String email);

  Optional<List<OrderResponse>> orders(String email);
}
