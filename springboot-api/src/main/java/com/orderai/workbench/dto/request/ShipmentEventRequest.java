package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * Adds one scan to a parcel's timeline.
 *
 * <p>{@code status} is optional; when it is present and differs from the current status it is applied
 * with the same transition rules as an update, so a scan can move the parcel forward.
 */
public record ShipmentEventRequest(
    String status,
    String location,
    String description,
    @JsonProperty("occurred_at") LocalDateTime occurredAt) {}
