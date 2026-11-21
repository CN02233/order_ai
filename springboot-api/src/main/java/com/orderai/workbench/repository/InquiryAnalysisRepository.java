package com.orderai.workbench.repository;

import com.orderai.workbench.entity.InquiryAnalysis;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryAnalysisRepository extends JpaRepository<InquiryAnalysis, Long> {
  /** EN: Find one analysis version of an inquiry. 中文：查询某咨询的一个分析版本。 */
  Optional<InquiryAnalysis> findByInquiry_IdAndVersion(Long inquiryId, Integer version);

  /** EN: Full analysis timeline, oldest first. 中文：完整分析时间线，按版本升序。 */
  List<InquiryAnalysis> findByInquiry_IdOrderByVersionAsc(Long inquiryId);

  /**
   * EN: Highest version already used by an inquiry; null when no analysis exists yet. Called while
   * the inquiry row is locked. 中文：某咨询已用的最大版本号；尚无分析时返回 null。在咨询行加锁期间调用。
   */
  @Query("select max(a.version) from InquiryAnalysis a where a.inquiry.id = :inquiryId")
  Integer findMaxVersionByInquiryId(@Param("inquiryId") Long inquiryId);
}
