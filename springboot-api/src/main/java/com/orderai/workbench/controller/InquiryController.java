package com.orderai.workbench.controller;

import com.orderai.workbench.service.InquiryService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inquiries")
public class InquiryController {
  private final InquiryService service;

  /**
   * Initializes this component with its required dependencies.
   * @param s input used by this operation
   */
  public InquiryController(InquiryService s) {
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
  public ApiResponse<InquiryResponse> create(
      @RequestBody Map<String, String> p, @RequestHeader("Idempotency-Key") String key) {
    return ApiResponse.success(service.create(p, key));
  }
}
