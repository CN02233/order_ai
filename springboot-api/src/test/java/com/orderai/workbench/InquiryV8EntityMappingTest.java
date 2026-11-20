package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.entity.InquiryAnalysis;
import com.orderai.workbench.entity.InquiryHandlingRecord;
import com.orderai.workbench.repository.InquiryAnalysisRepository;
import com.orderai.workbench.repository.InquiryHandlingRecordRepository;
import com.orderai.workbench.repository.InquiryRepository;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * EN: Verifies the V8 JPA mappings against a real H2 database (Hibernate-generated DDL), including
 * the new state columns, the two history tables, their repositories and the optimistic lock.
 *
 * <p>中文：针对真实 H2 数据库（Hibernate 生成的 DDL）验证 V8 JPA 映射，包括新增状态字段、两张历史表、
 * 对应 Repository 与乐观锁。
 */
@SpringBootTest
@ActiveProfiles("test")
class InquiryV8EntityMappingTest {
  @Autowired InquiryRepository inquiries;
  @Autowired InquiryAnalysisRepository analyses;
  @Autowired InquiryHandlingRecordRepository handlingRecords;
  @Autowired JdbcTemplate jdbc;

  private String key;
  private Long inquiryId;

  @BeforeEach
  void setUp() {
    key = "v8-entity-" + UUID.randomUUID();
  }

  @AfterEach
  void cleanUpOnlyThisTestsRows() {
    // EN: Delete only rows owned by this test; never clear whole tables.
    // 中文：只删除本测试创建的行，绝不清空整表。
    if (inquiryId != null) {
      jdbc.update("DELETE FROM inquiry_handling_record WHERE inquiry_id = ?", inquiryId);
      jdbc.update("DELETE FROM inquiry_analysis WHERE inquiry_id = ?", inquiryId);
      jdbc.update("DELETE FROM inquiries WHERE id = ?", inquiryId);
    }
  }

