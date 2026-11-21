package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * EN: Result of one reanalysis attempt, whether it succeeded or failed. analysis_status reflects
 * THIS attempt, not necessarily the inquiry's current analysis; error_message is a fixed safe code.
 *
 * <p>中文：一次重新分析尝试的结果，无论成功或失败。analysis_status 描述本次尝试，不一定等于咨询当前分析；
 * error_message 为固定安全错误码。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReanalyzeResponse(
    @JsonProperty("inquiry_id") long inquiryId,
    @JsonProperty("analysis_id") long analysisId,
    @JsonProperty("analysis_version") int analysisVersion,
    @JsonProperty("analysis_status") String analysisStatus,
    String category,
    String priority,
    @JsonProperty("analysis_source") String analysisSource,
    @JsonProperty("error_message") String errorMessage,
    @JsonProperty("created_at") LocalDateTime createdAt,
    @JsonProperty("completed_at") LocalDateTime completedAt) {}
