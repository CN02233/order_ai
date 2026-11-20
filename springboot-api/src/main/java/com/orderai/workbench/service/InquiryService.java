package com.orderai.workbench.service;

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
}
