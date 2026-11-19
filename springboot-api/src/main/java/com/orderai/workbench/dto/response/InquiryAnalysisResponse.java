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
 *
 * <p>EN: confidence and needsHumanReview are nullable so a missing value can be rejected instead of
 *     being silently replaced by 0/false. suggestedReply is the only field that may stay null.
 * 中文：confidence 与 needsHumanReview 使用可空类型，缺失时可直接拒绝，不会被静默替换成 0/false。
 *     suggestedReply 是唯一允许为 null 的字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InquiryAnalysisResponse(
    String category,
    Double confidence,
    @JsonProperty("needs_human_review") Boolean needsHumanReview,
    @JsonProperty("suggested_reply") String suggestedReply,
    @JsonProperty("analysis_source") String analysisSource,
    @JsonProperty("error_message") String errorMessage) {}
