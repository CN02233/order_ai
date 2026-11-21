package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderai.workbench.dto.request.InquiryHandlingUpdateRequest;
import com.orderai.workbench.dto.response.InquiryHandlingResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.entity.InquiryHandlingRecord;
import com.orderai.workbench.repository.InquiryHandlingRecordRepository;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * EN: T4 manual-handling integration tests on a real H2 database. Business writes, source markers,
 * optimistic locking and history share genuine transactions; the only injected failures are a
 * test-only CHECK constraint and a repository write gate. No whole Service/Repository is mocked.
 *
 * <p>中文：T4 人工处理集成测试运行在真实 H2 数据库上。业务写入、来源标记、乐观锁与历史共用真实事务；仅通过测试专用
 * CHECK 约束与 Repository 写入闸门注入故障，不 mock 整个 Service/Repository。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(InquiryHandlingIntegrationTest.GateConfiguration.class)
class InquiryHandlingIntegrationTest {

  /** EN: Shared test gate for the deterministic optimistic-lock case. 中文：确定性乐观锁用例共享的测试闸门。 */
  static final AtomicBoolean GATE_ARMED = new AtomicBoolean(false);

  static final AtomicLong GATED_ID = new AtomicLong(-1L);
  static final CountDownLatch[] BOTH_REACHED_WRITE = {new CountDownLatch(2)};
  static final CountDownLatch[] RELEASE_WRITES = {new CountDownLatch(1)};

