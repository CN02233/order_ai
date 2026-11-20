package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderai.workbench.dto.request.OrderItemCreateRequest;
import com.orderai.workbench.dto.request.OrderRequest;
import com.orderai.workbench.entity.Customer;
import com.orderai.workbench.entity.OrderEntity;
import com.orderai.workbench.entity.Product;
import com.orderai.workbench.repository.CustomerRepository;
import com.orderai.workbench.repository.OrderItemRepository;
import com.orderai.workbench.repository.OrderRepository;
import com.orderai.workbench.repository.ProductRepository;
import com.orderai.workbench.repository.RefundRequestRepository;
import com.orderai.workbench.repository.RefundReturnItemRepository;
import com.orderai.workbench.repository.ShipmentEventRepository;
import com.orderai.workbench.repository.ShipmentItemRepository;
import com.orderai.workbench.repository.ShipmentRepository;
import com.orderai.workbench.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Shared fixture on a real (H2) database.
 *
 * <p>Deliberately not transactional: every service call commits, so rollback behaviour, row locks
 * and "nothing was written" assertions are tested the same way they behave in production.
 */
@SpringBootTest
@ActiveProfiles("test")
abstract class OrderAiTestSupport {
  static final String SKU = "TEST-MUG";
  static final BigDecimal PRICE = new BigDecimal("12.99");

  @Autowired CustomerRepository customers;
  @Autowired ProductRepository products;
  @Autowired OrderRepository orders;
  @Autowired OrderItemRepository orderItems;
  @Autowired RefundRequestRepository refunds;
  @Autowired RefundReturnItemRepository returnItems;
  @Autowired StockMovementRepository movements;
  @Autowired ShipmentRepository shipments;
  @Autowired ShipmentItemRepository shipmentItems;
  @Autowired ShipmentEventRepository shipmentEvents;

  Customer customer;
  Product product;

  @BeforeEach
  void resetAndSeed() {
    shipmentEvents.deleteAllInBatch();
    shipmentItems.deleteAllInBatch();
    shipments.deleteAllInBatch();
    returnItems.deleteAllInBatch();
    refunds.deleteAllInBatch();
    orderItems.deleteAllInBatch();
    movements.deleteAllInBatch();
    orders.deleteAllInBatch();
    products.deleteAllInBatch();
    customers.deleteAllInBatch();

    customer = customers.saveAndFlush(new Customer("customer@example.com", "Test Customer"));
    product = new Product();
    product.sku = SKU;
    product.name = "Test Mug";
    product.stock = 10;
    product.price = PRICE;
    product = products.saveAndFlush(product);
  }

  OrderRequest orderRequest(String number, int quantity) {
    return new OrderRequest(
        number, customer.id, null, null, List.of(new OrderItemCreateRequest(SKU, quantity)));
  }

  Product reloaded() {
    return products.findBySku(SKU).orElseThrow();
  }

  OrderEntity reloadedOrder(String number) {
    return orders.findByOrderNumber(number).orElseThrow();
  }

  static void assertMoney(String expected, BigDecimal actual) {
    assertThat(actual).isEqualByComparingTo(expected);
  }
}
