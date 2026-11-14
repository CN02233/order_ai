package com.orderai.workbench.service.impl;

import com.orderai.workbench.entity.RefundRequest;
import com.orderai.workbench.dto.response.RefundResponse;

import com.orderai.workbench.repository.*;
import com.orderai.workbench.service.RefundService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefundServiceImpl implements RefundService {
  private final RefundRequestRepository repo;
  private final OrderRepository orders;

  /**
   * Initializes this component with its required dependencies.
   * @param r input used by this operation
   * @param o input used by this operation
   */
  public RefundServiceImpl(RefundRequestRepository r, OrderRepository o) {
    repo = r;
    orders = o;
  }

  @Transactional
  /**
   * Creates a business record while honoring the idempotency key.
   * @param String input used by this operation
   * @param p input used by this operation
   * @param key input used by this operation
   * @return the result of this operation
   */
  public RefundResponse create(Map<String, String> p, String key) {
    // Idempotency protects the customer from duplicate requests after a retry.
    Optional<RefundRequest> old = repo.findByIdempotencyKey(key);
    if (old.isPresent()) {
      RefundRequest existing = old.get();
      return new RefundResponse(
          existing.id, existing.orderNumber, existing.reason, existing.status, true);
    }
    if (orders.findByOrderNumber(p.get("order_number")).isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
    // Creating a request never triggers a payment refund; approval remains a human action.
    RefundRequest r = repo.save(new RefundRequest(key, p.get("order_number"), p.get("reason")));
    return new RefundResponse(r.id, r.orderNumber, r.reason, r.status, false);
  }

  /**
   * Returns refund requests in reverse creation order.
   * @return the result of this operation
   */
  public List<RefundResponse> list() {
    return repo.findAllByOrderByIdDesc().stream()
        .map(
            r -> new RefundResponse(r.id, r.orderNumber, r.reason, r.status, null))
        .toList();
  }

  @Transactional
  /**
   * Marks a pending refund request as manually approved.
   * @param id input used by this operation
   * @return the result of this operation
   */
  public RefundResponse approve(long id) {
    // Only pending requests can transition to manually approved.
    RefundRequest r =
        repo.findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found"));
    if (!"PENDING_MANUAL_APPROVAL".equals(r.status))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund already processed");
    r.status = "APPROVED_MANUALLY";
    repo.save(r);
    return new RefundResponse(r.id, r.orderNumber, r.reason, r.status, null);
  }

  @Transactional
  public RefundResponse update(long id, String reason) {
    RefundRequest r = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found"));
    if ("PENDING_MANUAL_APPROVAL".equals(r.status) && reason != null) r.reason = reason;
    repo.save(r); return new RefundResponse(r.id, r.orderNumber, r.reason, r.status, null);
  }

  @Transactional
  public void delete(long id) { repo.delete(repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found"))); }
}
