package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * EN: Result of a status transition. previousStatus and currentStatus are equal for the idempotent
 * no-op case. updatedAt is read back from the persisted row so the client sees the exact value that
 * was stored in the same transaction.
 *
 * <p>中文：状态流转结果。幂等无操作时 previousStatus 与 currentStatus 相同。updatedAt 从持久化行读回，
 * 保证客户端看到的就是同一事务中写入的值。
 */
public record InquiryStatusResponse(
    Long id,
    @JsonProperty("previousStatus") String previousStatus,
    @JsonProperty("currentStatus") String currentStatus,
    @JsonProperty("updatedAt") LocalDateTime updatedAt) {}
