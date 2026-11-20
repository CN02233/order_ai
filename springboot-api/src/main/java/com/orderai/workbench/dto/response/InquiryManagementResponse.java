package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.orderai.workbench.entity.Inquiry;
import java.time.LocalDateTime;

/**
 * EN: Management list view. The four analysis fields are null for historical rows created before
 * the analysis columns existed; error_message stays internal and is never exposed.
 * 中文：管理列表视图。分析字段在分析列出现之前创建的历史记录中为 null； error_message 属于内部信息，绝不暴露。
 */
public record InquiryManagementResponse(
    Long id,
    @JsonProperty("customer_email") String customerEmail,
    String subject,
    String body,
    String category,
    @JsonProperty("ai_category") String aiCategory,
    Double confidence,
    @JsonProperty("needs_human_review") Boolean needsHumanReview,
    @JsonProperty("suggested_reply") String suggestedReply,
    @JsonProperty("analysis_source") String analysisSource,
    @JsonProperty("created_at") LocalDateTime createdAt) {
  /**
   * EN: Single mapping used by both the controller and the service, so the old PUT path and the new
   * manual-handling path return exactly the same management view.
   *
   * <p>中文：Controller 与 Service 共用此映射，确保旧 PUT 与新人工处理接口返回完全相同的管理视图。
   */
  public static InquiryManagementResponse from(Inquiry inquiry) {
    return new InquiryManagementResponse(
        inquiry.id,
        inquiry.customerEmail,
        inquiry.subject,
        inquiry.body,
        inquiry.category,
        inquiry.aiCategory,
        inquiry.confidence,
        inquiry.needsHumanReview,
        inquiry.suggestedReply,
        inquiry.analysisSource,
        inquiry.createdAt);
  }
}
