package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderai.workbench.dto.response.InquiryStatusResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.entity.InquiryHandlingRecord;
import com.orderai.workbench.repository.InquiryHandlingRecordRepository;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * EN: Status-transition integration tests on a real H2 database. The service writes state and the
 * handling record in one transaction, so these tests reload from the repository instead of mocking
 * it. MockMvc is used only for the HTTP contract (status codes and response envelope); conversion
 * rules are always exercised through the real service.
 *
 * <p>中文：基于真实 H2 数据库的状态流转集成测试。Service 在同一事务中写入状态与处理历史，因此测试从 Repository
 * 重新加载校验，而非 mock。MockMvc 只用于 HTTP 契约（状态码与响应结构）；转换规则始终通过真实 Service 验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InquiryStatusFlowTest {
  @Autowired InquiryService service;
  @Autowired InquiryRepository inquiries;
  @Autowired InquiryHandlingRecordRepository handlingRecords;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  private final List<Long> createdIds = new ArrayList<>();

  @AfterEach
  void cleanUpOnlyRowsCreatedByThisTest() {
    // EN: Delete only this test's own rows; never clear whole tables.
    // 中文：只删除本测试创建的记录，绝不清空整表。
    for (Long id : createdIds) {
      jdbc.update("DELETE FROM inquiry_handling_record WHERE inquiry_id = ?", id);
      jdbc.update("DELETE FROM inquiries WHERE id = ?", id);
    }
    createdIds.clear();
  }

  private Inquiry seed(String status) {
    Inquiry inquiry =
        new Inquiry(
            "status-" + UUID.randomUUID(), "customer@example.com", "subject", "body", "OTHER");
    inquiry.status = status;
    Inquiry saved = inquiries.saveAndFlush(inquiry);
    createdIds.add(saved.id);
    return saved;
  }

  private List<InquiryHandlingRecord> history(long id) {
    return handlingRecords.findByInquiry_IdOrderByCreatedAtAsc(id);
  }

  private static void assertHttpStatus(int expected, Throwable thrown) {
    assertThat(thrown).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) thrown).getStatusCode().value()).isEqualTo(expected);
  }

  static Stream<Arguments> legalTransitions() {
    return Stream.of(
        Arguments.of("PENDING", "IN_PROGRESS", "STATUS_CHANGE"),
        Arguments.of("PENDING", "CLOSED", "CLOSE"),
        Arguments.of("IN_PROGRESS", "WAITING_CUSTOMER", "STATUS_CHANGE"),
        Arguments.of("IN_PROGRESS", "RESOLVED", "RESOLVE"),
        Arguments.of("IN_PROGRESS", "CLOSED", "CLOSE"),
        Arguments.of("WAITING_CUSTOMER", "IN_PROGRESS", "STATUS_CHANGE"),
        Arguments.of("WAITING_CUSTOMER", "CLOSED", "CLOSE"),
        Arguments.of("RESOLVED", "IN_PROGRESS", "STATUS_CHANGE"),
        Arguments.of("RESOLVED", "CLOSED", "CLOSE"),
        Arguments.of("CLOSED", "PENDING", "REOPEN"));
  }

  static Stream<Arguments> illegalTransitions() {
    return Stream.of(
        Arguments.of("PENDING", "WAITING_CUSTOMER"),
        Arguments.of("PENDING", "RESOLVED"),
        Arguments.of("IN_PROGRESS", "PENDING"),
        Arguments.of("WAITING_CUSTOMER", "PENDING"),
        Arguments.of("WAITING_CUSTOMER", "RESOLVED"),
        Arguments.of("RESOLVED", "PENDING"),
        Arguments.of("RESOLVED", "WAITING_CUSTOMER"),
        Arguments.of("CLOSED", "IN_PROGRESS"),
        Arguments.of("CLOSED", "WAITING_CUSTOMER"),
        Arguments.of("CLOSED", "RESOLVED"));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("legalTransitions")
  @DisplayName("All ten legal transitions update state and append exactly one handling record")
  void legalTransitionWritesStateAndHistory(String from, String to, String action) {
    Inquiry seed = seed(from);
    long id = seed.id;
    long versionBefore = seed.version;

    InquiryStatusResponse result = service.changeStatus(id, to);

    Inquiry reloaded = inquiries.findById(id).orElseThrow();
    assertThat(result.id()).isEqualTo(id);
    assertThat(result.previousStatus()).isEqualTo(from);
    assertThat(result.currentStatus()).isEqualTo(to);
    assertThat(result.updatedAt()).isEqualTo(reloaded.updatedAt);
    assertThat(reloaded.status).isEqualTo(to);
    assertThat(reloaded.version).isEqualTo(versionBefore + 1);
    assertThat(reloaded.updatedAt).isNotNull();

    List<InquiryHandlingRecord> records = history(id);
    assertThat(records).hasSize(1);
    assertThat(records.get(0).actionType).isEqualTo(action);
    assertThat(records.get(0).oldValue).isEqualTo("{\"status\":\"" + from + "\"}");
    assertThat(records.get(0).newValue).isEqualTo("{\"status\":\"" + to + "\"}");
    assertThat(records.get(0).operatorId).isNull();
    assertThat(records.get(0).note).isNull();
  }

  @ParameterizedTest(name = "{0} -X-> {1}")
  @MethodSource("illegalTransitions")
  @DisplayName("All illegal transitions return 409 and leave state and history untouched")
  void illegalTransitionIsRejectedWithoutWrites(String from, String to) {
    Inquiry seed = seed(from);
    long id = seed.id;
    long versionBefore = seed.version;

    assertThatThrownBy(() -> service.changeStatus(id, to))
        .satisfies(thrown -> assertHttpStatus(409, thrown));

    Inquiry reloaded = inquiries.findById(id).orElseThrow();
    assertThat(reloaded.status).isEqualTo(from);
    assertThat(reloaded.version).isEqualTo(versionBefore);
    assertThat(history(id)).isEmpty();
  }

  @Test
  @DisplayName("Unknown inquiry is 404; invalid target status is 400; uninitialized current status is 409")
  void validationErrorsUseTheDocumentedStatusCodes() {
    Inquiry uninitialized = seed(null);
    Inquiry initialized = seed("PENDING");

    assertThatThrownBy(() -> service.changeStatus(999_999L, "IN_PROGRESS"))
        .satisfies(thrown -> assertHttpStatus(404, thrown));

    assertThatThrownBy(() -> service.changeStatus(initialized.id, "BOGUS"))
        .satisfies(thrown -> assertHttpStatus(400, thrown));

    assertThatThrownBy(() -> service.changeStatus(uninitialized.id, "IN_PROGRESS"))
        .satisfies(thrown -> assertHttpStatus(409, thrown));

    assertThat(inquiries.findById(uninitialized.id).orElseThrow().status).isNull();
    assertThat(inquiries.findById(initialized.id).orElseThrow().status).isEqualTo("PENDING");
    assertThat(history(uninitialized.id)).isEmpty();
    assertThat(history(initialized.id)).isEmpty();
  }

  @Test
  @DisplayName("Same-status request is an idempotent 200 with no write and no history")
  void sameStatusIsNoOp() {
    Inquiry seed = seed("IN_PROGRESS");
    long versionBefore = seed.version;

    InquiryStatusResponse result = service.changeStatus(seed.id, "IN_PROGRESS");

    assertThat(result.previousStatus()).isEqualTo("IN_PROGRESS");
    assertThat(result.currentStatus()).isEqualTo("IN_PROGRESS");
    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(reloaded.version).isEqualTo(versionBefore);
    assertThat(history(seed.id)).isEmpty();
  }

  @Test
  @DisplayName("Reopening RESOLVED enters IN_PROGRESS; reopening CLOSED enters PENDING with REOPEN")
  void reopeningUsesTheConfiguredTargetAndAction() {
    Inquiry resolved = seed("RESOLVED");
    resolved.resolvedAt = resolved.createdAt;
    inquiries.saveAndFlush(resolved);

    InquiryStatusResponse reopened = service.changeStatus(resolved.id, "IN_PROGRESS");
    assertThat(reopened.currentStatus()).isEqualTo("IN_PROGRESS");
    Inquiry afterReopen = inquiries.findById(resolved.id).orElseThrow();
    assertThat(afterReopen.resolvedAt).isNull();
    assertThat(afterReopen.closedAt).isNull();
    assertThat(history(resolved.id).get(0).actionType).isEqualTo("STATUS_CHANGE");

    Inquiry closed = seed("CLOSED");
    closed.closedAt = closed.createdAt;
    inquiries.saveAndFlush(closed);

    InquiryStatusResponse reopenedClosed = service.changeStatus(closed.id, "PENDING");
    assertThat(reopenedClosed.currentStatus()).isEqualTo("PENDING");
    Inquiry afterClosedReopen = inquiries.findById(closed.id).orElseThrow();
    assertThat(afterClosedReopen.closedAt).isNull();
    assertThat(afterClosedReopen.resolvedAt).isNull();
    assertThat(history(closed.id).get(0).actionType).isEqualTo("REOPEN");
  }

  @Test
  @DisplayName("Terminal timestamps track only the current terminal state")
  void terminalTimestampsFollowTheCurrentTerminalState() {
    Inquiry inquiry = seed("PENDING");

    service.changeStatus(inquiry.id, "IN_PROGRESS");
    InquiryStatusResponse resolved = service.changeStatus(inquiry.id, "RESOLVED");
    Inquiry atResolved = inquiries.findById(inquiry.id).orElseThrow();
    assertThat(atResolved.resolvedAt).isNotNull();
    assertThat(atResolved.closedAt).isNull();
    assertThat(resolved.updatedAt()).isEqualTo(atResolved.updatedAt);

    service.changeStatus(inquiry.id, "CLOSED");
    Inquiry atClosed = inquiries.findById(inquiry.id).orElseThrow();
    assertThat(atClosed.resolvedAt).isNull();
    assertThat(atClosed.closedAt).isNotNull();

    service.changeStatus(inquiry.id, "PENDING");
    Inquiry reopened = inquiries.findById(inquiry.id).orElseThrow();
    assertThat(reopened.resolvedAt).isNull();
    assertThat(reopened.closedAt).isNull();

    List<InquiryHandlingRecord> records = history(inquiry.id);
    assertThat(records).hasSize(4);
    assertThat(records).extracting(r -> r.actionType)
        .containsExactly("STATUS_CHANGE", "RESOLVE", "CLOSE", "REOPEN");
  }

  @Test
  @DisplayName("Literal null JSON body is rejected with 400; empty object keeps existing validation")
  void nullJsonBodyIsRejectedWith400() throws Exception {
    Inquiry inquiry = seed("PENDING");

    mockMvc
        .perform(
            patch("/api/inquiries/{id}/status", inquiry.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("null"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.message").value("Request body must not be null"));

    // EN: An empty object must still take the existing invalid-status path.
    // 中文：空对象必须继续走原有的非法状态校验路径。
    mockMvc
        .perform(
            patch("/api/inquiries/{id}/status", inquiry.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.message").value("Invalid inquiry status"));

    Inquiry reloaded = inquiries.findById(inquiry.id).orElseThrow();
    assertThat(reloaded.status).isEqualTo("PENDING");
    assertThat(history(inquiry.id)).isEmpty();
  }

  @Test
  @DisplayName("Handling-record insert failure rolls back status and version in the same transaction")
  void handlingRecordFailureRollsBackStatusChange() {
    Inquiry seed = seed("PENDING");
    long versionBefore = seed.version;
    // EN: A test-only CHECK constraint scoped to this inquiry id makes the handling insert fail.
    // 中文：测试专用 CHECK 约束只针对本次咨询 id，稳定制造处理历史写入失败。
    String constraintName =
        "CHK_FAIL_HANDLING_" + UUID.randomUUID().toString().replace("-", "");

    try {
      jdbc.update(
          "ALTER TABLE inquiry_handling_record ADD CONSTRAINT "
              + constraintName
              + " CHECK (inquiry_id <> "
              + seed.id
              + ")");

      assertThatThrownBy(() -> service.changeStatus(seed.id, "IN_PROGRESS"))
          .isInstanceOf(DataIntegrityViolationException.class);

      // EN: Read the committed database state directly so a stale persistence context cannot hide a
      // partial commit.
      // 中文：直接读取数据库中的已提交状态，避免旧持久化上下文掩盖部分提交。
      String statusAfter =
          jdbc.queryForObject(
              "SELECT status FROM inquiries WHERE id = ?", String.class, seed.id);
      Long versionAfter =
          jdbc.queryForObject("SELECT version FROM inquiries WHERE id = ?", Long.class, seed.id);
      Long historyCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM inquiry_handling_record WHERE inquiry_id = ?",
              Long.class,
              seed.id);

      assertThat(statusAfter).isEqualTo("PENDING");
      assertThat(versionAfter).isEqualTo(versionBefore);
      assertThat(historyCount).isZero();
    } finally {
      jdbc.update(
          "ALTER TABLE inquiry_handling_record DROP CONSTRAINT IF EXISTS " + constraintName);
    }
  }

  @Test
  @DisplayName("PATCH endpoint returns the documented envelope and status codes")
  void patchEndpointHonorsTheHttpContract() throws Exception {
    Inquiry inquiry = seed("PENDING");

    String body = objectMapper.writeValueAsString(java.util.Map.of("status", "IN_PROGRESS"));
    mockMvc
        .perform(
            patch("/api/inquiries/{id}/status", inquiry.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("success"))
        .andExpect(jsonPath("$.data.id").value(inquiry.id))
        .andExpect(jsonPath("$.data.previousStatus").value("PENDING"))
        .andExpect(jsonPath("$.data.currentStatus").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.data.updatedAt").exists());

    mockMvc
        .perform(
            patch("/api/inquiries/{id}/status", 999_999L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value("error"));

    mockMvc
        .perform(
            patch("/api/inquiries/{id}/status", inquiry.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"BOGUS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("error"));

    mockMvc
        .perform(
            patch("/api/inquiries/{id}/status", inquiry.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PENDING\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("error"));
  }
}
