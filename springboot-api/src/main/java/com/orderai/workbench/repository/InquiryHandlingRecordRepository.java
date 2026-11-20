package com.orderai.workbench.repository;

import com.orderai.workbench.entity.InquiryHandlingRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryHandlingRecordRepository
    extends JpaRepository<InquiryHandlingRecord, Long> {
  /** EN: Handling timeline, oldest first. 中文：人工处理时间线，按时间升序。 */
  List<InquiryHandlingRecord> findByInquiry_IdOrderByCreatedAtAsc(Long inquiryId);
}
