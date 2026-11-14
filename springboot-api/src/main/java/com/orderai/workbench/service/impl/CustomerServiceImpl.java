package com.orderai.workbench.service.impl;

import com.orderai.workbench.entity.Customer;
import com.orderai.workbench.dto.response.CustomerResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.dto.request.CreateCustomerRequest;

import com.orderai.workbench.repository.CustomerRepository;
import com.orderai.workbench.repository.OrderRepository;
import com.orderai.workbench.service.CustomerService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerServiceImpl implements CustomerService {
  private final CustomerRepository customers;
  private final OrderRepository orders;

  /**
   * Initializes this component with its required dependencies.
   * @param customers input used by this operation
   * @param orders input used by this operation
   */
  public CustomerServiceImpl(CustomerRepository customers, OrderRepository orders) {
    this.customers = customers;
    this.orders = orders;
  }

  @Override
  @Transactional
  /** Creates a customer after validating and normalizing the supplied fields. */
  public CustomerResponse create(CreateCustomerRequest request) {
    String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
    String name = request.name() == null ? "" : request.name().trim();
    if (email.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name and email are required");
    }
    if (customers.findByEmailIgnoreCase(email).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Customer already exists");
    }

    Customer saved = customers.saveAndFlush(new Customer(email, name));
    return new CustomerResponse(saved.id, saved.email, saved.name);
  }

  @Override
  /**
   * Finds and returns the requested resource.
   * @param email input used by this operation
   * @return the result of this operation
   */
  public Optional<CustomerResponse> find(String email) {
    return customers.findByEmailIgnoreCase(email.trim())
        .map(customer -> new CustomerResponse(customer.id, customer.email, customer.name));
  }

  @Override
  /**
   * Lists orders associated with a customer.
   * @param email input used by this operation
   * @return the result of this operation
   */
  public Optional<List<OrderResponse>> orders(String email) {
    Optional<Customer> customer =
        customers.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT));
    if (customer.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        orders.findByCustomerId(customer.get().id).stream()
            .map(
                order ->
                    new OrderResponse(
                        order.id,
                        order.orderNumber,
                        order.status,
                        order.trackingNumber == null ? "" : order.trackingNumber,
                        null,
                        order.createdAt))
            .toList());
  }
}
