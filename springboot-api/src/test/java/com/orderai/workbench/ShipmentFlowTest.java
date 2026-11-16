package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderai.workbench.dto.request.OrderUpdateRequest;
import com.orderai.workbench.dto.request.ShipmentCreateRequest;
import com.orderai.workbench.dto.request.ShipmentEventRequest;
import com.orderai.workbench.dto.request.ShipmentItemRequest;
import com.orderai.workbench.dto.request.ShipmentUpdateRequest;
import com.orderai.workbench.dto.response.OrderResponse;
import com.orderai.workbench.dto.response.ShipmentResponse;
import com.orderai.workbench.service.OrderService;
import com.orderai.workbench.service.ShipmentService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logistics behaviour: a parcel drives the order status, never the stock.
 *
 * <p>Stock stays at the level reserved by checkout, because shipping moves goods the order already
 * paid for out of the warehouse — reversing it here would double count.
 */
class ShipmentFlowTest extends OrderAiTestSupport {
  @Autowired OrderService orderService;
  @Autowired ShipmentService shipmentService;

  private ShipmentCreateRequest shipment(String orderNumber, String tracking, List<ShipmentItemRequest> items) {
    return new ShipmentCreateRequest(orderNumber, "SF Express", tracking, LocalDate.now().plusDays(2),
        "Test Receiver", "handle with care", items);
  }

  /** Status-only update: carrier, dates and note stay untouched. */
  private static ShipmentUpdateRequest statusOnly(String status) {
    return new ShipmentUpdateRequest(null, null, status, null, null, null, null, null);
  }

  /** Status update that also appends one tracking event. */
  private static ShipmentUpdateRequest withEvent(String status, String location, String description) {
    return new ShipmentUpdateRequest(null, null, status, null, null, null, location, description);
  }

  @Test
  @DisplayName("creating a parcel ships the whole order and records the first tracking event")
  void createShipsWholeOrder() {
    orderService.create(orderRequest("ORD-S-1", 3));
    assertThat(reloaded().stock).isEqualTo(7);

    ShipmentResponse parcel = shipmentService.create(shipment("ORD-S-1", "SF-0001", List.of()));

    assertThat(parcel.status()).isEqualTo("CREATED");
    assertThat(parcel.items()).hasSize(1);
    assertThat(parcel.items().get(0).quantity()).isEqualTo(3);
    assertThat(parcel.items().get(0).sku()).isEqualTo(SKU);
    assertThat(parcel.events()).hasSize(1);
    assertThat(parcel.events().get(0).status()).isEqualTo("CREATED");

    OrderResponse detail = orderService.find("ORD-S-1");
    assertThat(detail.status()).isEqualTo("SHIPPED");
    assertThat(detail.trackingNumber()).isEqualTo("SF-0001");
    assertThat(detail.shipments()).hasSize(1);
    // Shipping does not touch stock again.
    assertThat(reloaded().stock).isEqualTo(7);
  }

  @Test
  @DisplayName("the order is only delivered once every unit shipped and every parcel arrived")
  void deliveredOnlyWhenEverythingArrived() {
    orderService.create(orderRequest("ORD-S-2", 4));
    long lineId = orderService.find("ORD-S-2").items().get(0).id();

    ShipmentResponse first = shipmentService.create(
        shipment("ORD-S-2", "SF-0002", List.of(new ShipmentItemRequest(lineId, 1))));
    // Three units are still in the warehouse, so the order has not shipped yet.
    assertThat(orderService.find("ORD-S-2").status()).isEqualTo("PROCESSING");

    ShipmentResponse second = shipmentService.create(
        shipment("ORD-S-2", "SF-0003", List.of(new ShipmentItemRequest(lineId, 3))));
    assertThat(second.items().get(0).quantity()).isEqualTo(3);
    // Every unit is now on a parcel, so the order flips to shipped.
    assertThat(orderService.find("ORD-S-2").status()).isEqualTo("SHIPPED");

    shipmentService.update(first.id(), statusOnly("DELIVERED"));
    assertThat(orderService.find("ORD-S-2").status()).isEqualTo("SHIPPED");

    shipmentService.update(second.id(), statusOnly("DELIVERED"));
    OrderResponse detail = orderService.find("ORD-S-2");
    assertThat(detail.status()).isEqualTo("DELIVERED");
    assertThat(detail.shipmentStatus()).isEqualTo("DELIVERED");
    assertThat(detail.items().get(0).shippedQuantity()).isEqualTo(4);
  }

