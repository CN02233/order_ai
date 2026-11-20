package com.orderai.workbench.repository;

import com.orderai.workbench.entity.RefundRequest;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRequestRepository
    extends JpaRepository<RefundRequest, Long>, JpaSpecificationExecutor<RefundRequest> {
  Optional<RefundRequest> findByIdempotencyKey(String key);

  List<RefundRequest> findAllByOrderByIdDesc();

  List<RefundRequest> findAllByOrderNumberOrderByIdDesc(String orderNumber);

  /**
   * Amount already committed against the order: pending reviews and approved requests both block
   * the same money, so a rejected request correctly frees it again.
   */
  @Query(
      "select coalesce(sum(r.amount),0) from RefundRequest r "
          + "where r.orderNumber=:number and r.status in ('PENDING_MANUAL_APPROVAL','APPROVED_MANUALLY')")
  BigDecimal sumReservedAmountByOrder(@Param("number") String number);

  @Query(
      "select coalesce(sum(r.amount),0) from RefundRequest r "
          + "where r.orderNumber=:number and r.status=:status")
  BigDecimal sumAmountByOrderAndStatus(
      @Param("number") String number, @Param("status") String status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RefundRequest r where r.id=:id")
  Optional<RefundRequest> findByIdForUpdate(@Param("id") Long id);
}
