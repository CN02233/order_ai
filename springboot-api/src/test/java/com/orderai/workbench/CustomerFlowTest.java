package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderai.workbench.dto.request.CreateCustomerRequest;
import com.orderai.workbench.dto.response.CustomerResponse;
import com.orderai.workbench.service.CustomerService;
import com.orderai.workbench.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** Customer deletion: only a customer with no order history may be removed. */
class CustomerFlowTest extends OrderAiTestSupport {
  @Autowired CustomerService customerService;
  @Autowired OrderService orderService;

  @Test
  @DisplayName("a customer without orders can be deleted")
  void deletesSpareCustomer() {
    CustomerResponse spare =
        customerService.create(new CreateCustomerRequest("Spare Person", "spare@example.com"));

    customerService.delete(spare.id());

    assertThat(customers.findById(spare.id())).isEmpty();
    assertThat(customers.findById(customer.id)).isPresent();
  }

  @Test
  @DisplayName("a customer with an order cannot be deleted")
  void refusesCustomerWithOrders() {
    orderService.create(orderRequest("ORD-T-DEL-1", 1));

    assertThatThrownBy(() -> customerService.delete(customer.id))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("cannot be deleted");

    assertThat(customers.findById(customer.id)).isPresent();
  }

  @Test
  @DisplayName("deleting an unknown customer reports not found")
  void refusesUnknownCustomer() {
    assertThatThrownBy(() -> customerService.delete(999_999L))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not found");
  }
}
