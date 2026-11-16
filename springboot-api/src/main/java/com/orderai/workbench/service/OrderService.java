package com.orderai.workbench.service;

import com.orderai.workbench.dto.request.OrderRequest;
import com.orderai.workbench.dto.request.OrderUpdateRequest;
import com.orderai.workbench.dto.response.OrderManagementResponse;
import com.orderai.workbench.dto.response.OrderResponse;

public interface OrderService {
  OrderResponse find(String number);

  OrderManagementResponse create(OrderRequest request);

  OrderManagementResponse update(String number, OrderUpdateRequest request);

  void delete(String number);
}
