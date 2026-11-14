package com.orderai.workbench.controller;

import com.orderai.workbench.service.InquiryService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.entity.Inquiry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@RestController
@RequestMapping("/api/inquiries")
public class InquiryController {
  private final InquiryService service;
  private final InquiryRepository inquiries;

  /**
   * Initializes this component with its required dependencies.
   * @param s input used by this operation
   */
  public InquiryController(InquiryService s, InquiryRepository inquiries) {
    service = s;
    this.inquiries = inquiries;
  }

  @GetMapping
  public ApiResponse<Page<Inquiry>> list(Pageable pageable) { return ApiResponse.success(inquiries.findAll(pageable)); }

  @PutMapping("/{id}")
  public ApiResponse<Inquiry> update(@PathVariable long id, @RequestBody Inquiry input) {
    Inquiry inquiry = inquiries.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Inquiry not found"));
    if(input.customerEmail!=null) inquiry.customerEmail=input.customerEmail;
    if(input.subject!=null) inquiry.subject=input.subject;
    if(input.body!=null) inquiry.body=input.body;
    if(input.category!=null) inquiry.category=input.category;
    return ApiResponse.success(inquiries.save(inquiry));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable long id) { inquiries.delete(inquiries.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Inquiry not found"))); return ApiResponse.success(null); }

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