  @Test
  @DisplayName("a parcel can never carry more units than the order line still has left")
  void overShipmentIsRejected() {
    orderService.create(orderRequest("ORD-S-3", 5));
    long lineId = orderService.find("ORD-S-3").items().get(0).id();

    shipmentService.create(shipment("ORD-S-3", "SF-0004", List.of(new ShipmentItemRequest(lineId, 2))));

    assertThatThrownBy(() -> shipmentService.create(
        shipment("ORD-S-3", "SF-0005", List.of(new ShipmentItemRequest(lineId, 4)))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("exceeds the unshipped quantity");

    // The rejected parcel wrote nothing.
    assertThat(shipmentService.findByOrder("ORD-S-3")).hasSize(1);

    // A partially shipped order stays processing, otherwise the second parcel could never be created.
    assertThat(orderService.find("ORD-S-3").status()).isEqualTo("PROCESSING");

    shipmentService.create(shipment("ORD-S-3", "SF-0006", List.of(new ShipmentItemRequest(lineId, 3))));
    assertThat(shipmentService.findByOrder("ORD-S-3")).hasSize(2);
    assertThat(orderService.find("ORD-S-3").status()).isEqualTo("SHIPPED");

    assertThatThrownBy(() -> shipmentService.create(shipment("ORD-S-3", "SF-0007", List.of())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("already been shipped");
  }

  @Test
  @DisplayName("parcel status only moves forward and delivery is terminal")
  void parcelStatusIsForwardOnly() {
    orderService.create(orderRequest("ORD-S-4", 1));
    ShipmentResponse parcel = shipmentService.create(shipment("ORD-S-4", "SF-0008", List.of()));

    shipmentService.update(parcel.id(),
        withEvent("PICKED_UP", "Shanghai", "Picked up"));

    assertThatThrownBy(() -> shipmentService.update(parcel.id(),
        statusOnly("CREATED")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("cannot change");

    shipmentService.update(parcel.id(),
        withEvent("DELIVERED", "Hangzhou", "Signed by customer"));

    assertThatThrownBy(() -> shipmentService.update(parcel.id(),
        statusOnly("IN_TRANSIT")))
        .isInstanceOf(ResponseStatusException.class);

    ShipmentResponse reloaded = shipmentService.find(parcel.id());
    assertThat(reloaded.status()).isEqualTo("DELIVERED");
    assertThat(reloaded.deliveredAt()).isNotNull();
    assertThat(reloaded.events()).hasSize(3);
    assertThat(reloaded.events().get(2).location()).isEqualTo("Hangzhou");
  }

  @Test
  @DisplayName("a plain tracking scan is stored without changing the status")
  void scanWithoutStatusChange() {
    orderService.create(orderRequest("ORD-S-5", 1));
    ShipmentResponse parcel = shipmentService.create(shipment("ORD-S-5", "SF-0009", List.of()));

    ShipmentResponse updated = shipmentService.addEvent(parcel.id(),
        new ShipmentEventRequest(null, "Suzhou hub", "Arrived at sorting centre", null));

    assertThat(updated.status()).isEqualTo("CREATED");
    assertThat(updated.events()).hasSize(2);
    assertThat(updated.events().get(1).location()).isEqualTo("Suzhou hub");
  }

  @Test
  @DisplayName("deleting the last parcel rolls the order back to processing without touching stock")
  void deleteLastParcelRollsBackOrder() {
    orderService.create(orderRequest("ORD-S-6", 2));
    ShipmentResponse parcel = shipmentService.create(shipment("ORD-S-6", "SF-0010", List.of()));
    assertThat(orderService.find("ORD-S-6").status()).isEqualTo("SHIPPED");

    shipmentService.delete(parcel.id());

    OrderResponse detail = orderService.find("ORD-S-6");
    assertThat(detail.status()).isEqualTo("PROCESSING");
    assertThat(detail.trackingNumber()).isEmpty();
    assertThat(detail.shipments()).isEmpty();
    assertThat(reloaded().stock).isEqualTo(8);

    // With no parcel left the order can be cancelled again and returns its stock.
    orderService.update("ORD-S-6", new OrderUpdateRequest("CANCELLED", null));
    assertThat(reloaded().stock).isEqualTo(10);
  }

  @Test
  @DisplayName("a delivered parcel stays as a delivery record")
  void deliveredParcelCannotBeDeleted() {
    orderService.create(orderRequest("ORD-S-7", 1));
    ShipmentResponse parcel = shipmentService.create(shipment("ORD-S-7", "SF-0011", List.of()));
    shipmentService.update(parcel.id(), statusOnly("DELIVERED"));

    assertThatThrownBy(() -> shipmentService.delete(parcel.id()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("delivery records");
  }

  @Test
  @DisplayName("deleting an order removes its parcels and their timeline")
  void deleteOrderCascadesShipments() {
    orderService.create(orderRequest("ORD-S-8", 2));
    ShipmentResponse parcel = shipmentService.create(shipment("ORD-S-8", "SF-0012", List.of()));

    orderService.delete("ORD-S-8");

    assertThat(shipments.findAllByOrderIdOrderByIdAsc(parcel.orderId())).isEmpty();
    assertThat(shipmentItems.findAllByShipmentId(parcel.id())).isEmpty();
    assertThat(shipmentEvents.findAllByShipmentIdOrderByOccurredAtAscIdAsc(parcel.id())).isEmpty();
    assertThat(reloaded().stock).isEqualTo(10);
  }

  @Test
  @DisplayName("a cancelled order cannot be shipped and a duplicate tracking number is refused")
  void cancelledOrderAndDuplicateTracking() {
    orderService.create(orderRequest("ORD-S-9", 1));
    orderService.create(orderRequest("ORD-S-10", 1));
    shipmentService.create(shipment("ORD-S-9", "SF-0013", List.of()));

    assertThatThrownBy(() -> shipmentService.create(shipment("ORD-S-10", "SF-0013", List.of())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Tracking number already exists");

    orderService.update("ORD-S-10", new OrderUpdateRequest("CANCELLED", null));
    assertThatThrownBy(() -> shipmentService.create(shipment("ORD-S-10", "SF-0014", List.of())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Cancelled orders cannot be shipped");
  }
}
