package com.orderai.workbench.service;

import com.orderai.workbench.domain.AnalysisFieldApplier;
import com.orderai.workbench.dto.response.InquiryAnalysisResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.entity.InquiryAnalysis;
import com.orderai.workbench.repository.InquiryAnalysisRepository;
import com.orderai.workbench.repository.InquiryRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * EN: Owns the DB side of one analysis attempt. The three steps (begin -> remote Python -> complete)
 * are deliberately NOT wrapped in a single transaction: begin and complete are short REQUIRES_NEW
 * transactions so no database lock or transaction is held during the HTTP call. begin locks the
 * inquiry row with PESSIMISTIC_WRITE while assigning the next version, so two concurrent reanalyses
 * can never receive the same version. completion re-locks the inquiry and uses version guards so a
 * late older attempt cannot overwrite a newer current result nor a newer analysis_status.
 *
 * <p>中文：负责一次分析尝试的数据库部分。begin -> 远程 Python -> complete 三步刻意不放在同一个事务中：begin 与
 * complete 是短小的 REQUIRES_NEW 事务，HTTP 调用期间不持有任何数据库事务或锁。begin 在分配版本号时用
 * PESSIMISTIC_WRITE 锁定咨询行，因此并发重新分析绝不会拿到相同版本。complete 再次加锁并用版本守卫，使迟到的旧版本
 * 无法覆盖更新后的当前结果或 analysis_status。
 */
@Service
public class AnalysisRunService {
  static final String STATUS_PROCESSING = "PROCESSING";
  static final String STATUS_SUCCESS = "SUCCESS";
  static final String STATUS_FAILED = "FAILED";

  private final InquiryRepository inquiries;
  private final InquiryAnalysisRepository analyses;
  // EN: A disabled instance keeps the legacy unit-test seam intact (no history writes).
  // 中文：禁用实例保留旧单元测试接缝（不写分析历史）。
  private final boolean enabled;

  @Autowired
  public AnalysisRunService(InquiryRepository inquiries, InquiryAnalysisRepository analyses) {
    this.inquiries = inquiries;
    this.analyses = analyses;
    this.enabled = true;
  }

  private AnalysisRunService() {
    this.inquiries = null;
    this.analyses = null;
    this.enabled = false;
  }

