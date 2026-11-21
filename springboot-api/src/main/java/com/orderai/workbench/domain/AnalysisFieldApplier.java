package com.orderai.workbench.domain;

import com.orderai.workbench.dto.response.InquiryAnalysisResponse;
import com.orderai.workbench.entity.Inquiry;

/**
 * EN: Single place that decides which AI result fields may reach the current inquiry row. A HUMAN
 * source always wins over a later AI analysis; a RULE_BASED result has no priority evidence, so it
 * never writes or clears the stored priority. The AI snapshot (ai_category) always reflects the
 * latest analysis, independent of human edits to the business category.
 *
 * <p>中文：决定哪些 AI 结果字段可以写入当前咨询行的唯一入口。HUMAN 来源始终优先于后续 AI 分析；RULE_BASED 结果没有
 * 优先级证据，既不写入也不清空主表优先级。AI 快照（ai_category）始终反映最新分析，与人工修改的业务分类互不影响。
 */
public final class AnalysisFieldApplier {
  public static final String SOURCE_AI = "AI";
  public static final String SOURCE_HUMAN = "HUMAN";

  private AnalysisFieldApplier() {}

  /**
   * EN: Apply the analysis snapshot and any AI-owned business fields to the inquiry. Returns true
   * when at least one current business field (category/priority) changed source or value.
   *
   * <p>中文：把分析快照与 AI 可拥有的业务字段写入咨询；当任一当前业务字段（分类/优先级）的值或来源发生变化时返回 true。
   */
  public static boolean apply(Inquiry inquiry, InquiryAnalysisResponse analysis) {
    boolean changed = false;

    // EN: The AI snapshot is always refreshed, even when a human owns the business category.
    // 中文：AI 快照总是刷新，即使业务分类由人工维护。
    inquiry.aiCategory = analysis.category();
    inquiry.confidence = analysis.confidence();
    inquiry.needsHumanReview = analysis.needsHumanReview();
    inquiry.suggestedReply = analysis.suggestedReply();
    inquiry.analysisSource = analysis.analysisSource();

    if (!SOURCE_HUMAN.equals(inquiry.categorySource)) {
      if (!java.util.Objects.equals(inquiry.category, analysis.category())
          || !SOURCE_AI.equals(inquiry.categorySource)) {
        changed = true;
      }
      inquiry.category = analysis.category();
      inquiry.categorySource = SOURCE_AI;
    }

    // EN: Only an AI result with a real priority may set the current priority. A RULE_BASED result
    //     (priority == null) must leave a human or existing AI priority untouched.
    // 中文：只有带真实优先级的 AI 结果才能设置当前优先级。RULE_BASED 结果（priority 为 null）不得改动人工或既有 AI 优先级。
    if (analysis.priority() != null && !SOURCE_HUMAN.equals(inquiry.prioritySource)) {
      if (!java.util.Objects.equals(inquiry.priority, analysis.priority())
          || !SOURCE_AI.equals(inquiry.prioritySource)) {
        changed = true;
      }
      inquiry.priority = analysis.priority();
      inquiry.prioritySource = SOURCE_AI;
    }
    return changed;
  }
}
