package com.orderai.workbench.controller;

import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.request.InquiryCreateRequest;
import com.orderai.workbench.dto.request.InquiryHandlingUpdateRequest;
import com.orderai.workbench.dto.request.InquiryStatusUpdateRequest;
import com.orderai.workbench.dto.request.InquiryUpdateRequest;
import com.orderai.workbench.dto.response.InquiryHandlingResponse;
import com.orderai.workbench.dto.response.InquiryManagementResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import com.orderai.workbench.dto.response.InquiryStatusResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inquiries")
public class InquiryController {
  private final InquiryService service;
  private final InquiryRepository inquiries;

  public InquiryController(InquiryService service, InquiryRepository inquiries) {
    this.service = service;
    this.inquiries = inquiries;
  }

  /** Newest first, with every filter optional. */
  @GetMapping
  public ApiResponse<Page<InquiryManagementResponse>> list(
      @RequestParam(name = "customer_email", required = false) String customerEmail,
      @RequestParam(required = false) String subject,
      @RequestParam(required = false) String category,
      @RequestParam(name = "created_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime createdFrom,
      @RequestParam(name = "created_to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime createdTo,
      @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "created_from must be before created_to");
    }

    Specification<Inquiry> spec = (root, query, cb) -> cb.conjunction();
    if (customerEmail != null && !customerEmail.isBlank()) {
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.like(
                      cb.lower(root.get("customerEmail")),
                      "%" + customerEmail.toLowerCase() + "%"));
    }
    if (subject != null && !subject.isBlank()) {
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.like(cb.lower(root.get("subject")), "%" + subject.toLowerCase() + "%"));
    }
    if (category != null && !category.isBlank()) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category));
    }
    if (createdFrom != null) {
      spec =
          spec.and(
              (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
    }
    if (createdTo != null) {
      spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), createdTo));
    }

    return ApiResponse.success(inquiries.findAll(spec, pageable).map(InquiryController::toView));
  }

  /**
   * EN: Full detail view of one inquiry, including the immutable AI analysis snapshot.
   * 中文：单条咨询的完整详情，包含不可人工修改的 AI 分析快照。
   */
  @GetMapping("/{id}")
  public ApiResponse<InquiryManagementResponse> detail(@PathVariable long id) {
    Inquiry inquiry =
        inquiries
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inquiry not found"));
    return ApiResponse.success(toView(inquiry));
  }

  /**
   * EN: Partial update of business fields only. The request DTO deliberately has no AI analysis
   * fields, so ai_category and the analysis snapshot can never be changed here. 中文：只做业务字段的部分更新。请求
   * DTO 刻意不含 AI 分析字段，因此此处永远无法修改 ai_category 与分析快照。
   */
  @PutMapping("/{id}")
  public ApiResponse<InquiryManagementResponse> update(
      @PathVariable long id, @RequestBody InquiryUpdateRequest input) {
    // EN: The controller only binds and wraps. Category edits are forced through the service so the
    // old PUT path cannot bypass HUMAN source marking or handling history.
    // 中文：Controller 只负责绑定与包装。分类修改必须走 Service，旧 PUT 不能绕过 HUMAN 来源标记与处理历史。
    return ApiResponse.success(service.update(id, input));
  }

  /**
   * EN: Status transition. All rules and history writes live in the service; this method only binds
   * the request and wraps the result. 中文：状态流转。规则与历史写入都在 Service；此处只绑定请求并包装结果。
   */
  @PatchMapping("/{id}/status")
  public ApiResponse<InquiryStatusResponse> changeStatus(
      @PathVariable long id,
      @RequestBody(required = false) InquiryStatusUpdateRequest input) {
    // EN: required=false lets a literal JSON null reach this method as a null record. Reject it
    // here as 400 through the shared handler instead of letting input.status() throw a 500 NPE.
    // 中文：required=false 让 JSON 字面量 null 以 null 记录进入方法。在此显式拒绝并交给统一异常处理器返回
    // 400，避免 input.status() 抛出 NullPointerException 变成 500。
    if (input == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body must not be null");
    }
    return ApiResponse.success(service.changeStatus(id, input.status()));
  }

  /**
   * EN: Manual handling endpoint. All validation, source marking, optimistic locking and history
   * writes live in the service; this method only binds the request and wraps the result.
   *
   * <p>中文：人工处理接口。校验、来源标记、乐观锁与历史写入全部在 Service；此处只绑定请求并包装结果。
   */
  @PatchMapping("/{id}/handling")
  public ApiResponse<InquiryHandlingResponse> applyManualHandling(
      @PathVariable long id,
      @RequestBody(required = false) InquiryHandlingUpdateRequest input) {
    return ApiResponse.success(service.applyManualHandling(id, input));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable long id) {
    Inquiry inquiry =
        inquiries
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inquiry not found"));
    inquiries.delete(inquiry);
    return ApiResponse.success(null);
  }

  /**
   * Creates an inquiry from the dashboard form while honoring the idempotency key.
   *
   * <p>The raw request is handed over as a field map because the service classifies the text and
   * expects the same shape as the dashboard payload.
   */
  @PostMapping
  public ApiResponse<InquiryResponse> create(
      @RequestBody InquiryCreateRequest request, @RequestHeader("Idempotency-Key") String key) {
    Map<String, String> payload = new HashMap<>();
    payload.put("customer_email", request.customerEmail());
    payload.put("subject", request.subject());
    payload.put("body", request.body());
    return ApiResponse.success(service.create(payload, key));
  }

  private static InquiryManagementResponse toView(Inquiry inquiry) {
    return new InquiryManagementResponse(
        inquiry.id,
        inquiry.customerEmail,
        inquiry.subject,
        inquiry.body,
        inquiry.category,
        inquiry.aiCategory,
        inquiry.confidence,
        inquiry.needsHumanReview,
        inquiry.suggestedReply,
        inquiry.analysisSource,
        inquiry.createdAt);
  }
}
