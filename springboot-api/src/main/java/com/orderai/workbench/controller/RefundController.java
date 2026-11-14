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
import com.orderai.workbench.dto.response.RefundManagementResponse;
import com.orderai.workbench.dto.request.RefundUpdateRequest;
import com.orderai.workbench.dto.request.RefundCreateRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

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
      @RequestBody RefundCreateRequest request, @RequestHeader("Idempotency-Key") String key) {
    if(request.orderNumber()==null||request.orderNumber().isBlank()||request.reason()==null||request.reason().isBlank())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"order_number and reason are required");
    return ApiResponse.success(service.create(Map.of("order_number",request.orderNumber(),"reason",request.reason()), key));
  }

  @GetMapping
  /**
   * Returns refund requests in reverse creation order.
   * @return the result of this operation
   */
  public ApiResponse<Page<RefundManagementResponse>> list(@RequestParam(required=false) Long id,@RequestParam(required=false) String order_number,@RequestParam(required=false) String status,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime created_from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime created_to,@PageableDefault(size=10,sort="createdAt",direction=Sort.Direction.DESC) Pageable pageable) {
    if(created_from!=null&&created_to!=null&&created_from.isAfter(created_to))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"created_from must be before created_to");
    Specification<RefundRequest> spec=(r,q,cb)->cb.conjunction();if(id!=null)spec=spec.and((r,q,cb)->cb.equal(r.get("id"),id));if(order_number!=null&&!order_number.isBlank())spec=spec.and((r,q,cb)->cb.equal(r.get("orderNumber"),order_number));if(status!=null&&!status.isBlank())spec=spec.and((r,q,cb)->cb.equal(r.get("status"),status));if(created_from!=null)spec=spec.and((r,q,cb)->cb.greaterThanOrEqualTo(r.get("createdAt"),created_from));if(created_to!=null)spec=spec.and((r,q,cb)->cb.lessThanOrEqualTo(r.get("createdAt"),created_to));
    return ApiResponse.success(refunds.findAll(spec,pageable).map(r->new RefundManagementResponse(r.id,r.orderNumber,r.reason,r.status,r.createdAt)));
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
  public ApiResponse<RefundResponse> update(@PathVariable long id, @RequestBody RefundUpdateRequest payload) {
    return ApiResponse.success(service.update(id, payload.reason()));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable long id) { service.delete(id); return ApiResponse.success(null); }
}