  /** EN: No-op seam for constructor-based unit tests. 中文：供基于构造器的单元测试使用的无操作接缝。 */
  public static AnalysisRunService disabled() {
    return new AnalysisRunService();
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** EN: Identity of a freshly created analysis attempt. 中文：新建分析尝试的标识。 */
  public record AnalysisAttempt(long analysisId, int version) {}

  /**
   * EN: Short locked transaction: allocate maxVersion+1, append a PROCESSING history row and mark
   * the inquiry as PROCESSING. The lock is released on commit, before any HTTP call.
   *
   * <p>中文：短加锁事务：分配 maxVersion+1，追加 PROCESSING 历史行并把咨询标记为 PROCESSING。锁在提交时释放，
   * 早于任何 HTTP 调用。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AnalysisAttempt begin(long inquiryId) {
    if (!enabled) {
      return null;
    }
    Inquiry inquiry = lockedInquiry(inquiryId);
    Integer max = analyses.findMaxVersionByInquiryId(inquiryId);
    int next = (max == null ? 0 : max) + 1;

    InquiryAnalysis row = new InquiryAnalysis();
    row.inquiry = inquiry;
    row.version = next;
    row.status = STATUS_PROCESSING;
    InquiryAnalysis saved = analyses.saveAndFlush(row);

    inquiry.analysisStatus = STATUS_PROCESSING;
    inquiries.saveAndFlush(inquiry);
    return new AnalysisAttempt(saved.id, next);
  }

  /**
   * EN: Short locked transaction that finalizes a SUCCESS attempt. Writes the AI result into the
   * history row, then applies source-protected fields and advances current_analysis_id only when
   * this version beats the current one; analysis_status changes only when this attempt is the newest
   * one. A late older success therefore cannot desynchronize the current fields from the pointer.
   * Any DB failure rolls back both writes (no half-success state).
   *
   * <p>中文：完成 SUCCESS 尝试的短加锁事务。把 AI 结果写入历史行，仅当本次版本高于当前版本时才按来源保护规则更新咨询
   * 并推进 current_analysis_id；仅当本次是最新尝试时更新 analysis_status。因此迟到的旧成功不会让当前字段与指针脱节。
   * 任何数据库失败都会同时回滚两处写入（不产生半成功数据）。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public InquiryAnalysis completeSuccess(
      long inquiryId, AnalysisAttempt attempt, InquiryAnalysisResponse analysis) {
    if (!enabled) {
      return null;
    }
    Inquiry inquiry = lockedInquiry(inquiryId);
    InquiryAnalysis row = loadAttempt(attempt);

    row.category = analysis.category();
    row.priority = analysis.priority();
    row.confidence = analysis.confidence();
    row.needsHumanReview = analysis.needsHumanReview();
    row.suggestedReply = analysis.suggestedReply();
    row.analysisSource = analysis.analysisSource();
    row.status = STATUS_SUCCESS;
    row.errorMessage = null;
    row.completedAt = LocalDateTime.now();
    analyses.saveAndFlush(row);

    // EN: Only a winner that can advance the current pointer may write the current inquiry fields;
    // otherwise a late older SUCCESS would leave ai_category/category/priority inconsistent with
    // current_analysis_id. 中文：只有能推进当前指针的尝试才可写入当前咨询字段；否则迟到的旧 SUCCESS 会造成
    // ai_category/category/priority 与 current_analysis_id 不一致。
    if (attempt.version() > currentVersion(inquiry)) {
      AnalysisFieldApplier.apply(inquiry, analysis);
      inquiry.currentAnalysisId = row.id;
    }
    if (attempt.version() >= maxVersion(inquiryId)) {
      inquiry.analysisStatus = STATUS_SUCCESS;
    }
    inquiries.saveAndFlush(inquiry);
    return row;
  }

  /**
   * EN: Short locked transaction that finalizes a FAILED attempt. A failed attempt keeps its history
   * but never changes current_analysis_id or the current AI result. analysis_status moves to FAILED
   * only when this attempt is the newest one; a late older failure cannot overwrite a newer status.
   *
   * <p>中文：完成 FAILED 尝试的短加锁事务。失败尝试只保留历史，绝不改动 current_analysis_id 或当前分析结果。
   * 仅当本次是最新尝试时 analysis_status 才变为 FAILED；迟到的旧失败不能覆盖较新的状态。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public InquiryAnalysis completeFailure(long inquiryId, AnalysisAttempt attempt, String safeCode) {
    if (!enabled) {
      return null;
    }
    Inquiry inquiry = lockedInquiry(inquiryId);
    InquiryAnalysis row = loadAttempt(attempt);

    row.status = STATUS_FAILED;
    row.errorMessage = safeCode;
    row.completedAt = LocalDateTime.now();
    analyses.saveAndFlush(row);

    if (attempt.version() >= maxVersion(inquiryId)) {
      inquiry.analysisStatus = STATUS_FAILED;
    }
    inquiries.saveAndFlush(inquiry);
    return row;
  }

  /** EN: Complete timeline, oldest first. 中文：完整时间线，按版本升序。 */
  public List<InquiryAnalysis> history(long inquiryId) {
    return analyses.findByInquiry_IdOrderByVersionAsc(inquiryId);
  }

  private Inquiry lockedInquiry(long inquiryId) {
    return inquiries
        .findByIdForUpdate(inquiryId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inquiry not found"));
  }

  private InquiryAnalysis loadAttempt(AnalysisAttempt attempt) {
    return analyses
        .findById(attempt.analysisId())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "Analysis attempt no longer exists"));
  }

  private int maxVersion(long inquiryId) {
    Integer max = analyses.findMaxVersionByInquiryId(inquiryId);
    return max == null ? 0 : max;
  }

  /** EN: Version of the analysis the inquiry currently points at; 0 when it has none. 中文：咨询当前指向的分析版本，无则 0。 */
  private int currentVersion(Inquiry inquiry) {
    if (inquiry.currentAnalysisId == null) {
      return 0;
    }
    return analyses
        .findById(inquiry.currentAnalysisId)
        .map(row -> row.version == null ? 0 : row.version)
        .orElse(0);
  }
}
