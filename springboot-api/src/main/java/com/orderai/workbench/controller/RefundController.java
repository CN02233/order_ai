package com.orderai.workbench.controller;

import com.orderai.workbench.service.RefundService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.RefundResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/refund-requests")
public class RefundController {
  private final RefundService service;

  /**
   * Initializes this component with its required dependencies.
   * @param s input used by this operation
   */
  public RefundController(RefundService s) {
    service = s;
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
  public ApiResponse<List<RefundResponse>> list() {
    return ApiResponse.success(service.list());
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
}