  private Set<String> columns(String table) {
    return jdbc.execute(
        (ConnectionCallback<Set<String>>)
            connection -> {
          Set<String> names = new HashSet<>();
          try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
              names.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
            }
          }
          return names;
        });
  }

  @Test
  @DisplayName("V8 columns and history tables exist in the Hibernate-generated database structure")
  void entityDdlIncludesAllV8Columns() throws Exception {
    assertThat(columns("INQUIRIES"))
        .contains(
            "STATUS",
            "PRIORITY",
            "CATEGORY_SOURCE",
            "PRIORITY_SOURCE",
            "ANALYSIS_STATUS",
            "CURRENT_ANALYSIS_ID",
            "HANDLED_BY",
            "HANDLED_AT",
            "HANDLING_NOTE",
            "UPDATED_AT",
            "RESOLVED_AT",
            "CLOSED_AT",
            "VERSION");
    assertThat(columns("INQUIRY_ANALYSIS"))
        .contains("INQUIRY_ID", "VERSION", "ANALYSIS_REASON", "STATUS", "COMPLETED_AT");
    assertThat(columns("INQUIRY_HANDLING_RECORD"))
        .contains("INQUIRY_ID", "ACTION_TYPE", "OLD_VALUE", "NEW_VALUE", "CREATED_AT");
  }

  @Test
  @DisplayName("Repositories persist analysis/handling histories and the new inquiry state")
  void repositoriesPersistFullV8Model() {
    Inquiry inquiry =
        new Inquiry(key, "customer@example.com", "question", "private body", "OTHER");
    inquiry.status = "IN_PROGRESS";
    inquiry.priority = "HIGH";
    inquiry.categorySource = "AI";
    inquiry.prioritySource = "HUMAN";
    inquiry.analysisStatus = "SUCCESS";
    inquiry.handledBy = "agent-7";
    inquiry.handlingNote = "manual review";
    Inquiry saved = inquiries.saveAndFlush(inquiry);
    inquiryId = saved.id;

    // EN: @Version starts at 0 and advances on update; @UpdateTimestamp is populated by Hibernate.
    // 中文：@Version 从 0 开始并在更新时递增；@UpdateTimestamp 由 Hibernate 填充。
    assertThat(saved.version).isZero();
    assertThat(saved.updatedAt).isNotNull();

    InquiryAnalysis first = new InquiryAnalysis();
    first.inquiry = saved;
    first.version = 1;
    first.category = "REFUND";
    first.priority = "HIGH";
    first.confidence = 0.8;
    first.needsHumanReview = true;
    first.analysisReason = "refund keywords";
    first.suggestedReply = "We will check your refund.";
    first.analysisSource = "AI";
    first.status = "SUCCESS";
    InquiryAnalysis firstSaved = analyses.saveAndFlush(first);

    InquiryAnalysis second = new InquiryAnalysis();
    second.inquiry = saved;
    second.version = 2;
    second.category = "REFUND";
    second.status = "FAILED";
    second.errorMessage = "AI_TIMEOUT";
    analyses.saveAndFlush(second);

    InquiryHandlingRecord handlingOne = new InquiryHandlingRecord();
    handlingOne.inquiry = saved;
    handlingOne.operatorId = "agent-7";
    handlingOne.actionType = "CATEGORY_CHANGE";
    handlingOne.oldValue = "{\"category\":\"OTHER\"}";
    handlingOne.newValue = "{\"category\":\"REFUND\"}";
    handlingRecords.saveAndFlush(handlingOne);

    InquiryHandlingRecord handlingTwo = new InquiryHandlingRecord();
    handlingTwo.inquiry = saved;
    handlingTwo.operatorId = "agent-7";
    handlingTwo.actionType = "NOTE_ADD";
    handlingTwo.note = "called customer";
    handlingRecords.saveAndFlush(handlingTwo);

    // EN: Wire the current analysis through the writable logical id. The referenced analysis is
    //     resolved through its own repository, so there is no cyclic FK and no cascade.
    // 中文：通过可写的逻辑 id 指向当前分析；完整分析记录由独立 Repository 查询，无循环外键与级联。
    saved.currentAnalysisId = firstSaved.id;
    Inquiry updated = inquiries.saveAndFlush(saved);
    assertThat(updated.version).isEqualTo(1L);

    Inquiry reloaded = inquiries.findById(inquiryId).orElseThrow();
    assertThat(reloaded.currentAnalysisId).isEqualTo(firstSaved.id);
    assertThat(analyses.findById(reloaded.currentAnalysisId).orElseThrow().version).isEqualTo(1);
    assertThat(reloaded.status).isEqualTo("IN_PROGRESS");
    assertThat(reloaded.priority).isEqualTo("HIGH");
    assertThat(reloaded.categorySource).isEqualTo("AI");
    assertThat(reloaded.prioritySource).isEqualTo("HUMAN");
    assertThat(reloaded.analysisStatus).isEqualTo("SUCCESS");
    assertThat(reloaded.handledBy).isEqualTo("agent-7");
    assertThat(reloaded.handlingNote).isEqualTo("manual review");

    InquiryAnalysis v1 = analyses.findByInquiry_IdAndVersion(inquiryId, 1).orElseThrow();
    assertThat(v1.analysisReason).isEqualTo("refund keywords");
    assertThat(v1.confidence).isEqualTo(0.8);
    assertThat(v1.needsHumanReview).isTrue();
    List<InquiryAnalysis> timeline = analyses.findByInquiry_IdOrderByVersionAsc(inquiryId);
    assertThat(timeline).extracting(a -> a.version).containsExactly(1, 2);
    assertThat(timeline.get(1).errorMessage).isEqualTo("AI_TIMEOUT");

    List<InquiryHandlingRecord> history =
        handlingRecords.findByInquiry_IdOrderByCreatedAtAsc(inquiryId);
    assertThat(history).hasSize(2);
    assertThat(history.get(0).oldValue).isEqualTo("{\"category\":\"OTHER\"}");
    assertThat(history.get(1).actionType).isEqualTo("NOTE_ADD");
  }

  @Test
  @DisplayName("Duplicate analysis version is rejected by the database through JPA")
  void duplicateAnalysisVersionRejected() {
    Inquiry saved = inquiries.saveAndFlush(new Inquiry(key, "a@example.com", "s", "b", "OTHER"));
    inquiryId = saved.id;

    InquiryAnalysis first = new InquiryAnalysis();
    first.inquiry = saved;
    first.version = 1;
    first.status = "SUCCESS";
    analyses.saveAndFlush(first);

    InquiryAnalysis duplicate = new InquiryAnalysis();
    duplicate.inquiry = saved;
    duplicate.version = 1;
    duplicate.status = "FAILED";
    assertThatThrownBy(() -> analyses.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
