package com.orderai.workbench.repository;

import com.orderai.workbench.entity.OrderItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
  List<OrderItem> findAllByOrderIdOrderById(Long orderId);

  Optional<OrderItem> findByIdAndOrderId(Long id, Long orderId);

  boolean existsByProductId(Long productId);

  /** Order lines that still point at this product; a product with any is not deletable. */
  long countByProductId(Long productId);

  /**
   * Reference counts for a whole page of products in one query, so the inventory list can disable the
   * delete button without an N+1 lookup.
   */
  @Query("select i.productId, count(i) from OrderItem i where i.productId in :productIds "
      + "group by i.productId")
  List<Object[]> countByProductIdIn(@Param("productIds") List<Long> productIds);

  /** Total units ordered, used to decide whether every unit of an order is on its way. */
  @Query("select coalesce(sum(i.quantity),0) from OrderItem i where i.orderId=:orderId")
  Long sumQuantityByOrder(@Param("orderId") Long orderId);

  /** Powers "which orders bought this product" on the inventory page. */
  @Query("select i from OrderItem i where i.productId=:productId order by i.id desc")
  List<OrderItem> findByProductIdOrderByIdDesc(@Param("productId") Long productId, Pageable pageable);
}
