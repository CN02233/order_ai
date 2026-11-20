package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderai.workbench.dto.request.OrderUpdateRequest;
import com.orderai.workbench.dto.request.RefundCreateRequest;
import com.orderai.workbench.dto.request.RefundReceiveLineRequest;
import com.orderai.workbench.dto.request.RefundReceiveRequest;
import com.orderai.workbench.dto.request.RefundReturnLineRequest;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.dto.response.RefundResponse;
import com.orderai.workbench.repository.OrderItemRepository;
import com.orderai.workbench.service.OrderService;
import com.orderai.workbench.service.RefundService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** Refund workflow: cumulative limits, manual review, and stock only moving on real receipt. */
class RefundFlowTest extends OrderAiTestSupport {
  @Autowired OrderService orderService;
  @Autowired RefundService refundService;
  @Autowired OrderItemRepository orderItems;

  long itemId;

  @BeforeEach
  void placeShippedOrder() {
    orderService.create(orderRequest("ORD-R-1", 2)); // 2 * 12.99 = 25.98
    orderService.update("ORD-R-1", new OrderUpdateRequest("SHIPPED", "TRK-R"));
    itemId = orderItems.findAllByOrderIdOrderById(reloadedOrder("ORD-R-1").id).get(0).id;
  }

  private RefundCreateRequest refund(
      String amount, String type, List<RefundReturnLineRequest> lines) {
    return new RefundCreateRequest("ORD-R-1", "damaged", new BigDecimal(amount), type, lines);
  }

