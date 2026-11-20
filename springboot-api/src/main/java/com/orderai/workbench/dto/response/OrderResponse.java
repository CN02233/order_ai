package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Order detail: header, price-snapshotted lines, the parcels carrying them and every refund request
 * raised against the order.
 *
 * <p>{@code refundableAmount} equals {@code totalAmount} minus the requests that are still pending
 * review or already approved, so a second request can never exceed what is left.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
    Long id,
    @JsonProperty("order_number") String orderNumber,
    @JsonProperty("customer_id") Long customerId,
    @JsonProperty("customer_email") String customerEmail,
    String status,
    @JsonProperty("tracking_number") String trackingNumber,
    @JsonProperty("stock_reserved") boolean stockReserved,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("total_amount") BigDecimal totalAmount,
    @JsonProperty("pending_refund_amount") BigDecimal pendingRefundAmount,
    @JsonProperty("refunded_amount") BigDecimal refundedAmount,
    @JsonProperty("refundable_amount") BigDecimal refundableAmount,
    @JsonProperty("shipment_status") String shipmentStatus,
    List<OrderItemResponse> items,
    List<ShipmentSummaryResponse> shipments,
    List<RefundManagementResponse> refunds) {}
