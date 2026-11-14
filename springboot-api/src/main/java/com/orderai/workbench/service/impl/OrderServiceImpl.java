package com.orderai.workbench.service.impl;

import com.orderai.workbench.entity.Customer;
import com.orderai.workbench.entity.OrderEntity;
import com.orderai.workbench.dto.response.OrderResponse;

import com.orderai.workbench.repository.*;
import com.orderai.workbench.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderServiceImpl implements OrderService {
  private final OrderRepository orders;
  private final CustomerRepository customers;

  /**
   * Initializes this component with its required dependencies.
   * @param o input used by this operation
   * @param c input used by this operation
   */
  public OrderServiceImpl(OrderRepository o, CustomerRepository c) {
    orders = o;
    customers = c;
  }

  /**
   * Finds and returns the requested resource.
   * @param n input used by this operation
   * @return the result of this operation
   */
  public OrderResponse find(String n) {
    OrderEntity o =
        orders
            .findByOrderNumber(n)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    Customer c = customers.findById(o.customerId).orElseThrow();
    return new OrderResponse(
        o.orderNumber,
        o.status,
        o.trackingNumber == null ? "" : o.trackingNumber,
        c.email);
  }
}
