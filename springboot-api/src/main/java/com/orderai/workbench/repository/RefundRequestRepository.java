package com.orderai.workbench.repository;

import com.orderai.workbench.entity.RefundRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {
  Optional<RefundRequest> findByIdempotencyKey(String key);

  List<RefundRequest> findAllByOrderByIdDesc();
}
