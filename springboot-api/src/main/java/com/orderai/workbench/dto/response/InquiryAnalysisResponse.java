package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents the structured response returned by the Python analysis service.
 *
 * <p>EN: The first four fields are the original contract; the last two mirror Python's new fields.
 * Unknown fields are ignored so a future Python addition never breaks Java deserialization.
 * 中文：前四个字段是原有契约，后两个对应 Python 新增字段；忽略未知字段，避免 Python 未来加字段导致
 * Java 反序列化失败。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InquiryAnalysisResponse(
    String category,
    double confidence,
    @JsonProperty("needs_human_review") boolean needsHumanReview,
    @JsonProperty("suggested_reply") String suggestedReply,
    @JsonProperty("analysis_source") String analysisSource,
    @JsonProperty("error_message") String errorMessage) {}
