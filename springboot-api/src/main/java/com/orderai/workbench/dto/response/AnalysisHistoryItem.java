package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * EN: One row of the analysis history timeline. is_current is derived from
 * inquiries.current_analysis_id, never from the highest version (the newest attempt may have failed).
 *
 * <p>中文：分析历史时间线中的一行。is_current 由 inquiries.current_analysis_id 推导，绝不使用最大版本号
 * （最新尝试可能失败）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisHistoryItem(
    long id,
    int version,
    String category,
    String priority,
    Double confidence,
    @JsonProperty("needs_human_review") Boolean needsHumanReview,
    @JsonProperty("analysis_reason") String analysisReason,
    @JsonProperty("suggested_reply") String suggestedReply,
    @JsonProperty("analysis_source") String analysisSource,
    String status,
    @JsonProperty("error_message") String errorMessage,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("completed_at") LocalDateTime completedAt,
    @JsonProperty("is_current") boolean current) {}
