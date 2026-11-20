package com.orderai.workbench.controller;

import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.dto.request.ShipmentCreateRequest;
import com.orderai.workbench.dto.request.ShipmentEventRequest;
import com.orderai.workbench.dto.request.ShipmentUpdateRequest;
import com.orderai.workbench.dto.response.ShipmentResponse;
import com.orderai.workbench.dto.response.ShipmentSummaryResponse;
import com.orderai.workbench.entity.Shipment;
import com.orderai.workbench.repository.ShipmentRepository;
import com.orderai.workbench.service.ShipmentService;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {
  private final ShipmentService service;
  private final ShipmentRepository shipments;

  public ShipmentController(ShipmentService service, ShipmentRepository shipments) {
    this.service = service;
    this.shipments = shipments;
  }

  @GetMapping
  public ApiResponse<Page<ShipmentSummaryResponse>> list(
      @RequestParam(required = false) String order_number,
      @RequestParam(required = false) String tracking_number,
      @RequestParam(required = false) String carrier,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime created_from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          LocalDateTime created_to,
      @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    if (created_from != null && created_to != null && created_from.isAfter(created_to)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "created_from must be before created_to");
    }
    Specification<Shipment> spec = (root, query, cb) -> cb.conjunction();
    if (order_number != null && !order_number.isBlank()) {
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.like(cb.lower(root.get("orderNumber")), order_number.toLowerCase() + "%"));
    }
    if (tracking_number != null && !tracking_number.isBlank()) {
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.like(
                      cb.lower(root.get("trackingNumber")),
                      "%" + tracking_number.toLowerCase() + "%"));
    }
    if (carrier != null && !carrier.isBlank()) {
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.like(cb.lower(root.get("carrier")), "%" + carrier.toLowerCase() + "%"));
    }
    if (status != null && !status.isBlank()) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
    }
    if (created_from != null) {
      spec =
          spec.and(
              (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), created_from));
    }
    if (created_to != null) {
      spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), created_to));
    }
    return ApiResponse.success(shipments.findAll(spec, pageable).map(service::toSummary));
  }

  @GetMapping("/{id}")
  public ApiResponse<ShipmentResponse> find(@PathVariable Long id) {
    return ApiResponse.success(service.find(id));
  }

  @PostMapping
  public ApiResponse<ShipmentResponse> create(@RequestBody ShipmentCreateRequest request) {
    return ApiResponse.success(service.create(request));
  }

  @PutMapping("/{id}")
  public ApiResponse<ShipmentResponse> update(
      @PathVariable Long id, @RequestBody ShipmentUpdateRequest request) {
    return ApiResponse.success(service.update(id, request));
  }

  /** Appends one scan without necessarily changing the parcel status. */
  @PostMapping("/{id}/events")
  public ApiResponse<ShipmentResponse> addEvent(
      @PathVariable Long id, @RequestBody ShipmentEventRequest request) {
    return ApiResponse.success(service.addEvent(id, request));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    service.delete(id);
    return ApiResponse.success(null);
  }
}
