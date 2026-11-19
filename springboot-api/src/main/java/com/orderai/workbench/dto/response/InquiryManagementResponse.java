package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * EN: Management list view. The four analysis fields are null for historical rows created before
 *     the analysis columns existed; error_message stays internal and is never exposed.
 * 中文：管理列表视图。分析字段在分析列出现之前创建的历史记录中为 null；
 *     error_message 属于内部信息，绝不暴露。
 */
public record InquiryManagementResponse(
    Long id,
    @JsonProperty("customer_email") String customerEmail,
    String subject,
    String body,
    String category,
    Double confidence,
    @JsonProperty("needs_human_review") Boolean needsHumanReview,
    @JsonProperty("suggested_reply") String suggestedReply,
    @JsonProperty("analysis_source") String analysisSource,
    @JsonProperty("created_at") LocalDateTime createdAt) {}
