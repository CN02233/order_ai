package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderai.workbench.dto.request.OrderUpdateRequest;
import com.orderai.workbench.dto.request.RefundCreateRequest;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.entity.StockMovement;
import com.orderai.workbench.repository.StockMovementRepository;
import com.orderai.workbench.service.OrderService;
import com.orderai.workbench.service.RefundService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** Order lifecycle: stock reservation on create, release on cancel, no illegal status jumps. */
class OrderLifecycleTest extends OrderAiTestSupport {
  @Autowired OrderService orderService;
  @Autowired RefundService refundService;
  @Autowired StockMovementRepository movements;

  @Test
  @DisplayName("creating an order reserves stock, snapshots the price and writes one SALE movement")
  void createReservesStock() {
    orderService.create(orderRequest("ORD-T-1", 2));

    assertThat(reloaded().stock).isEqualTo(8);

    OrderResponse detail = orderService.find("ORD-T-1");
    assertMoney("25.98", detail.totalAmount());
    assertMoney("12.99", detail.items().get(0).unitPrice());
    assertMoney("25.98", detail.refundableAmount());
    assertThat(detail.stockReserved()).isTrue();

    List<StockMovement> ledger = movements.findAll();
    assertThat(ledger).hasSize(1);
    assertThat(ledger.get(0).movementType).isEqualTo("SALE");
    assertThat(ledger.get(0).delta).isEqualTo(-2);
    assertThat(ledger.get(0).balanceAfter).isEqualTo(8);
    assertThat(ledger.get(0).reference).isEqualTo("ORD-T-1");
  }

  @Test
  @DisplayName("a later price change never rewrites an existing order's amount")
  void priceChangeDoesNotRewriteHistory() {
    orderService.create(orderRequest("ORD-T-2", 1));

    product = reloaded();
    product.price = new BigDecimal("99.00");
    products.saveAndFlush(product);

    OrderResponse detail = orderService.find("ORD-T-2");
    assertMoney("12.99", detail.totalAmount());
    assertMoney("12.99", detail.items().get(0).unitPrice());
  }

  @Test
  @DisplayName("insufficient stock fails the whole checkout and leaves stock untouched")
  void insufficientStockRollsBack() {
    assertThatThrownBy(() -> orderService.create(orderRequest("ORD-T-3", 11)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Insufficient stock");

    assertThat(reloaded().stock).isEqualTo(10);
    assertThat(orders.findByOrderNumber("ORD-T-3")).isEmpty();
    assertThat(movements.findAll()).isEmpty();
  }

  @Test
  @DisplayName("cancelling returns stock exactly once and keeps the order closed")
  void cancelReleasesStockOnce() {
    orderService.create(orderRequest("ORD-T-4", 3));
    assertThat(reloaded().stock).isEqualTo(7);

    orderService.update("ORD-T-4", new OrderUpdateRequest("CANCELLED", null));
    assertThat(reloaded().stock).isEqualTo(10);
    assertThat(reloadedOrder("ORD-T-4").stockReserved).isFalse();

    orderService.update("ORD-T-4", new OrderUpdateRequest("CANCELLED", null));
    assertThat(reloaded().stock).isEqualTo(10);

    assertThatThrownBy(() -> orderService.update("ORD-T-4", new OrderUpdateRequest("SHIPPED", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("cannot change");
    assertThat(reloaded().stock).isEqualTo(10);
  }

  @Test
  @DisplayName("the status can only move forward one step at a time")
  void statusMachineIsForwardOnly() {
    orderService.create(orderRequest("ORD-T-5", 1));

    assertThatThrownBy(() -> orderService.update("ORD-T-5", new OrderUpdateRequest("DELIVERED", null)))
        .isInstanceOf(ResponseStatusException.class);

    orderService.update("ORD-T-5", new OrderUpdateRequest("SHIPPED", "TRK-1"));
    orderService.update("ORD-T-5", new OrderUpdateRequest("DELIVERED", null));
    assertThat(reloadedOrder("ORD-T-5").status).isEqualTo("DELIVERED");

    assertThatThrownBy(() -> orderService.update("ORD-T-5", new OrderUpdateRequest("CANCELLED", null)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("deleting a reserved order releases stock, removes child rows and keeps the ledger")
  void deleteReleasesStockAndCascades() {
    orderService.create(orderRequest("ORD-T-DEL", 4));
    assertThat(reloaded().stock).isEqualTo(6);
    long orderId = reloadedOrder("ORD-T-DEL").id;
    assertThat(orderItems.findAllByOrderIdOrderById(orderId)).hasSize(1);

    orderService.delete("ORD-T-DEL");

    assertThat(orders.findByOrderNumber("ORD-T-DEL")).isEmpty();
    assertThat(orderItems.findAllByOrderIdOrderById(orderId)).isEmpty();
    assertThat(refunds.findAllByOrderNumberOrderByIdDesc("ORD-T-DEL")).isEmpty();
    // Stock came back, and both the SALE and the release remain in the audit ledger.
    assertThat(reloaded().stock).isEqualTo(10);
    List<StockMovement> ledger = movements.findAll();
    assertThat(ledger).extracting(m -> m.movementType).containsExactly("SALE", "ORDER_CANCELLED");
    assertThat(ledger).extracting(m -> m.delta).containsExactly(-4, 4);
  }

  @Test
  @DisplayName("an order with an open refund cannot be deleted")
  void deleteBlockedByOpenRefund() {
    orderService.create(orderRequest("ORD-T-DEL-R", 2));
    // A pending refund reserves money, so the order must stay for audit.
    refundService.create(new RefundCreateRequest(
        "ORD-T-DEL-R", "damaged", new BigDecimal("5.00"), "REFUND_ONLY", List.of()), "k-del-block");

    assertThatThrownBy(() -> orderService.delete("ORD-T-DEL-R"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("refund requests");

    assertThat(orders.findByOrderNumber("ORD-T-DEL-R")).isPresent();
    assertThat(reloaded().stock).isEqualTo(8);
  }

  @Test
  @DisplayName("deleting a missing order reports 404")
  void deleteMissingOrder() {
    assertThatThrownBy(() -> orderService.delete("ORD-DOES-NOT-EXIST"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Order not found");
  }
}
