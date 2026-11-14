package com.orderai.workbench.controller;

import com.orderai.workbench.service.RefundService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.RefundResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.orderai.workbench.repository.RefundRequestRepository;
import com.orderai.workbench.entity.RefundRequest;

@RestController
@RequestMapping("/api/refund-requests")
public class RefundController {
  private final RefundService service;
  private final RefundRequestRepository refunds;

  /**
   * Initializes this component with its required dependencies.
   * @param s input used by this operation
   */
  public RefundController(RefundService s, RefundRequestRepository refunds) {
    service = s;
    this.refunds = refunds;
  }

  @PostMapping
  /**
   * Creates a business record while honoring the idempotency key.
   * @param String input used by this operation
   * @param p input used by this operation
   * @param key input used by this operation
   * @return the result of this operation
   */
  public ApiResponse<RefundResponse> create(
      @RequestBody Map<String, String> p, @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.success(service.create(p, key));
  }

  @GetMapping
  /**
   * Returns refund requests in reverse creation order.
   * @return the result of this operation
   */
  public ApiResponse<Page<RefundRequest>> list(Pageable pageable) {
    return ApiResponse.success(refunds.findAll(pageable));
  }

  @PostMapping("/{id}/approve")
  /**
   * Marks a pending refund request as manually approved.
   * @param id input used by this operation
   * @return the result of this operation
   */
  public ApiResponse<RefundResponse> approve(@PathVariable long id) {
    return ApiResponse.success(service.approve(id));
  }

  @PutMapping("/{id}")
  public ApiResponse<RefundResponse> update(@PathVariable long id, @RequestBody Map<String,String> payload) {
    return ApiResponse.success(service.update(id, payload));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable long id) { service.delete(id); return ApiResponse.success(null); }
}
