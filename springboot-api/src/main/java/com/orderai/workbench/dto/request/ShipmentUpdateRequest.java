package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

/**
 * Updates a parcel. Only non-null fields are applied.
 *
 * <p>{@code location}/{@code description} are optional and, when a status change happens, are stored
 * on the tracking event that the change produces.
 */
public record ShipmentUpdateRequest(
    String carrier,
    @JsonProperty("tracking_number") String trackingNumber,
    String status,
    @JsonProperty("estimated_delivery") LocalDate estimatedDelivery,
    @JsonProperty("receiver_name") String receiverName,
    String note,
    String location,
    String description) {}
