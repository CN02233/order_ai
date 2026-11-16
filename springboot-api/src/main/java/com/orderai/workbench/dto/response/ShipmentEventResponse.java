package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/** One scan on the parcel's journey. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentEventResponse(
    Long id,
    String status,
    String location,
    String description,
    @JsonProperty("occurred_at") LocalDateTime occurredAt,
    @JsonProperty("created_at") LocalDateTime createdAt) {}
