package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * EN: Complete result of classifying and saving an inquiry, also returned on idempotency replay.
 *     suggested_reply is nullable; error_message is internal and never exposed here.
 * 中文：咨询分类与保存的完整结果，幂等重放时同样返回此结构。
 *     suggested_reply 可空；error_message 属于内部信息，绝不在此暴露。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InquiryResponse(
    @JsonProperty("id") Long inquiryId,
    String category,
    @JsonProperty("ai_category") String aiCategory,
    Double confidence,
    @JsonProperty("needs_human_review") Boolean needsHumanReview,
    @JsonProperty("suggested_reply") String suggestedReply,
    @JsonProperty("analysis_source") String analysisSource,
    Boolean replayed) {}
