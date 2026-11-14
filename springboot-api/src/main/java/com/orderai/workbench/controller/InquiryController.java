package com.orderai.workbench.controller;

import com.orderai.workbench.service.InquiryService;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.dto.response.InquiryManagementResponse;
import com.orderai.workbench.dto.request.InquiryUpdateRequest;
import com.orderai.workbench.dto.request.InquiryCreateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

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
  public ApiResponse<Page<InquiryManagementResponse>> list(@RequestParam(required=false) String customer_email,@RequestParam(required=false) String subject,@RequestParam(required=false) String category,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime created_from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime created_to,@PageableDefault(size=10,sort="createdAt",direction=Sort.Direction.DESC) Pageable pageable) {
    if(created_from!=null&&created_to!=null&&created_from.isAfter(created_to))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"created_from must be before created_to");
    Specification<Inquiry> spec=(r,q,cb)->cb.conjunction();
    if(customer_email!=null&&!customer_email.isBlank())spec=spec.and((r,q,cb)->cb.like(cb.lower(r.get("customerEmail")),"%"+customer_email.toLowerCase()+"%"));
    if(subject!=null&&!subject.isBlank())spec=spec.and((r,q,cb)->cb.like(cb.lower(r.get("subject")),"%"+subject.toLowerCase()+"%"));
    if(category!=null&&!category.isBlank())spec=spec.and((r,q,cb)->cb.equal(r.get("category"),category));
    if(created_from!=null)spec=spec.and((r,q,cb)->cb.greaterThanOrEqualTo(r.get("createdAt"),created_from));
    if(created_to!=null)spec=spec.and((r,q,cb)->cb.lessThanOrEqualTo(r.get("createdAt"),created_to));
    return ApiResponse.success(inquiries.findAll(spec,pageable).map(i->new InquiryManagementResponse(i.id,i.customerEmail,i.subject,i.body,i.category,i.createdAt)));
  }

  @PutMapping("/{id}")
  public ApiResponse<InquiryManagementResponse> update(@PathVariable long id, @RequestBody InquiryUpdateRequest input) {
    Inquiry inquiry = inquiries.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Inquiry not found"));
    if(input.customerEmail()!=null) inquiry.customerEmail=input.customerEmail();
    if(input.subject()!=null) inquiry.subject=input.subject();
    if(input.body()!=null) inquiry.body=input.body();
    if(input.category()!=null) inquiry.category=input.category();
    inquiry=inquiries.save(inquiry);return ApiResponse.success(new InquiryManagementResponse(inquiry.id,inquiry.customerEmail,inquiry.subject,inquiry.body,inquiry.category,inquiry.createdAt));
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
      @RequestBody InquiryCreateRequest request, @RequestHeader("Idempotency-Key") String key) {
    Map<String,String> payload=new java.util.HashMap<>();payload.put("customer_email",request.customerEmail());payload.put("subject",request.subject());payload.put("body",request.body());
    return ApiResponse.success(service.create(payload, key));
  }
}
