package com.orderai.workbench.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * EN: Result of one manual-handling call. {@code historyWritten} makes no-op requests observable,
 * while the source fields expose whether the current category/priority is protected from AI overwrite.
 *
 * <p>中文：一次人工处理的结果。historyWritten 让无变化请求可被识别；来源字段表明当前分类/优先级是否已被标记为
 * 人工维护，后续 AI 不得覆盖。
 */
public record InquiryHandlingResponse(
    long id,
    String category,
    @JsonProperty("category_source") String categorySource,
    String priority,
    @JsonProperty("priority_source") String prioritySource,
    @JsonProperty("handled_by") String handledBy,
    @JsonProperty("handled_at") LocalDateTime handledAt,
    @JsonProperty("handling_note") String handlingNote,
    long version,
    @JsonProperty("updated_at") LocalDateTime updatedAt,
    @JsonProperty("history_written") boolean historyWritten) {}
