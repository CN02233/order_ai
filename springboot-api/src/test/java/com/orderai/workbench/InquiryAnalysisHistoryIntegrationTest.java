package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderai.workbench.dto.response.AnalysisHistoryItem;
import com.orderai.workbench.dto.response.ReanalyzeResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.entity.InquiryAnalysis;
import com.orderai.workbench.repository.InquiryAnalysisRepository;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * EN: T5 analysis-history integration tests on a real H2 database with real REQUIRES_NEW
 * transactions and PESSIMISTIC_WRITE locks. The only stub is the remote Python HTTP endpoint; no
 * Service/Repository is mocked. Concurrency cases use real threads, real row locks and gated HTTP
 * responses to force deterministic out-of-order completion.
 *
 * <p>中文：T5 分析历史集成测试运行在真实 H2 数据库上，使用真实的 REQUIRES_NEW 事务与 PESSIMISTIC_WRITE 锁。
 * 唯一被桩化的是远程 Python HTTP 端点；不 mock 任何 Service/Repository。并发用例使用真实线程、真实行锁与受控 HTTP
 * 响应来确定性地制造乱序完成。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      // EN: Isolate T5 data from other Spring test contexts sharing the default in-memory DB.
      // 中文：与其他共用默认内存库的 Spring 测试上下文隔离 T5 数据。
      "spring.datasource.url=jdbc:h2:mem:orderai_t5;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
    })
class InquiryAnalysisHistoryIntegrationTest {
  private static final String EMAIL = "t5.customer@example.com";
  private static final String SUBJECT = "T5 subject";
  private static final String BODY = "T5 body about an order";

  static HttpServer server;
  static StubHandler handler;

  @DynamicPropertySource
  static void configureStub(DynamicPropertyRegistry registry) throws IOException {
    if (server == null) {
      handler = new StubHandler();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/analyze-inquiry", handler);
      // EN: Thread pool so a sleeping/gated response never blocks later test requests.
      // 中文：线程池，避免 sleep/受控响应阻塞后续测试请求。
      server.setExecutor(Executors.newCachedThreadPool());
      server.start();
    }
    registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    // EN: Short read timeout so the timeout case is fast; sleeps elsewhere are well below it.
    // 中文：较短读超时让超时用例快速完成；其他用例的 sleep 远低于此值。
    registry.add("app.ai.read-timeout-seconds", () -> "5");
    registry.add("app.ai.connect-timeout-seconds", () -> "3");
  }

  @AfterAll
  static void stopStub() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  @Autowired InquiryService service;
  @Autowired InquiryRepository inquiries;
  @Autowired InquiryAnalysisRepository analyses;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  private final List<Long> createdIds = new ArrayList<>();

  @BeforeEach
  void resetStub() {
    handler.reset();
    handler.defaultResponse = ok(aiJson("ORDER_QUERY", "HIGH", false));
  }

  @AfterEach
  void cleanUpOnlyOwnRows() {
    for (Long id : createdIds) {
      jdbc.update("DELETE FROM inquiry_handling_record WHERE inquiry_id = ?", id);
      jdbc.update("DELETE FROM inquiry_analysis WHERE inquiry_id = ?", id);
      jdbc.update("DELETE FROM inquiries WHERE id = ?", id);
    }
    createdIds.clear();
  }

  private Inquiry seed(String category, String priority, String categorySource, String prioritySource) {
    Inquiry i = new Inquiry("t5-" + UUID.randomUUID(), EMAIL, SUBJECT, BODY, category);
    i.aiCategory = category;
    i.priority = priority;
    i.categorySource = categorySource;
    i.prioritySource = prioritySource;
    Inquiry saved = inquiries.saveAndFlush(i);
    createdIds.add(saved.id);
    return saved;
  }

  private Map<String, String> payload() {
    return Map.of("customer_email", EMAIL, "subject", SUBJECT, "body", BODY);
  }

  private void track(long id) {
    createdIds.add(id);
  }

  // ------------------------------------------------------------------ first analysis

