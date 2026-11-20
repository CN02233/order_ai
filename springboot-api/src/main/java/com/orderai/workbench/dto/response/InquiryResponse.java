package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * EN: Complete result of classifying and saving an inquiry, also returned on idempotency replay.
 * suggested_reply is nullable; error_message is internal and never exposed here. analysis_status
 * and analysis_error are response-only (not persisted): they distinguish "inquiry created, AI
 * analysis failed" from a full success without adding a database column in T1. The values are
 * fixed safe codes, never raw exception text.
 * 中文：咨询分类与保存的完整结果，幂等重放时同样返回此结构。suggested_reply 可空；error_message 属于内部信息，绝不在此暴露。
 * analysis_status 与 analysis_error 仅存在于响应（不落库）：在 T1 不加数据库列的前提下，明确区分“咨询已创建但 AI
 * 分析失败”与完整成功。取值为固定安全码，绝不回传原始异常文本。
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
    @JsonProperty("analysis_status") String analysisStatus,
    @JsonProperty("analysis_error") String analysisError,
    Boolean replayed) {}
