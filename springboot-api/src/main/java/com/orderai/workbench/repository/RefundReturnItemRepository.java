package com.orderai.workbench.repository;

import com.orderai.workbench.entity.RefundReturnItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundReturnItemRepository extends JpaRepository<RefundReturnItem, Long> {
  List<RefundReturnItem> findAllByRefundRequestId(Long refundRequestId);

  /** Quantity of one order line already accepted back into stock. */
  @Query(
      "select coalesce(sum(i.acceptedQuantity),0) from RefundReturnItem i where i.orderItemId=:orderItemId")
  Long sumAcceptedByOrderItem(@Param("orderItemId") Long orderItemId);

  /** Quantity of one order line already accepted back into stock, ignoring one refund request. */
  @Query(
      "select coalesce(sum(i.acceptedQuantity),0) from RefundReturnItem i "
          + "where i.orderItemId=:orderItemId and i.refundRequestId<>:refundId")
  Long sumAcceptedByOrderItemExcludingRefund(
      @Param("orderItemId") Long orderItemId, @Param("refundId") Long refundId);

  /**
   * Quantity of one order line already claimed by other refund requests that are not rejected.
   * Stops a customer from returning five units of a line they only bought three of, spread across
   * several requests.
   */
  @Query(
      "select coalesce(sum(i.quantity),0) from RefundReturnItem i, RefundRequest r "
          + "where r.id=i.refundRequestId and i.orderItemId=:orderItemId "
          + "and i.refundRequestId<>:refundId and r.status<>'REJECTED'")
  Long sumRequestedByOrderItemExcludingRefund(
      @Param("orderItemId") Long orderItemId, @Param("refundId") Long refundId);
}