  @Test
  @DisplayName("First analysis success creates v1 and points current_analysis_id at it")
  void firstAnalysisSuccessCreatesV1() {
    String key = "t5-first-" + UUID.randomUUID();
    var response = service.create(payload(), key);

    assertThat(response.analysisStatus()).isEqualTo("SUCCESS");
    Inquiry row = inquiries.findByIdempotencyKey(key).orElseThrow();
    track(row.id);
    assertThat(row.status).isNull(); // EN: analysis never touches business status. 中文：分析不碰业务状态。
    assertThat(row.analysisStatus).isEqualTo("SUCCESS");
    assertThat(analyses.findByInquiry_IdOrderByVersionAsc(row.id))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.version).isEqualTo(1);
              assertThat(a.status).isEqualTo("SUCCESS");
              assertThat(a.category).isEqualTo("ORDER_QUERY");
              assertThat(a.priority).isEqualTo("HIGH");
              assertThat(a.completedAt).isNotNull();
            });
    InquiryAnalysis v1 = analyses.findByInquiry_IdAndVersion(row.id, 1).orElseThrow();
    assertThat(row.currentAnalysisId).isEqualTo(v1.id);
    assertThat(row.categorySource).isEqualTo("AI");
    assertThat(row.prioritySource).isEqualTo("AI");
    assertThat(row.priority).isEqualTo("HIGH");
  }

  @Test
  @DisplayName("First analysis failure keeps the raw inquiry and leaves a FAILED v1 history")
  void firstAnalysisFailureLeavesFailedHistory() {
    handler.defaultResponse = error(500, "{\"detail\":\"boom\"}");

    var response = service.create(payload(), "t5-fail-" + UUID.randomUUID());

    assertThat(response.analysisStatus()).isEqualTo("FAILED");
    assertThat(response.analysisError()).isEqualTo("AI_HTTP_ERROR");
    Inquiry row = inquiries.findById(response.inquiryId()).orElseThrow();
    track(row.id);
    assertThat(row.customerEmail).isEqualTo(EMAIL); // EN: raw row kept. 中文：原始记录保留。
    assertThat(row.analysisStatus).isEqualTo("FAILED");
    assertThat(row.currentAnalysisId).isNull();
    assertThat(row.category).isNull();
    assertThat(analyses.findByInquiry_IdOrderByVersionAsc(row.id))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.version).isEqualTo(1);
              assertThat(a.status).isEqualTo("FAILED");
              assertThat(a.errorMessage).isEqualTo("AI_HTTP_ERROR");
              assertThat(a.completedAt).isNotNull();
            });
  }

  @Test
  @DisplayName("Invalid analysis result leaves FAILED history without touching current result")
  void invalidResultLeavesFailedHistory() {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    InquiryAnalysis first = appendSuccess(base, 1, "REFUND", "MEDIUM");
    base.currentAnalysisId = first.id;
    base.analysisStatus = "SUCCESS";
    inquiries.saveAndFlush(base);

    handler.defaultResponse = ok(aiJson("BOGUS_CATEGORY", "HIGH", false));

    ReanalyzeResponse result = service.reanalyze(base.id);

    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.errorMessage()).isEqualTo("AI_INVALID_RESPONSE");
    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.currentAnalysisId).isEqualTo(first.id); // EN: current unchanged. 中文：当前分析未变。
    assertThat(reloaded.analysisStatus).isEqualTo("FAILED"); // EN: latest attempt failed. 中文：最新尝试失败。
    assertThat(analyses.findByInquiry_IdAndVersion(base.id, 2).orElseThrow().status)
        .isEqualTo("FAILED");
  }

  @Test
  @DisplayName("Python timeout leaves a FAILED history row and a safe error code")
  void timeoutLeavesFailedHistory() {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    // EN: 8s sleep exceeds the 5s read timeout, so the HTTP call fails with a timeout.
    // 中文：sleep 8 秒超过 5 秒读超时，HTTP 调用必然超时。
    handler.defaultResponse = ok("{}", 8000);

    ReanalyzeResponse result = service.reanalyze(base.id);

    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.errorMessage()).isEqualTo("AI_TIMEOUT");
    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(analyses.findByInquiry_IdOrderByVersionAsc(base.id))
        .singleElement()
        .satisfies(a -> assertThat(a.status).isEqualTo("FAILED"));
    assertThat(reloaded.currentAnalysisId).isNull();
  }

  @Test
  @DisplayName("HTTP error leaves a FAILED history row with a safe code")
  void httpErrorLeavesFailedHistory() {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    handler.defaultResponse = error(500, "{\"detail\":\"boom\"}");

    ReanalyzeResponse result = service.reanalyze(base.id);

    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.errorMessage()).isEqualTo("AI_HTTP_ERROR");
    assertThat(analyses.findByInquiry_IdAndVersion(base.id, 1).orElseThrow().errorMessage)
        .isEqualTo("AI_HTTP_ERROR");
  }

  @Test
  @DisplayName("Idempotent replay on the real service does not call Python again or duplicate history")
  void replayDoesNotDuplicateHistoryOnRealService() {
    String key = "t5-replay-" + UUID.randomUUID();
    handler.defaultResponse = ok(aiJson("REFUND", "HIGH", true));

    var first = service.create(payload(), key);
    int callsAfterFirst = handler.calls.get();
    var replay = service.create(payload(), key);

    assertThat(replay.inquiryId()).isEqualTo(first.inquiryId());
    assertThat(replay.replayed()).isTrue();
    assertThat(handler.calls.get()).isEqualTo(callsAfterFirst); // EN: no second Python call. 中文：无第二次 Python 调用。
    Inquiry row = inquiries.findByIdempotencyKey(key).orElseThrow();
    track(row.id);
    assertThat(analyses.findByInquiry_IdOrderByVersionAsc(row.id)).hasSize(1);
  }

  // ------------------------------------------------------------------ reanalysis

  @Test
  @DisplayName("Reanalyze success appends a new version and advances the current pointer")
  void reanalyzeSuccessAppendsVersion() {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    InquiryAnalysis first = appendSuccess(base, 1, "REFUND", "MEDIUM");
    base.currentAnalysisId = first.id;
    base.analysisStatus = "SUCCESS";
    inquiries.saveAndFlush(base);

    handler.defaultResponse = ok(aiJson("COMPLAINT", "URGENT", true));

    ReanalyzeResponse result = service.reanalyze(base.id);

    assertThat(result.analysisStatus()).isEqualTo("SUCCESS");
    assertThat(result.analysisVersion()).isEqualTo(2);
    assertThat(result.category()).isEqualTo("COMPLAINT");
    assertThat(result.priority()).isEqualTo("URGENT");
    List<com.orderai.workbench.entity.InquiryAnalysis> rows =
        analyses.findByInquiry_IdOrderByVersionAsc(base.id);
    assertThat(rows).extracting(a -> a.version).containsExactly(1, 2);
    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.currentAnalysisId).isEqualTo(result.analysisId());
    assertThat(reloaded.category).isEqualTo("COMPLAINT");
    assertThat(reloaded.priority).isEqualTo("URGENT");
  }

  @Test
  @DisplayName("Reanalyze failure keeps the previous successful result as current")
  void reanalyzeFailureKeepsCurrent() {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    InquiryAnalysis first = appendSuccess(base, 1, "REFUND", "MEDIUM");
    base.currentAnalysisId = first.id;
    base.analysisStatus = "SUCCESS";
    inquiries.saveAndFlush(base);
    handler.defaultResponse = error(500, "{\"detail\":\"boom\"}");

    ReanalyzeResponse result = service.reanalyze(base.id);

    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.errorMessage()).isEqualTo("AI_HTTP_ERROR");
    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.currentAnalysisId).isEqualTo(first.id);
    assertThat(reloaded.category).isEqualTo("REFUND");
    assertThat(reloaded.analysisStatus).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("Repeated reanalysis increments versions with no gaps or duplicates")
  void repeatedReanalysisIncrementsVersions() {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    handler.defaultResponse = ok(aiJson("REFUND", "HIGH", true));

    ReanalyzeResponse v1 = service.reanalyze(base.id);
    ReanalyzeResponse v2 = service.reanalyze(base.id);
    ReanalyzeResponse v3 = service.reanalyze(base.id);

    assertThat(List.of(v1.analysisVersion(), v2.analysisVersion(), v3.analysisVersion()))
        .containsExactly(1, 2, 3);
    assertThat(analyses.findByInquiry_IdOrderByVersionAsc(base.id))
        .extracting(a -> a.version)
        .containsExactly(1, 2, 3);
    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.currentAnalysisId).isEqualTo(v3.analysisId());
  }

  @Test
  @DisplayName("Failed versions never become current; current is the latest SUCCESS")
  void failedVersionNeverBecomesCurrent() {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    handler.defaultResponse = ok(aiJson("REFUND", "HIGH", true));
    ReanalyzeResponse v1 = service.reanalyze(base.id);
    handler.defaultResponse = error(500, "{}");
    ReanalyzeResponse v2 = service.reanalyze(base.id);
    handler.defaultResponse = ok(aiJson("REFUND", "URGENT", true));
    ReanalyzeResponse v3 = service.reanalyze(base.id);
    handler.defaultResponse = error(503, "{}");
    ReanalyzeResponse v4 = service.reanalyze(base.id);

    assertThat(v2.analysisStatus()).isEqualTo("FAILED");
    assertThat(v4.analysisStatus()).isEqualTo("FAILED");
    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.currentAnalysisId).isEqualTo(v3.analysisId());
    List<AnalysisHistoryItem> history = service.listAnalyses(base.id);
    assertThat(history).extracting(AnalysisHistoryItem::version).containsExactly(1, 2, 3, 4);
    assertThat(history.stream().filter(AnalysisHistoryItem::current)).singleElement()
        .satisfies(item -> assertThat(item.version()).isEqualTo(3));
  }

  // ------------------------------------------------------------------ HUMAN protection

  @Test
  @DisplayName("HUMAN category is not overwritten by AI but ai_category snapshot is refreshed")
  void humanCategoryProtected() {
    Inquiry base = seed("COMPLAINT", "MEDIUM", "HUMAN", "AI");
    handler.defaultResponse = ok(aiJson("REFUND", "HIGH", true));

    service.reanalyze(base.id);

    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.category).isEqualTo("COMPLAINT");
    assertThat(reloaded.categorySource).isEqualTo("HUMAN");
    assertThat(reloaded.aiCategory).isEqualTo("REFUND");
    assertThat(analyses.findByInquiry_IdAndVersion(base.id, 1).orElseThrow().category)
        .isEqualTo("REFUND");
  }

  @Test
  @DisplayName("HUMAN priority is not overwritten by AI")
  void humanPriorityProtected() {
    Inquiry base = seed("REFUND", "URGENT", "AI", "HUMAN");
    handler.defaultResponse = ok(aiJson("REFUND", "LOW", true));

    service.reanalyze(base.id);

    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.priority).isEqualTo("URGENT");
    assertThat(reloaded.prioritySource).isEqualTo("HUMAN");
    assertThat(analyses.findByInquiry_IdAndVersion(base.id, 1).orElseThrow().priority)
        .isEqualTo("LOW");
  }

  @Test
  @DisplayName("AI-owned category and priority are updated from a successful AI result")
  void aiOwnedFieldsUpdated() {
    Inquiry base = seed("ORDER_QUERY", "LOW", "AI", "AI");
    handler.defaultResponse = ok(aiJson("COMPLAINT", "URGENT", true));

    service.reanalyze(base.id);

    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.category).isEqualTo("COMPLAINT");
    assertThat(reloaded.priority).isEqualTo("URGENT");
    assertThat(reloaded.categorySource).isEqualTo("AI");
    assertThat(reloaded.prioritySource).isEqualTo("AI");
  }

  @Test
  @DisplayName("RULE_BASED result never fabricates a priority and never clears an existing one")
  void ruleBasedNeverFabricatesPriority() {
    Inquiry base = seed("REFUND", "URGENT", "AI", "AI");
    handler.defaultResponse = ok(ruleJson("REFUND"));

    ReanalyzeResponse result = service.reanalyze(base.id);

    assertThat(result.analysisStatus()).isEqualTo("SUCCESS");
    assertThat(result.analysisSource()).isEqualTo("RULE_BASED");
    assertThat(result.priority()).isNull();
    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.priority).isEqualTo("URGENT");
    assertThat(reloaded.prioritySource).isEqualTo("AI");
    assertThat(analyses.findByInquiry_IdAndVersion(base.id, 1).orElseThrow().priority).isNull();
  }

  @Test
  @DisplayName("Reanalysis never changes the inquiry business status")
  void reanalysisDoesNotChangeBusinessStatus() {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    base.status = "IN_PROGRESS";
    inquiries.saveAndFlush(base);
    handler.defaultResponse = ok(aiJson("COMPLAINT", "HIGH", true));

    service.reanalyze(base.id);

    Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
    assertThat(reloaded.status).isEqualTo("IN_PROGRESS");
  }

  // ------------------------------------------------------------------ API & 404

  @Test
  @DisplayName("reanalyze and analyses endpoints return the standard envelope; unknown id is 404")
  void endpointsAndNotFound() throws Exception {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    handler.defaultResponse = ok(aiJson("REFUND", "HIGH", true));

    mockMvc
        .perform(post("/api/inquiries/{id}/reanalyze", base.id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("success"))
        .andExpect(jsonPath("$.data.analysis_status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.analysis_version").value(1))
        .andExpect(jsonPath("$.data.priority").value("HIGH"));

    mockMvc
        .perform(get("/api/inquiries/{id}/analyses", base.id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("success"))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].is_current").value(true));

    mockMvc
        .perform(post("/api/inquiries/{id}/reanalyze", 999_999L))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/inquiries/{id}/analyses", 999_999L))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ concurrency

  @Test
  @DisplayName("Two concurrent reanalyses never receive the same analysis version")
  void concurrentReanalysisGetsDistinctVersions() throws Exception {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    handler.defaultResponse = ok(aiJson("REFUND", "HIGH", true));
    int threads = 2;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
    List<ReanalyzeResponse> results = java.util.Collections.synchronizedList(new ArrayList<>());
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                results.add(service.reanalyze(base.id));
              } catch (Throwable t) {
                errors.add(t);
              }
            });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(errors).isEmpty();
    assertThat(results).hasSize(2);
    assertThat(analyses.findByInquiry_IdOrderByVersionAsc(base.id))
        .extracting(a -> a.version)
        .containsExactly(1, 2);
  }

  @Test
  @DisplayName("Out-of-order completion: an older success cannot overwrite a newer current result")
  void outOfOrderCannotOverwriteCurrent() throws Exception {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    handler.defaultResponse = ok(aiJson("REFUND", "HIGH", true));

    // v1 must complete first so it establishes the initial state.
    ReanalyzeResponse v1 = service.reanalyze(base.id);
    assertThat(v1.analysisVersion()).isEqualTo(1);

    // v2's Python response is held; v3 is launched only after v2 has reached its HTTP call,
    // which guarantees v2 was assigned version 2 before v3 began.
    GateResponse held = new GateResponse(ok(aiJson("COMPLAINT", "URGENT", true)));
    handler.gates.put(2, held);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ReanalyzeResponse> slower = pool.submit(() -> service.reanalyze(base.id));
      assertThat(held.entered.await(10, TimeUnit.SECONDS)).isTrue();
      Future<ReanalyzeResponse> faster = pool.submit(() -> service.reanalyze(base.id));
      ReanalyzeResponse v3 = faster.get(20, TimeUnit.SECONDS);
      assertThat(v3.analysisVersion()).isEqualTo(3);

      Inquiry mid = inquiries.findById(base.id).orElseThrow();
      assertThat(mid.currentAnalysisId).isEqualTo(v3.analysisId());

      held.release.countDown();
      ReanalyzeResponse v2 = slower.get(20, TimeUnit.SECONDS);
      assertThat(v2.analysisVersion()).isEqualTo(2);

      Inquiry finalRow = inquiries.findById(base.id).orElseThrow();
      assertThat(finalRow.currentAnalysisId).isEqualTo(v3.analysisId());
      assertThat(finalRow.analysisStatus).isEqualTo("SUCCESS");
      // EN: The late v2 must not refresh the current fields away from v3.
      // 中文：迟到的 v2 不得把当前字段刷新成与 v3 不一致的值。
      assertThat(finalRow.category).isEqualTo("REFUND");
      assertThat(finalRow.aiCategory).isEqualTo("REFUND");
      assertThat(finalRow.priority).isEqualTo("HIGH");
      assertThat(analyses.findByInquiry_IdOrderByVersionAsc(base.id))
          .extracting(a -> a.version)
          .containsExactly(1, 2, 3);
    } finally {
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("Out-of-order completion: an older result cannot overwrite a newer analysis_status")
  void olderResultCannotOverwriteAnalysisStatus() throws Exception {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    handler.defaultResponse = ok(aiJson("REFUND", "HIGH", true));
    service.reanalyze(base.id); // v1 success

    // v2 succeeds but its response is held. v3 fails fast, so analysis_status becomes FAILED.
    GateResponse held = new GateResponse(ok(aiJson("REFUND", "HIGH", true)));
    handler.gates.put(2, held);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<ReanalyzeResponse> slower = pool.submit(() -> service.reanalyze(base.id));
      assertThat(held.entered.await(10, TimeUnit.SECONDS)).isTrue();
      // EN: v2 is already parked on its own gate, so changing the default response only affects v3,
      // which must fail fast. 中文：v2 已停在自己的闸门上，此时改默认响应只影响需要快速失败的 v3。
      handler.defaultResponse = error(500, "{}");
      Future<ReanalyzeResponse> faster = pool.submit(() -> service.reanalyze(base.id));
      ReanalyzeResponse v3 = faster.get(20, TimeUnit.SECONDS);
      assertThat(v3.analysisStatus()).isEqualTo("FAILED");

      held.release.countDown();
      ReanalyzeResponse v2 = slower.get(20, TimeUnit.SECONDS);
      assertThat(v2.analysisStatus()).as("late v2 response: %s", v2).isEqualTo("SUCCESS");

      Inquiry finalRow = inquiries.findById(base.id).orElseThrow();
      // EN: v3 is the newest attempt; its FAILED status must not be overwritten by the late v2.
      // 中文：v3 是最新尝试；迟到的 v2 不得覆盖它的 FAILED 状态。
      assertThat(finalRow.analysisStatus).isEqualTo("FAILED");
      assertThat(finalRow.currentAnalysisId).isEqualTo(v2.analysisId());
    } finally {
      held.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("A DB failure finalizing analysis leaves no half-success data")
  void dbFailureFinalizingAnalysisRollsBack() {
    Inquiry base = seed("REFUND", "MEDIUM", "AI", "AI");
    String constraint = "CHK_T5_FAIL_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        "ALTER TABLE inquiry_analysis ADD CONSTRAINT "
            + constraint
            + " CHECK (NOT (inquiry_id = "
            + base.id
            + " AND status = 'SUCCESS'))");
    try {
      handler.defaultResponse = ok(aiJson("COMPLAINT", "URGENT", true));
      ReanalyzeResponse result = service.reanalyze(base.id);

      assertThat(result.analysisStatus()).isEqualTo("FAILED");
      Inquiry reloaded = inquiries.findById(base.id).orElseThrow();
      assertThat(reloaded.currentAnalysisId).isNull();
      assertThat(reloaded.category).isEqualTo("REFUND");
      assertThat(reloaded.priority).isEqualTo("MEDIUM");
      assertThat(analyses.findByInquiry_IdAndVersion(base.id, 1).orElseThrow().status)
          .isEqualTo("FAILED");
    } finally {
      jdbc.update("ALTER TABLE inquiry_analysis DROP CONSTRAINT IF EXISTS " + constraint);
    }
  }

  // ------------------------------------------------------------------ helpers

  private InquiryAnalysis appendSuccess(Inquiry inquiry, int version, String category, String priority) {
    com.orderai.workbench.entity.InquiryAnalysis row =
        new com.orderai.workbench.entity.InquiryAnalysis();
    row.inquiry = inquiry;
    row.version = version;
    row.category = category;
    row.priority = priority;
    row.analysisSource = "AI";
    row.status = "SUCCESS";
    return analyses.saveAndFlush(row);
  }

  static String aiJson(String category, String priority, boolean review) {
    return "{\"category\":\""
        + category
        + "\",\"priority\":"
        + (priority == null ? "null" : "\"" + priority + "\"")
        + ",\"confidence\":0.9,\"needs_human_review\":"
        + review
        + ",\"suggested_reply\":\"reply\",\"analysis_source\":\"AI\",\"error_message\":null}";
  }

  static String ruleJson(String category) {
    return "{\"category\":\""
        + category
        + "\",\"priority\":null,\"confidence\":0.5,\"needs_human_review\":true,"
        + "\"suggested_reply\":null,\"analysis_source\":\"RULE_BASED\","
        + "\"error_message\":\"LLM is not configured\"}";
  }

  static StubResponse ok(String body) {
    return new StubResponse(200, body, 0);
  }

  static StubResponse ok(String body, int sleepMs) {
    return new StubResponse(200, body, sleepMs);
  }

  static StubResponse error(int status, String body) {
    return new StubResponse(status, body, 0);
  }

  record StubResponse(int status, String body, int sleepMs) {}

  static class GateResponse {
    final StubResponse response;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    GateResponse(StubResponse response) {
      this.response = response;
    }
  }

  static class StubHandler implements HttpHandler {
    volatile StubResponse defaultResponse = ok("{}");
    final Map<Integer, GateResponse> gates = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();

    void reset() {
      gates.clear();
      calls.set(0);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      int n = calls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      GateResponse gate = gates.get(n);
      StubResponse response;
      if (gate != null) {
        gate.entered.countDown();
        try {
          if (!gate.release.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for gate release");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted", e);
        }
        response = gate.response;
      } else {
        response = defaultResponse;
      }
      if (response.sleepMs() > 0) {
        try {
          Thread.sleep(response.sleepMs());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(response.status(), bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }
}