  @Test
  @DisplayName("a refund request may not exceed the refundable amount")
  void cannotExceedRefundable() {
    assertThatThrownBy(() -> refundService.create(refund("25.99", "REFUND_ONLY", null), "k-over"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("remaining refundable amount");
  }

  @Test
  @DisplayName("a pending request reserves money and a rejection releases it again")
  void pendingReservesAndRejectFrees() {
    RefundResponse first = refundService.create(refund("20.00", "REFUND_ONLY", null), "k-1");
    assertThat(first.status()).isEqualTo("PENDING_MANUAL_APPROVAL");

    OrderResponse afterPending = orderService.find("ORD-R-1");
    assertMoney("20.00", afterPending.pendingRefundAmount());
    assertMoney("5.98", afterPending.refundableAmount());
    assertThat(afterPending.refunds()).hasSize(1);

    assertThatThrownBy(() -> refundService.create(refund("6.00", "REFUND_ONLY", null), "k-2"))
        .isInstanceOf(ResponseStatusException.class);

    RefundResponse rejected = refundService.reject(first.id());
    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.reviewedAt()).isNotNull();

    OrderResponse afterReject = orderService.find("ORD-R-1");
    assertMoney("0.00", afterReject.pendingRefundAmount());
    assertMoney("25.98", afterReject.refundableAmount());

    RefundResponse second = refundService.create(refund("25.98", "REFUND_ONLY", null), "k-3");
    assertThat(second.amount()).isEqualByComparingTo("25.98");
  }

  @Test
  @DisplayName("return quantities cannot exceed what was bought, across separate requests")
  void returnQuantityLimitedAcrossRequests() {
    assertThatThrownBy(
            () ->
                refundService.create(
                    refund(
                        "10.00",
                        "RETURN_AND_REFUND",
                        List.of(new RefundReturnLineRequest(itemId, 5))),
                    "k-many"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("still returnable");

    refundService.create(
        refund("12.99", "RETURN_AND_REFUND", List.of(new RefundReturnLineRequest(itemId, 1))),
        "k-one");

    assertThatThrownBy(
            () ->
                refundService.create(
                    refund(
                        "6.00",
                        "RETURN_AND_REFUND",
                        List.of(new RefundReturnLineRequest(itemId, 2))),
                    "k-two"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("approval changes status only; stock moves when goods are received")
  void approvalDoesNotTouchStockButReceiptDoes() {
    RefundResponse request =
        refundService.create(
            refund("12.99", "RETURN_AND_REFUND", List.of(new RefundReturnLineRequest(itemId, 1))),
            "k-approve");
    assertThat(request.returnItems()).hasSize(1);
    assertThat(request.returnItems().get(0).acceptedQuantity()).isZero();
    assertThat(request.order()).isNotNull();
    assertThat(request.order().orderNumber()).isEqualTo("ORD-R-1");

    RefundResponse approved = refundService.approve(request.id());
    assertThat(approved.status()).isEqualTo("APPROVED_MANUALLY");
    assertThat(approved.inventoryReceived()).isFalse();
    assertThat(reloaded().stock).isEqualTo(8); // untouched by approval

    RefundResponse received =
        refundService.receive(
            request.id(),
            new RefundReceiveRequest(List.of(new RefundReceiveLineRequest(itemId, 1))));
    assertThat(received.inventoryReceived()).isTrue();
    assertThat(received.returnItems().get(0).acceptedQuantity()).isEqualTo(1);
    assertThat(reloaded().stock).isEqualTo(9);

    assertThatThrownBy(
            () ->
                refundService.receive(
                    request.id(),
                    new RefundReceiveRequest(List.of(new RefundReceiveLineRequest(itemId, 1)))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("already received");
    assertThat(reloaded().stock).isEqualTo(9);
  }

  @Test
  @DisplayName(
      "an approved return cannot be received before approval, and only for return-and-refund")
  void receiptRequiresApproval() {
    RefundResponse only = refundService.create(refund("12.99", "REFUND_ONLY", null), "k-only");
    assertThat(only.returnItems()).isEmpty();

    assertThatThrownBy(
            () ->
                refundService.receive(
                    only.id(),
                    new RefundReceiveRequest(List.of(new RefundReceiveLineRequest(itemId, 1)))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Only approved return-and-refund");
  }

  @Test
  @DisplayName("only processing, shipped and delivered orders can be refunded")
  void cancelledOrderCannotBeRefunded() {
    orderService.create(orderRequest("ORD-R-2", 1));
    orderService.update("ORD-R-2", new OrderUpdateRequest("CANCELLED", null));

    assertThatThrownBy(
            () ->
                refundService.create(
                    new RefundCreateRequest(
                        "ORD-R-2", "late", new BigDecimal("12.99"), "REFUND_ONLY", null),
                    "k-cancel"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("processing, shipped or delivered");
  }

  @Test
  @DisplayName("replaying an idempotency key returns the original request instead of a duplicate")
  void idempotentReplay() {
    RefundResponse first = refundService.create(refund("12.99", "REFUND_ONLY", null), "same-key");
    RefundResponse replay = refundService.create(refund("12.99", "REFUND_ONLY", null), "same-key");

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.replayed()).isTrue();
    assertThat(orderService.find("ORD-R-1").refunds()).hasSize(1);
  }

  @Test
  @DisplayName("a rejected request does not block cancelling the order any more")
  void rejectedRefundDoesNotBlockCancellation() {
    orderService.create(orderRequest("ORD-R-3", 1));
    RefundResponse request =
        refundService.create(
            new RefundCreateRequest(
                "ORD-R-3", "changed mind", new BigDecimal("12.99"), "REFUND_ONLY", null),
            "k-c3");

    assertThatThrownBy(
            () -> orderService.update("ORD-R-3", new OrderUpdateRequest("CANCELLED", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("refund requests");

    refundService.reject(request.id());
    orderService.update("ORD-R-3", new OrderUpdateRequest("CANCELLED", null));
    assertThat(reloadedOrder("ORD-R-3").status).isEqualTo("CANCELLED");
    assertThat(reloaded().stock).isEqualTo(8); // 10 - 2 for ORD-R-1, -1 for ORD-R-3, +1 on cancel
  }
}
