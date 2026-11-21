package com.orderai.workbench.service;

import com.orderai.workbench.dto.request.InquiryHandlingUpdateRequest;
import com.orderai.workbench.dto.request.InquiryUpdateRequest;
import com.orderai.workbench.dto.response.AnalysisHistoryItem;
import com.orderai.workbench.dto.response.InquiryHandlingResponse;
import com.orderai.workbench.dto.response.InquiryManagementResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import com.orderai.workbench.dto.response.InquiryStatusResponse;
import com.orderai.workbench.dto.response.ReanalyzeResponse;
import java.util.List;
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

  /**
   * EN: Re-run AI analysis for an existing inquiry using the same pipeline as first analysis,
   * appending a new history version. Never changes the business status or human-owned fields.
   * 中文：对已有咨询重新执行 AI 分析，与首次分析共用同一流水线，追加新历史版本；绝不改动业务状态或人工维护字段。
   */
  ReanalyzeResponse reanalyze(long id);

  /**
   * EN: Full analysis timeline for an inquiry, oldest first, with is_current derived from the
   * inquiry's current_analysis_id. 中文：咨询的完整分析时间线，按版本升序，is_current 由 current_analysis_id 推导。
   */
  List<AnalysisHistoryItem> listAnalyses(long id);
}
