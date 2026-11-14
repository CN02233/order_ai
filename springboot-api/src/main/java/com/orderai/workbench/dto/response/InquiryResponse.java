package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Represents the result of classifying and saving an inquiry. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InquiryResponse(
    @JsonProperty("id") Long inquiryId,
    String category,
    @JsonProperty("needs_human_review") Boolean needsHumanReview,
    Boolean replayed) {}
