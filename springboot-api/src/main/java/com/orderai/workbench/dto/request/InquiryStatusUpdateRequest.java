package com.orderai.workbench.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * EN: Request body for the inquiry status transition endpoint. Only the target status is accepted;
 * unknown properties are ignored so a client cannot smuggle in business fields or handling data.
 * The value is validated against the five allowed statuses in the service layer (400 if invalid).
 *
 * <p>中文：咨询状态流转接口的请求体。只接受目标状态；未知属性一律忽略，客户端无法夹带业务字段或处理数据。
 * 值由 Service 层按五种合法状态校验（非法返回 400）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InquiryStatusUpdateRequest(@JsonProperty("status") String status) {}
