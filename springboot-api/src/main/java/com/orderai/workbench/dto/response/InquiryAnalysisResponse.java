package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Represents the structured response returned by the Python analysis service. */
public record InquiryAnalysisResponse(
    String category,
    double confidence,
    @JsonProperty("needs_human_review") boolean needsHumanReview,
    @JsonProperty("suggested_reply") String suggestedReply) {}
