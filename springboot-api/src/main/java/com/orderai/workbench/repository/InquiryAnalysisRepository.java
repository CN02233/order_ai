package com.orderai.workbench.repository;

import com.orderai.workbench.entity.InquiryAnalysis;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryAnalysisRepository extends JpaRepository<InquiryAnalysis, Long> {
  /** EN: Find one analysis version of an inquiry. 中文：查询某咨询的一个分析版本。 */
  Optional<InquiryAnalysis> findByInquiry_IdAndVersion(Long inquiryId, Integer version);

  /** EN: Full analysis timeline, oldest first. 中文：完整分析时间线，按版本升序。 */
  List<InquiryAnalysis> findByInquiry_IdOrderByVersionAsc(Long inquiryId);
}
