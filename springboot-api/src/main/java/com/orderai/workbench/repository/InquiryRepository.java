package com.orderai.workbench.repository;

import com.orderai.workbench.entity.Inquiry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {
  Optional<Inquiry> findByIdempotencyKey(String key);
}
