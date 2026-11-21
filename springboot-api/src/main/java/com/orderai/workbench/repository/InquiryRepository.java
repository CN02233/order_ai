package com.orderai.workbench.repository;

import com.orderai.workbench.entity.Inquiry;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryRepository
    extends JpaRepository<Inquiry, Long>, JpaSpecificationExecutor<Inquiry> {
  Optional<Inquiry> findByIdempotencyKey(String key);

  /**
   * EN: Lock the inquiry row for the short version-allocation transaction, so two concurrent
   * reanalyses cannot compute the same next version. The lock is released at transaction commit,
   * before the Python HTTP call. 中文：在短事务中锁定咨询行，使并发重新分析不会算出相同的新版本号；锁在事务提交时释放，
   * 早于 Python HTTP 调用。
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from Inquiry i where i.id = :id")
  Optional<Inquiry> findByIdForUpdate(@Param("id") Long id);
}
