package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * EN: Partial manual-handling request. All fields are optional, but at least one must carry a real
 * value after trimming. Unknown properties are ignored so a client cannot modify AI analysis fields.
 * handled_by is a client-supplied identifier, NOT an authenticated user identity: this project has
 * no authentication layer yet.
 *
 * <p>中文：人工处理部分更新请求。所有字段均可空，但至少需要一个去除空白后仍有值的字段。未知属性一律忽略，
 * 客户端不能借机修改 AI 分析字段。handled_by 只是客户端提供的处理人标识，并非经过认证的用户身份：
 * 当前项目尚无认证体系。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InquiryHandlingUpdateRequest(
    String category,
    String priority,
    @JsonProperty("handled_by") String handledBy,
    String note) {}
