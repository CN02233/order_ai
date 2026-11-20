package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * EN: Only business fields are editable. Unknown properties (for example a client-supplied
 * ai_category or suggested_reply) are ignored so the AI snapshot can never be overwritten.
 * 中文：只有业务字段可编辑。未知属性（例如客户端传入的 ai_category 或 suggested_reply）一律忽略， 保证 AI 分析快照永远不会被覆盖。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InquiryUpdateRequest(
    @JsonProperty("customer_email") String customerEmail,
    String subject,
    String body,
    String category) {}
