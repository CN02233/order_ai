package com.orderai.workbench.service;

import com.orderai.workbench.dto.response.OrderResponse;

public interface OrderService {
  OrderResponse find(String number);
}
