package com.orderai.workbench.service;

import com.orderai.workbench.dto.response.CustomerResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.dto.request.CreateCustomerRequest;
import java.util.List;
import java.util.Optional;

public interface CustomerService {
  CustomerResponse create(CreateCustomerRequest request);

  Optional<CustomerResponse> find(String email);

  Optional<List<OrderResponse>> orders(String email);
}
