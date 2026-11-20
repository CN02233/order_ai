package com.orderai.workbench.controller;

import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.request.RefundCreateRequest;
import com.orderai.workbench.dto.request.RefundReceiveRequest;
import com.orderai.workbench.dto.request.RefundUpdateRequest;
import com.orderai.workbench.dto.response.RefundManagementResponse;
import com.orderai.workbench.dto.response.RefundResponse;
import com.orderai.workbench.entity.RefundRequest;
import com.orderai.workbench.repository.RefundRequestRepository;
import com.orderai.workbench.service.RefundService;
import java.time.LocalDateTime;
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
@RequestMapping("/api/refund-requests")
public class RefundController {
  private final RefundService service;
  private final RefundRequestRepository refunds;

  public RefundController(RefundService service, RefundRequestRepository refunds) {
    this.service = service;
    this.refunds = refunds;
  }

  /**
   * Creates a refund request while honoring the idempotency key, so a retried submission returns
   * the original record instead of a duplicate.
   */
  @PostMapping
  public ApiResponse<RefundResponse> create(
      @RequestBody RefundCreateRequest request, @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.success(service.create(request, key));
  }

  /** Returns refund requests in reverse creation order, newest first. */
  @GetMapping
  public ApiResponse<Page<RefundManagementResponse>> list(
      @RequestParam(required = false) Long id,
      @RequestParam(name = "order_number", required = false) String orderNumber,
      @RequestParam(required = false) String status,
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

    Specification<RefundRequest> spec = (root, query, cb) -> cb.conjunction();
    if (id != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("id"), id));
    }
    if (orderNumber != null && !orderNumber.isBlank()) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("orderNumber"), orderNumber));
    }
    if (status != null && !status.isBlank()) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
    }
    if (createdFrom != null) {
      spec =
          spec.and(
              (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
    }
    if (createdTo != null) {
      spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), createdTo));
    }

    return ApiResponse.success(refunds.findAll(spec, pageable).map(RefundController::toView));
  }

  /** Refund detail with its order summary and requested return lines. */
  @GetMapping("/{id}")
  public ApiResponse<RefundResponse> find(@PathVariable long id) {
    return ApiResponse.success(service.find(id));
  }

  /** Marks a pending refund request as manually approved. */
  @PostMapping("/{id}/approve")
  public ApiResponse<RefundResponse> approve(@PathVariable long id) {
    return ApiResponse.success(service.approve(id));
  }

  /** Marks a pending refund request as rejected; no stock moves. */
  @PostMapping("/{id}/reject")
  public ApiResponse<RefundResponse> reject(@PathVariable long id) {
    return ApiResponse.success(service.reject(id));
  }

  /** Accepts returned goods: only the accepted quantity goes back into stock. */
  @PostMapping("/{id}/receive")
  public ApiResponse<RefundResponse> receive(
      @PathVariable long id, @RequestBody RefundReceiveRequest request) {
    return ApiResponse.success(service.receive(id, request));
  }

  @PutMapping("/{id}")
  public ApiResponse<RefundResponse> update(
      @PathVariable long id, @RequestBody RefundUpdateRequest payload) {
    return ApiResponse.success(service.update(id, payload.reason()));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable long id) {
    service.delete(id);
    return ApiResponse.success(null);
  }

  private static RefundManagementResponse toView(RefundRequest r) {
    return new RefundManagementResponse(
        r.id,
        r.orderNumber,
        r.reason,
        r.status,
        r.refundType,
        r.amount,
        r.inventoryReceived,
        r.createdAt,
        r.reviewedAt);
  }
}