  @TestConfiguration
  static class GateConfiguration {
    @Bean
    static BeanPostProcessor inquiryRepositoryWriteGate() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (!(bean instanceof InquiryRepository repository) || bean instanceof GateMarker) {
            return bean;
          }
          return Proxy.newProxyInstance(
              InquiryRepository.class.getClassLoader(),
              new Class<?>[] {InquiryRepository.class, GateMarker.class},
              (proxy, method, args) -> {
                if ("saveAndFlush".equals(method.getName())
                    && args != null
                    && args.length == 1
                    && args[0] instanceof Inquiry inquiry
                    && GATE_ARMED.get()
                    && inquiry.id != null
                    && inquiry.id.equals(GATED_ID.get())) {
                  BOTH_REACHED_WRITE[0].countDown();
                  if (!RELEASE_WRITES[0].await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for the test release gate");
                  }
                }
                try {
                  return method.invoke(repository, args);
                } catch (InvocationTargetException wrapped) {
                  // EN: Unwrap so the service sees the real optimistic-lock exception.
                  // 中文：解包，让 Service 收到真实的乐观锁异常。
                  Throwable cause = wrapped.getCause();
                  if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                  }
                  throw wrapped;
                }
              });
        }
      };
    }
  }

  /** EN: Marker so the post-processor never wraps its own proxy. 中文：标记接口，避免重复包装代理。 */
  interface GateMarker {}

  @Autowired InquiryService service;
  @Autowired InquiryRepository inquiries;
  @Autowired InquiryHandlingRecordRepository handlingRecords;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  private final List<Long> createdIds = new ArrayList<>();

  @AfterEach
  void resetGateAndCleanUpOnlyRowsCreatedByThisTest() {
    GATE_ARMED.set(false);
    GATED_ID.set(-1L);
    BOTH_REACHED_WRITE[0] = new CountDownLatch(2);
    RELEASE_WRITES[0] = new CountDownLatch(1);
    // EN: Delete only this test's own rows; never clear whole tables.
    // 中文：只删除本测试创建的记录，绝不清空整表。
    for (Long id : createdIds) {
      jdbc.update("DELETE FROM inquiry_handling_record WHERE inquiry_id = ?", id);
      jdbc.update("DELETE FROM inquiries WHERE id = ?", id);
    }
    createdIds.clear();
  }

  private Inquiry seed(String category, String priority, String categorySource) {
    Inquiry inquiry =
        new Inquiry(
            "handling-" + UUID.randomUUID(), "customer@example.com", "subject", "body", category);
    inquiry.aiCategory = category;
    inquiry.categorySource = categorySource;
    inquiry.priority = priority;
    inquiry.prioritySource = "AI";
    Inquiry saved = inquiries.saveAndFlush(inquiry);
    createdIds.add(saved.id);
    return saved;
  }

  private List<InquiryHandlingRecord> history(long id) {
    return handlingRecords.findByInquiry_IdOrderByCreatedAtAsc(id);
  }

  private JsonNode parse(String json) throws Exception {
    return objectMapper.readTree(json);
  }

  @Test
  @DisplayName("Manual category edit marks HUMAN, keeps ai_category and writes one MANUAL_UPDATE row")
  void manualCategoryUpdateMarksHumanAndKeepsAiSnapshot() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");
    long versionBefore = seed.version;

    InquiryHandlingResponse result =
        service.applyManualHandling(
            seed.id, new InquiryHandlingUpdateRequest("COMPLAINT", null, null, null));

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(result.category()).isEqualTo("COMPLAINT");
    assertThat(result.categorySource()).isEqualTo("HUMAN");
    assertThat(result.historyWritten()).isTrue();
    assertThat(result.version()).isEqualTo(versionBefore + 1);
    assertThat(reloaded.category).isEqualTo("COMPLAINT");
    assertThat(reloaded.categorySource).isEqualTo("HUMAN");
    assertThat(reloaded.aiCategory).isEqualTo("REFUND");
    assertThat(reloaded.priority).isEqualTo("MEDIUM");
    assertThat(reloaded.prioritySource).isEqualTo("AI");

    List<InquiryHandlingRecord> records = history(seed.id);
    assertThat(records).hasSize(1);
    InquiryHandlingRecord record = records.get(0);
    assertThat(record.actionType).isEqualTo("MANUAL_UPDATE");
    assertThat(record.operatorId).isNull();
    assertThat(record.note).isNull();
    assertThat(parse(record.oldValue).path("category").asText()).isEqualTo("REFUND");
    assertThat(parse(record.newValue).path("category").asText()).isEqualTo("COMPLAINT");
    assertThat(parse(record.oldValue).has("priority")).isFalse();
    assertThat(parse(record.newValue).has("priority")).isFalse();
  }

  @Test
  @DisplayName("Manual priority edit marks HUMAN and writes the priority snapshot")
  void manualPriorityUpdateMarksHuman() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");

    InquiryHandlingResponse result =
        service.applyManualHandling(
            seed.id, new InquiryHandlingUpdateRequest(null, "HIGH", null, null));

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(result.priority()).isEqualTo("HIGH");
    assertThat(result.prioritySource()).isEqualTo("HUMAN");
    assertThat(reloaded.priority).isEqualTo("HIGH");
    assertThat(reloaded.prioritySource).isEqualTo("HUMAN");
    assertThat(reloaded.category).isEqualTo("REFUND");
    assertThat(reloaded.categorySource).isEqualTo("AI");

    List<InquiryHandlingRecord> records = history(seed.id);
    assertThat(records).hasSize(1);
    assertThat(parse(records.get(0).oldValue).path("priority").asText()).isEqualTo("MEDIUM");
    assertThat(parse(records.get(0).newValue).path("priority").asText()).isEqualTo("HIGH");
  }

  @Test
  @DisplayName("One request changing category and priority writes exactly one combined history row")
  void combinedUpdateWritesOneHistoryRow() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");

    service.applyManualHandling(
        seed.id, new InquiryHandlingUpdateRequest("COMPLAINT", "URGENT", null, null));

    List<InquiryHandlingRecord> records = history(seed.id);
    assertThat(records).hasSize(1);
    JsonNode oldValue = parse(records.get(0).oldValue);
    JsonNode newValue = parse(records.get(0).newValue);
    assertThat(oldValue.path("category").asText()).isEqualTo("REFUND");
    assertThat(oldValue.path("priority").asText()).isEqualTo("MEDIUM");
    assertThat(newValue.path("category").asText()).isEqualTo("COMPLAINT");
    assertThat(newValue.path("priority").asText()).isEqualTo("URGENT");

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(reloaded.categorySource).isEqualTo("HUMAN");
    assertThat(reloaded.prioritySource).isEqualTo("HUMAN");
    assertThat(reloaded.aiCategory).isEqualTo("REFUND");
  }

  @Test
  @DisplayName("Handled-by and note are trimmed, timestamped server-side and recorded once")
  void handledByAndNoteAreTrimmedAndRecorded() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");

    InquiryHandlingResponse result =
        service.applyManualHandling(
            seed.id,
            new InquiryHandlingUpdateRequest(
                null,
                null,
                "  support-agent-01  ",
                "  Customer requested a refund. Manual review completed.  "));

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(result.handledBy()).isEqualTo("support-agent-01");
    assertThat(reloaded.handledBy).isEqualTo("support-agent-01");
    assertThat(reloaded.handlingNote)
        .isEqualTo("Customer requested a refund. Manual review completed.");
    assertThat(reloaded.handledAt).isNotNull();

    List<InquiryHandlingRecord> records = history(seed.id);
    assertThat(records).hasSize(1);
    assertThat(records.get(0).operatorId).isEqualTo("support-agent-01");
    assertThat(records.get(0).note)
        .isEqualTo("Customer requested a refund. Manual review completed.");
    assertThat(parse(records.get(0).newValue).path("handled_by").asText())
        .isEqualTo("support-agent-01");
    assertThat(parse(records.get(0).newValue).path("note").asText())
        .isEqualTo("Customer requested a refund. Manual review completed.");
  }

  @Test
  @DisplayName("Empty strings are not supplied values; a real no-op writes no history and no version")
  void blankHandlingFieldsAndNoOpDoNotWrite() {
    Inquiry seed = seed("COMPLAINT", "HIGH", "HUMAN");
    seed.handledBy = "support-agent-01";
    seed.handlingNote = "Existing note";
    seed.handledAt = seed.createdAt;
    inquiries.saveAndFlush(seed);
    long versionBefore = inquiries.findById(seed.id).orElseThrow().version;

    assertThatThrownBy(
            () ->
                service.applyManualHandling(
                    seed.id, new InquiryHandlingUpdateRequest(null, null, "   ", "  ")))
        .satisfies(
            thrown -> {
              assertThat(thrown).isInstanceOf(ResponseStatusException.class);
              assertThat(((ResponseStatusException) thrown).getStatusCode().value()).isEqualTo(400);
            });

    InquiryHandlingResponse result =
        service.applyManualHandling(
            seed.id,
            new InquiryHandlingUpdateRequest(
                "COMPLAINT", "HIGH", "support-agent-01", "Existing note"));

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(result.historyWritten()).isFalse();
    assertThat(reloaded.version).isEqualTo(versionBefore);
    assertThat(reloaded.handledBy).isEqualTo("support-agent-01");
    assertThat(reloaded.handlingNote).isEqualTo("Existing note");
    assertThat(reloaded.handledAt).isEqualTo(seed.handledAt);
    assertThat(history(seed.id)).isEmpty();
  }

  @Test
  @DisplayName("PATCH endpoint rejects empty body, invalid category and invalid priority with 400")
  void patchEndpointRejectsInvalidRequests() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");

    mockMvc
        .perform(
            patch("/api/inquiries/{id}/handling", seed.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.message").value("At least one handling field is required"));

    mockMvc
        .perform(
            patch("/api/inquiries/{id}/handling", seed.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"BOGUS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.message").value("Invalid inquiry category"));

    mockMvc
        .perform(
            patch("/api/inquiries/{id}/handling", seed.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"priority\":\"URGENTish\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.message").value("Invalid inquiry priority"));

    assertThat(history(seed.id)).isEmpty();
  }

  @Test
  @DisplayName("PATCH endpoint returns the standard envelope on success")
  void patchEndpointReturnsEnvelope() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");

    mockMvc
        .perform(
            patch("/api/inquiries/{id}/handling", seed.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"priority\":\"HIGH\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("success"))
        .andExpect(jsonPath("$.data.id").value(seed.id))
        .andExpect(jsonPath("$.data.priority").value("HIGH"))
        .andExpect(jsonPath("$.data.priority_source").value("HUMAN"))
        .andExpect(jsonPath("$.data.history_written").value(true));
  }

  @Test
  @DisplayName("Unknown inquiry is 404")
  void unknownInquiryIs404() {
    assertThatThrownBy(
            () ->
                service.applyManualHandling(
                    999_999L, new InquiryHandlingUpdateRequest(null, "HIGH", null, null)))
        .satisfies(
            thrown -> {
              assertThat(thrown).isInstanceOf(ResponseStatusException.class);
              assertThat(((ResponseStatusException) thrown).getStatusCode().value()).isEqualTo(404);
            });
  }

  @Test
  @DisplayName("Handling-history insert failure rolls back every business field and the version")
  void historyFailureRollsBackAllFieldsAndVersion() {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");
    long versionBefore = seed.version;
    String constraintName =
        "CHK_FAIL_MANUAL_" + UUID.randomUUID().toString().replace("-", "");

    try {
      jdbc.update(
          "ALTER TABLE inquiry_handling_record ADD CONSTRAINT "
              + constraintName
              + " CHECK (inquiry_id <> "
              + seed.id
              + ")");

      assertThatThrownBy(
              () ->
                  service.applyManualHandling(
                      seed.id,
                      new InquiryHandlingUpdateRequest(
                          "COMPLAINT", "HIGH", "support-agent-01", "note")))
          .isInstanceOf(DataIntegrityViolationException.class);

      Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
      assertThat(reloaded.category).isEqualTo("REFUND");
      assertThat(reloaded.categorySource).isEqualTo("AI");
      assertThat(reloaded.priority).isEqualTo("MEDIUM");
      assertThat(reloaded.prioritySource).isEqualTo("AI");
      assertThat(reloaded.handledBy).isNull();
      assertThat(reloaded.handledAt).isNull();
      assertThat(reloaded.handlingNote).isNull();
      assertThat(reloaded.version).isEqualTo(versionBefore);
      assertThat(history(seed.id)).isEmpty();
    } finally {
      jdbc.update(
          "ALTER TABLE inquiry_handling_record DROP CONSTRAINT IF EXISTS " + constraintName);
    }
  }

  @Test
  @DisplayName("Concurrent manual updates: one wins, the stale writer gets 409")
  void concurrentManualUpdateMapsOptimisticLockConflictTo409() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");
    long id = seed.id;
    List<Object> outcomes = new CopyOnWriteArrayList<>();

    GATED_ID.set(id);
    GATE_ARMED.set(true);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      InquiryHandlingUpdateRequest request =
          new InquiryHandlingUpdateRequest("COMPLAINT", null, null, null);
      Future<?> first = pool.submit(() -> runAndCapture(outcomes, id, request));
      Future<?> second = pool.submit(() -> runAndCapture(outcomes, id, request));

      assertThat(BOTH_REACHED_WRITE[0].await(10, TimeUnit.SECONDS))
          .as("both transactions must have read version 0 and reached saveAndFlush")
          .isTrue();
      RELEASE_WRITES[0].countDown();

      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      RELEASE_WRITES[0].countDown();
      pool.shutdownNow();
      GATE_ARMED.set(false);
    }

    long successes =
        outcomes.stream().filter(outcome -> outcome instanceof InquiryHandlingResponse).count();
    long conflicts =
        outcomes.stream().filter(outcome -> outcome instanceof ResponseStatusException).count();
    assertThat(outcomes).as("one success and one 409, no other outcome").hasSize(2);
    assertThat(successes).isEqualTo(1);
    assertThat(conflicts).isEqualTo(1);

    ResponseStatusException conflict =
        (ResponseStatusException)
            outcomes.stream()
                .filter(outcome -> outcome instanceof ResponseStatusException)
                .findFirst()
                .orElseThrow();
    assertThat(conflict.getStatusCode().value()).isEqualTo(409);

    Inquiry finalRow = inquiries.findById(id).orElseThrow();
    assertThat(finalRow.category).isEqualTo("COMPLAINT");
    assertThat(finalRow.categorySource).isEqualTo("HUMAN");
    assertThat(finalRow.version).isEqualTo(1L);
    assertThat(history(id)).hasSize(1);
  }

  @Test
  @DisplayName("Legacy PUT category edit cannot bypass HUMAN source and handling history")
  void legacyPutCategoryEditStillMarksHumanAndWritesHistory() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");

    mockMvc
        .perform(
            put("/api/inquiries/{id}", seed.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("category", "OTHER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("success"));

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(reloaded.category).isEqualTo("OTHER");
    assertThat(reloaded.categorySource).isEqualTo("HUMAN");
    assertThat(reloaded.aiCategory).isEqualTo("REFUND");

    List<InquiryHandlingRecord> records = history(seed.id);
    assertThat(records).hasSize(1);
    assertThat(records.get(0).actionType).isEqualTo("MANUAL_UPDATE");
    assertThat(parse(records.get(0).oldValue).path("category").asText()).isEqualTo("REFUND");
    assertThat(parse(records.get(0).newValue).path("category").asText()).isEqualTo("OTHER");
  }

  @Test
  @DisplayName("Legacy PUT remains compatible for non-category fields without creating history")
  void legacyPutNonCategoryFieldsRemainCompatible() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");

    mockMvc
        .perform(
            put("/api/inquiries/{id}", seed.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("subject", "Updated subject"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.subject").value("Updated subject"));

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(reloaded.subject).isEqualTo("Updated subject");
    assertThat(reloaded.category).isEqualTo("REFUND");
    assertThat(reloaded.categorySource).isEqualTo("AI");
    assertThat(history(seed.id)).isEmpty();
  }

  @Test
  @DisplayName("Category-only manual edit refreshes handled_at")
  void categoryOnlyChangeRefreshesHandledAt() {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");
    assertThat(seed.handledAt).isNull();

    InquiryHandlingResponse result =
        service.applyManualHandling(
            seed.id, new InquiryHandlingUpdateRequest("COMPLAINT", null, null, null));

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(result.handledAt()).isNotNull();
    assertThat(reloaded.handledAt).isNotNull();
  }

  @Test
  @DisplayName("Priority-only manual edit refreshes handled_at")
  void priorityOnlyChangeRefreshesHandledAt() {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");
    assertThat(seed.handledAt).isNull();

    InquiryHandlingResponse result =
        service.applyManualHandling(
            seed.id, new InquiryHandlingUpdateRequest(null, "HIGH", null, null));

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(result.handledAt()).isNotNull();
    assertThat(reloaded.handledAt).isNotNull();
  }

  @Test
  @DisplayName("Legacy PUT category edit refreshes handled_at")
  void legacyPutCategoryEditRefreshesHandledAt() throws Exception {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");
    assertThat(seed.handledAt).isNull();

    mockMvc
        .perform(
            put("/api/inquiries/{id}", seed.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("category", "OTHER"))))
        .andExpect(status().isOk());

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(reloaded.handledAt).isNotNull();
  }

  @Test
  @DisplayName("No-op manual request leaves handled_at at its previous value")
  void noOpRequestKeepsHandledAtUnchanged() {
    Inquiry seed = seed("COMPLAINT", "HIGH", "HUMAN");
    LocalDateTime original = LocalDateTime.of(2020, 1, 1, 12, 0);
    seed.handledAt = original;
    inquiries.saveAndFlush(seed);

    InquiryHandlingResponse result =
        service.applyManualHandling(
            seed.id, new InquiryHandlingUpdateRequest("COMPLAINT", "HIGH", null, null));

    Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
    assertThat(result.historyWritten()).isFalse();
    assertThat(reloaded.handledAt).isEqualTo(original);
  }

  @Test
  @DisplayName("History insert failure rolls back handled_at with the rest of the transaction")
  void historyFailureRollsBackHandledAt() {
    Inquiry seed = seed("REFUND", "MEDIUM", "AI");
    LocalDateTime original = LocalDateTime.of(2020, 1, 1, 12, 0);
    seed.handledAt = original;
    inquiries.saveAndFlush(seed);
    String constraintName =
        "CHK_FAIL_HANDLED_AT_" + UUID.randomUUID().toString().replace("-", "");

    try {
      jdbc.update(
          "ALTER TABLE inquiry_handling_record ADD CONSTRAINT "
              + constraintName
              + " CHECK (inquiry_id <> "
              + seed.id
              + ")");

      assertThatThrownBy(
              () ->
                  service.applyManualHandling(
                      seed.id,
                      new InquiryHandlingUpdateRequest(
                          "COMPLAINT", null, "support-agent-01", "note")))
          .isInstanceOf(DataIntegrityViolationException.class);

      Inquiry reloaded = inquiries.findById(seed.id).orElseThrow();
      assertThat(reloaded.handledAt).isEqualTo(original);
      assertThat(reloaded.category).isEqualTo("REFUND");
      assertThat(history(seed.id)).isEmpty();
    } finally {
      jdbc.update(
          "ALTER TABLE inquiry_handling_record DROP CONSTRAINT IF EXISTS " + constraintName);
    }
  }

  /** EN: Record either outcome without swallowing it. 中文：记录任一结果，绝不吞掉异常。 */
  private void runAndCapture(
      List<Object> outcomes, long id, InquiryHandlingUpdateRequest request) {
    try {
      outcomes.add(service.applyManualHandling(id, request));
    } catch (RuntimeException failure) {
      outcomes.add(failure);
    }
  }
}
