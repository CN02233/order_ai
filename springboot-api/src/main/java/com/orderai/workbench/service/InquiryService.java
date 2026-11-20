package com.orderai.workbench.service;

import com.orderai.workbench.dto.request.InquiryHandlingUpdateRequest;
import com.orderai.workbench.dto.request.InquiryUpdateRequest;
import com.orderai.workbench.dto.response.InquiryHandlingResponse;
import com.orderai.workbench.dto.response.InquiryManagementResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import com.orderai.workbench.dto.response.InquiryStatusResponse;
import java.util.Map;

public interface InquiryService {
  InquiryResponse create(Map<String, String> payload, String key);

  /**
   * EN: Move an inquiry to a new status under the transition rules, writing one handling record in
   * the same transaction. 中文：按转换规则推进咨询状态，并在同一事务中写入一条处理历史。
   */
  InquiryStatusResponse changeStatus(long id, String targetStatus);

  /**
   * EN: Apply partial manual handling. Every real field change and its handling record are written
   * in one transaction; a no-op writes nothing. 中文：应用人工部分更新。每个真实字段变化与处理历史在同一事务写入；
   * 无变化时完全不写库。
   */
  InquiryHandlingResponse applyManualHandling(long id, InquiryHandlingUpdateRequest request);

  /**
   * EN: Legacy PUT path. Business fields stay compatible, but a category edit is forced through the
   * same HUMAN-source and history rules as the new endpoint. 中文：兼容旧 PUT。业务字段保持原行为，但分类修改
   * 必须遵守与新接口相同的 HUMAN 来源与历史规则。
   */
  InquiryManagementResponse update(long id, InquiryUpdateRequest request);
}
