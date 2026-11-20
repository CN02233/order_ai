package com.orderai.workbench.repository;

import com.orderai.workbench.entity.Inquiry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InquiryRepository
    extends JpaRepository<Inquiry, Long>, JpaSpecificationExecutor<Inquiry> {
  Optional<Inquiry> findByIdempotencyKey(String key);
}
