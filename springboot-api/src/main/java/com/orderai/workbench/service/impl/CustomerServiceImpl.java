package com.orderai.workbench.service.impl;

import com.orderai.workbench.dto.request.CreateCustomerRequest;
import com.orderai.workbench.dto.response.CustomerResponse;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.entity.Customer;

import com.orderai.workbench.repository.CustomerRepository;
import com.orderai.workbench.repository.OrderRepository;
import com.orderai.workbench.service.CustomerService;
import com.orderai.workbench.service.support.OrderViewAssembler;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerServiceImpl implements CustomerService {
  private final CustomerRepository customers;
  private final OrderRepository orders;
  private final OrderViewAssembler views;

  public CustomerServiceImpl(CustomerRepository customers, OrderRepository orders, OrderViewAssembler views) {
    this.customers = customers;
    this.orders = orders;
    this.views = views;
  }

  /** Creates a customer after validating and normalizing the supplied fields. */
  @Override
  @Transactional
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

  /** Returns a bounded customer list for order-form dropdowns. */
  @Override
  public List<CustomerResponse> list(int limit) {
    int size = Math.max(1, Math.min(limit, 500));
    return customers.findAll(PageRequest.of(0, size, Sort.by("id")))
        .stream()
        .map(customer -> new CustomerResponse(customer.id, customer.email, customer.name))
        .toList();
  }

  @Override
  public Optional<CustomerResponse> find(String email) {
    return customers.findByEmailIgnoreCase(email.trim())
        .map(customer -> new CustomerResponse(customer.id, customer.email, customer.name));
  }

  /** Lists orders associated with a customer, or an empty optional when the email is unknown. */
  @Override
  public Optional<List<OrderResponse>> orders(String email) {
    Optional<Customer> customer =
        customers.findByEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT));
    if (customer.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        orders.findByCustomerId(customer.get().id).stream()
            .map(order -> views.toDetail(order, customer.get().email))
            .toList());
  }

  /** Deletes a customer that never ordered; an order must keep its customer so its history stays intact. */
  @Override
  @Transactional
  public void delete(Long id) {
    Customer customer = customers.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
    if (orders.existsByCustomerId(id)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Customers with orders cannot be deleted; delete those orders first");
    }
    customers.delete(customer);
  }
}
