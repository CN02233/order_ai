package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderai.workbench.api.ApiResponse;
import com.orderai.workbench.controller.InquiryController;
import com.orderai.workbench.dto.request.InquiryUpdateRequest;
import com.orderai.workbench.dto.response.InquiryAnalysisResponse;
import com.orderai.workbench.dto.response.InquiryManagementResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import com.orderai.workbench.service.impl.InquiryServiceImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * EN: Integration tests for the Spring Boot -> Python boundary using an in-JVM HTTP stub. No real
 * Python service or network is required; every scenario is controlled locally. A stateful in-memory
 * repository mock simulates the UNIQUE constraint on idempotency_key so idempotency and concurrency
 * are exercised against DB-like atomicity, not a single-threaded illusion.
 *
 * <p>中文：使用 JVM 内 HTTP 桩服务测试 Spring Boot -> Python 边界。不需要真实 Python 服务或外网；每个场景都在本地精确控制。 有状态内存
 * repository mock 模拟 idempotency_key 的唯一约束，使幂等与并发测试基于类数据库的原子性，而非单线程假象。
 */
class InquiryAiIntegrationTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String EMAIL = "private.customer@example.com";
  private static final String BODY = "PRIVATE_BODY_MARKER order not shipped";
  private static final String SUBJECT = "PRIVATE_SUBJECT_MARKER";
  private static final String AI_JSON =
      "{\"category\":\"ORDER_QUERY\",\"confidence\":0.95,\"needs_human_review\":false,"
          + "\"suggested_reply\":\"Track your order.\",\"analysis_source\":\"AI\","
          + "\"error_message\":null}";

  private HttpServer server;
  private StubHandler handler;
  private InquiryRepository repo;
  private InquiryServiceImpl service;

  // EN: Stateful fake store + write snapshots, so tests can assert the two-phase write order.
  // 中文：有状态假存储 + 写入快照，便于测试断言两阶段写入顺序。
  private Map<String, Inquiry> store;
  private List<Inquiry> savedSnapshots;
  private AtomicLong ids;
  private AtomicInteger saveCalls;
  private int failSaveOnCall; // EN: 0 = no injected failure. 中文：0 表示不注入失败。

  @BeforeEach
  void startStub() throws IOException {
    handler = new StubHandler();
    server = startServer(handler);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    setupStatefulRepo();
    service = new InquiryServiceImpl(repo, MAPPER, RestClient.builder(), baseUrl, 3, 5);
  }

  /**
   * EN: A mock repository with DB-like semantics: find by key reads the store; saveAndFlush INSERTs
   * (enforcing the unique key) when id is null and UPDATEs otherwise. Each save archives an
   * immutable snapshot so tests can prove the raw inquiry was committed before any analysis was
   * attached. 中文：具备类数据库语义的 mock repository：按 key 查询读存储；id 为空时 saveAndFlush 执行 INSERT（强制唯一键）， 否则执行
   * UPDATE。每次保存归档一个不可变快照，从而证明原始咨询在分析字段写入之前就已提交。
   */
  private void setupStatefulRepo() {
    store = new ConcurrentHashMap<>();
    savedSnapshots = new CopyOnWriteArrayList<>();
    ids = new AtomicLong();
    saveCalls = new AtomicInteger();
    failSaveOnCall = 0;
    repo = mock(InquiryRepository.class);
    when(repo.findByIdempotencyKey(anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
    when(repo.saveAndFlush(any(Inquiry.class)))
        .thenAnswer(
            invocation -> {
              // EN: Fault injection: fail at the Nth saveAndFlush while earlier writes stay
              // committed.
              // 中文：故障注入：在第 N 次 saveAndFlush 失败，之前的写入保持已提交。
              if (failSaveOnCall > 0 && saveCalls.incrementAndGet() == failSaveOnCall) {
                throw new RuntimeException("simulated DB failure");
              }
              Inquiry src = invocation.getArgument(0);
              Inquiry returned = copy(src);
              Inquiry snapshot = copy(src);
              synchronized (store) {
                if (src.id == null) {
                  if (store.containsKey(src.idempotencyKey)) {
                    throw new DataIntegrityViolationException("duplicate key");
                  }
                  returned.id = ids.incrementAndGet();
                  snapshot.id = returned.id;
                  store.put(src.idempotencyKey, returned);
                } else {
                  returned.id = src.id;
                  snapshot.id = src.id;
                  store.put(src.idempotencyKey, returned);
                }
              }
              savedSnapshots.add(snapshot);
              return returned;
            });
  }

  private static Inquiry copy(Inquiry s) {
    Inquiry c =
        new Inquiry(
            s.idempotencyKey,
            s.customerEmail,
            s.subject,
            s.body,
            s.category,
            s.aiCategory,
            s.confidence,
            s.needsHumanReview,
            s.suggestedReply,
            s.analysisSource);
    c.id = s.id;
    return c;
  }

  @AfterEach
  void stopStub() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static HttpServer startServer(StubHandler handler) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/analyze-inquiry", handler);
    s.start();
    return s;
  }

  private Map<String, String> payload() {
    return Map.of("customer_email", EMAIL, "subject", SUBJECT, "body", BODY);
  }

  @Test
  @DisplayName("Python returns AI success result")
  void aiSuccess() {
    handler.response = response(200, AI_JSON);

    InquiryResponse result = service.create(payload(), "key-ai-success");

    assertThat(result.inquiryId()).isEqualTo(1L);
    assertThat(result.category()).isEqualTo("ORDER_QUERY");
    // EN: A new inquiry starts with ai_category = category.
    // 中文：新咨询创建时 ai_category 与 category 相同。
    assertThat(result.aiCategory()).isEqualTo("ORDER_QUERY");
    assertThat(result.confidence()).isEqualTo(0.95);
    assertThat(result.needsHumanReview()).isFalse();
    assertThat(result.suggestedReply()).isEqualTo("Track your order.");
    assertThat(result.analysisSource()).isEqualTo("AI");
    assertThat(result.analysisStatus()).isEqualTo("SUCCESS");
    assertThat(result.analysisError()).isNull();
    assertThat(result.replayed()).isFalse();
    assertThat(handler.method.get()).isEqualTo("POST");
    assertThat(handler.requestId.get()).isNotBlank();

    // EN: Two writes: raw first (all AI fields null), then the analysis update.
    // 中文：两次写入：先原始咨询（AI 字段全空），再回填分析结果。
    assertThat(savedSnapshots).hasSize(2);
    Inquiry raw = savedSnapshots.get(0);
    assertThat(raw.customerEmail).isEqualTo(EMAIL);
    assertThat(raw.subject).isEqualTo(SUBJECT);
    assertThat(raw.body).isEqualTo(BODY);
    assertThat(raw.category).isNull();
    assertThat(raw.confidence).isNull();
    assertThat(raw.analysisSource).isNull();
    Inquiry enriched = savedSnapshots.get(1);
    assertThat(enriched.category).isEqualTo("ORDER_QUERY");
    assertThat(enriched.aiCategory).isEqualTo("ORDER_QUERY");
    assertThat(enriched.confidence).isEqualTo(0.95);
    assertThat(enriched.needsHumanReview).isFalse();
    assertThat(enriched.suggestedReply).isEqualTo("Track your order.");
    assertThat(enriched.analysisSource).isEqualTo("AI");
  }

  @Test
  @DisplayName("Python returns RULE_BASED result")
  void ruleBasedResult() {
    handler.response =
        response(
            200,
            "{\"category\":\"OTHER\",\"confidence\":0.5,\"needs_human_review\":true,"
                + "\"suggested_reply\":null,\"analysis_source\":\"RULE_BASED\","
                + "\"error_message\":\"LLM is not configured\"}");

    InquiryResponse result = service.create(payload(), "key-rule-based");

    assertThat(result.category()).isEqualTo("OTHER");
    assertThat(result.aiCategory()).isEqualTo("OTHER");
    assertThat(result.confidence()).isEqualTo(0.5);
    assertThat(result.needsHumanReview()).isTrue();
    assertThat(result.suggestedReply()).isNull();
    assertThat(result.analysisSource()).isEqualTo("RULE_BASED");
    assertThat(result.analysisStatus()).isEqualTo("SUCCESS");
    assertThat(result.replayed()).isFalse();
    // EN: The internal diagnostic message must never appear in the API payload.
    // 中文：内部诊断信息绝不能出现在 API 响应中。
    assertThat(MAPPER.valueToTree(result).has("error_message")).isFalse();

    assertThat(savedSnapshots).hasSize(2);
    Inquiry enriched = savedSnapshots.get(1);
    assertThat(enriched.confidence).isEqualTo(0.5);
    assertThat(enriched.needsHumanReview).isTrue();
    assertThat(enriched.suggestedReply).isNull();
    assertThat(enriched.analysisSource).isEqualTo("RULE_BASED");
  }

  @Test
  @DisplayName(
      "Idempotency replay returns the original complete result without calling Python again")
  void replayReturnsOriginalAnalysis() {
    Inquiry stored =
        new Inquiry(
            "key-replay", EMAIL, SUBJECT, BODY, "COMPLAINT", 0.8, true, "We will follow up", "AI");
    // EN: A human already changed the business category; the AI snapshot stays COMPLAINT.
    // 中文：人工已将业务分类改为 ORDER_QUERY，AI 快照仍为 COMPLAINT。
    stored.category = "ORDER_QUERY";
    stored.id = 42L;
    when(repo.findByIdempotencyKey("key-replay")).thenReturn(Optional.of(stored));

    InquiryResponse result = service.create(payload(), "key-replay");

    assertThat(result.inquiryId()).isEqualTo(42L);
    assertThat(result.category()).isEqualTo("ORDER_QUERY");
    assertThat(result.aiCategory()).isEqualTo("COMPLAINT");
    assertThat(result.confidence()).isEqualTo(0.8);
    assertThat(result.needsHumanReview()).isTrue();
    assertThat(result.suggestedReply()).isEqualTo("We will follow up");
    assertThat(result.analysisSource()).isEqualTo("AI");
    assertThat(result.analysisStatus()).isEqualTo("SUCCESS");
    assertThat(result.replayed()).isTrue();
    assertThat(handler.callCount.get()).isZero(); // EN: no HTTP call. 中文：没有发生 HTTP 调用。
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("Management list view carries every analysis field and no error_message")
  void managementListIncludesAnalysisFields() {
    Inquiry row =
        new Inquiry("key-list", EMAIL, SUBJECT, BODY, "REFUND", 0.75, true, "Refund reply", "AI");
    row.id = 7L;
    InquiryRepository listRepo = mock(InquiryRepository.class);
    when(listRepo.findAll(ArgumentMatchers.<Specification<Inquiry>>any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(row)));
    InquiryController controller = new InquiryController(mock(InquiryService.class), listRepo);

    ApiResponse<Page<InquiryManagementResponse>> response =
        controller.list(null, null, null, null, null, PageRequest.of(0, 10));
    InquiryManagementResponse view = response.data().getContent().get(0);

    assertThat(view.id()).isEqualTo(7L);
    assertThat(view.aiCategory()).isEqualTo("REFUND");
    assertThat(view.confidence()).isEqualTo(0.75);
    assertThat(view.needsHumanReview()).isTrue();
    assertThat(view.suggestedReply()).isEqualTo("Refund reply");
    assertThat(view.analysisSource()).isEqualTo("AI");
    JsonNode json = MAPPER.valueToTree(view);
    assertThat(json.has("ai_category")).isTrue();
    assertThat(json.has("needs_human_review")).isTrue();
    assertThat(json.has("suggested_reply")).isTrue();
    assertThat(json.has("analysis_source")).isTrue();
    assertThat(json.has("error_message")).isFalse();
  }

  @Test
  @DisplayName("Detail endpoint returns the full AI snapshot of one inquiry")
  void detailIncludesAnalysisFields() {
    Inquiry row =
        new Inquiry(
            "key-detail",
            EMAIL,
            SUBJECT,
            BODY,
            "PRODUCT_INQUIRY",
            "PRODUCT_INQUIRY",
            0.92,
            false,
            "Here are the specs.",
            "AI");
    row.id = 8L;
    InquiryRepository detailRepo = mock(InquiryRepository.class);
    when(detailRepo.findById(8L)).thenReturn(Optional.of(row));
    InquiryController controller = new InquiryController(mock(InquiryService.class), detailRepo);

    InquiryManagementResponse view = controller.detail(8L).data();

    assertThat(view.category()).isEqualTo("PRODUCT_INQUIRY");
    assertThat(view.aiCategory()).isEqualTo("PRODUCT_INQUIRY");
    assertThat(view.confidence()).isEqualTo(0.92);
    assertThat(view.needsHumanReview()).isFalse();
    assertThat(view.suggestedReply()).isEqualTo("Here are the specs.");
    assertThat(view.analysisSource()).isEqualTo("AI");
    assertThat(MAPPER.valueToTree(view).has("error_message")).isFalse();
  }

  @Test
  @DisplayName("Manual category edit changes only category, never the AI snapshot")
  void manualEditPreservesAiSnapshot() throws Exception {
    Inquiry stored =
        new Inquiry(
            "key-edit", EMAIL, SUBJECT, BODY, "REFUND", "REFUND", 0.88, true, "Refund reply", "AI");
    stored.id = 9L;
    InquiryRepository editRepo = mock(InquiryRepository.class);
    when(editRepo.findById(9L)).thenReturn(Optional.of(stored));
    when(editRepo.save(any(Inquiry.class))).thenAnswer(invocation -> invocation.getArgument(0));
    InquiryController controller = new InquiryController(mock(InquiryService.class), editRepo);

    // EN: Even if a client sends AI fields, the request DTO has nowhere to store them.
    // 中文：即便客户端夹带 AI 字段，请求 DTO 也没有地方保存它们。
    InquiryUpdateRequest request =
        MAPPER.readValue(
            "{\"category\":\"COMPLAINT\",\"ai_category\":\"ORDER_QUERY\",\"confidence\":0.01,"
                + "\"needs_human_review\":false,\"suggested_reply\":\"forged\",\"analysis_source\":\"RULE_BASED\"}",
            InquiryUpdateRequest.class);
    InquiryManagementResponse view = controller.update(9L, request).data();

    assertThat(stored.category).isEqualTo("COMPLAINT");
    assertThat(stored.aiCategory).isEqualTo("REFUND");
    assertThat(stored.confidence).isEqualTo(0.88);
    assertThat(stored.needsHumanReview).isTrue();
    assertThat(stored.suggestedReply).isEqualTo("Refund reply");
    assertThat(stored.analysisSource).isEqualTo("AI");
    assertThat(view.aiCategory()).isEqualTo("REFUND");
    assertThat(view.confidence()).isEqualTo(0.88);
  }

  @Test
  @DisplayName("Python returns 500 -> inquiry kept, analysis FAILED, no misleading create error")
  void pythonServerError() {
    handler.response = response(500, "{\"detail\":\"boom\"}");

    InquiryResponse result = service.create(payload(), "key-500");

    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.analysisError()).isEqualTo("AI_HTTP_ERROR");
    assertThat(result.category()).isNull();
    assertThat(result.replayed()).isFalse();
    // EN: Original inquiry was committed before the HTTP call and is NOT rolled back.
    // 中文：原始咨询在 HTTP 调用前已提交，绝不回滚。
    assertThat(savedSnapshots).hasSize(1);
    assertThat(savedSnapshots.get(0).customerEmail).isEqualTo(EMAIL);
    assertThat(savedSnapshots.get(0).body).isEqualTo(BODY);
  }

  @Test
  @DisplayName("Python connection failure -> inquiry kept, analysis FAILED (AI_UNAVAILABLE)")
  void connectionFailure() {
    server.stop(0); // EN: close the stub so the port refuses connections. 中文：关闭桩服务，让端口拒绝连接。

    InquiryResponse result = service.create(payload(), "key-conn-fail");

    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.analysisError()).isEqualTo("AI_UNAVAILABLE");
    assertThat(savedSnapshots).hasSize(1);
    assertThat(savedSnapshots.get(0).customerEmail).isEqualTo(EMAIL);
  }

  @Test
  @DisplayName("Python call timeout -> inquiry kept, analysis FAILED (AI_TIMEOUT)")
  void pythonTimeout() throws IOException {
    // EN: Rebind on a fresh port with a handler that sleeps longer than the client read timeout.
    // 中文：在新端口重新绑定，处理器 sleep 时间超过客户端读超时。
    server.stop(0);
    handler.response = response(200, "{}", 2000);
    server = startServer(handler);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    // EN: 1s read timeout, stub sleeps 2s -> timeout is guaranteed.
    // 中文：读超时 1 秒，桩服务 sleep 2 秒，必然超时。
    service = new InquiryServiceImpl(repo, MAPPER, RestClient.builder(), baseUrl, 3, 1);

    InquiryResponse result = service.create(payload(), "key-timeout");

    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.analysisError()).isEqualTo("AI_TIMEOUT");
    assertThat(savedSnapshots).hasSize(1);
    assertThat(savedSnapshots.get(0).customerEmail).isEqualTo(EMAIL);
  }

  @Test
  @DisplayName("New Python fields are deserialized by Jackson, unknown fields tolerated")
  void jacksonDeserializesNewFields() throws Exception {
    String json =
        "{\"category\":\"REFUND\",\"confidence\":0.7,\"needs_human_review\":true,"
            + "\"suggested_reply\":\"We will review your refund.\",\"analysis_source\":\"AI\","
            + "\"error_message\":null,\"future_field\":\"ignored\"}";

    InquiryAnalysisResponse parsed = MAPPER.readValue(json, InquiryAnalysisResponse.class);

    assertThat(parsed.category()).isEqualTo("REFUND");
    assertThat(parsed.confidence()).isEqualTo(0.7);
    assertThat(parsed.needsHumanReview()).isTrue();
    assertThat(parsed.suggestedReply()).isEqualTo("We will review your refund.");
    assertThat(parsed.analysisSource()).isEqualTo("AI");
    assertThat(parsed.errorMessage()).isNull();
  }

  @Test
  @DisplayName("Configured timeouts: connect 3s, read 20s by default")
  void defaultTimeouts() {
    InquiryServiceImpl defaults =
        new InquiryServiceImpl(repo, MAPPER, RestClient.builder(), "http://127.0.0.1:8000", 3, 20);

    assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(defaults.readTimeout()).isEqualTo(Duration.ofSeconds(20));
  }

  @Test
  @DisplayName("Logs never contain customer email, subject, body or exception message")
  void logsAreRedacted() {
    Logger logger = (Logger) LoggerFactory.getLogger(InquiryServiceImpl.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      // Success path: request/response/result logs.
      handler.response = response(200, AI_JSON);
      service.create(payload(), "key-log-success");

      // Failure path: only the exception type is logged, no throwable reaches the logger.
      server.stop(0);
      service.create(payload(), "key-log-fail");

      String all =
          String.join("\n", appender.list.stream().map(e -> e.getFormattedMessage()).toList());
      assertThat(all).doesNotContain(EMAIL, SUBJECT, BODY);
      assertThat(all).doesNotContain("PRIVATE_");
      assertThat(all).doesNotContain("jsonBody");
      assertThat(all).doesNotContain("\tat "); // EN: no stack frames. 中文：无堆栈帧。
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("Historical inquiry without analysis data stays readable with null analysis fields")
  void historicalInquiryRemainsReadable() {
    Inquiry legacy = new Inquiry("key-legacy", EMAIL, SUBJECT, BODY, "OTHER");
    legacy.id = 9L;
    InquiryRepository listRepo = mock(InquiryRepository.class);
    when(listRepo.findAll(ArgumentMatchers.<Specification<Inquiry>>any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(legacy)));
    InquiryController controller = new InquiryController(mock(InquiryService.class), listRepo);

    InquiryManagementResponse view =
        controller
            .list(null, null, null, null, null, PageRequest.of(0, 10))
            .data()
            .getContent()
            .get(0);

    assertThat(view.id()).isEqualTo(9L);
    assertThat(view.category()).isEqualTo("OTHER");
    assertThat(view.aiCategory()).isNull();
    assertThat(view.confidence()).isNull();
    assertThat(view.needsHumanReview()).isNull();
    assertThat(view.suggestedReply()).isNull();
    assertThat(view.analysisSource()).isNull();
  }

  @Test
  @DisplayName("Invalid category -> inquiry kept, analysis FAILED (AI_INVALID_RESPONSE)")
  void invalidCategory() {
    assertAnalysisFailed(analysisJson("UNKNOWN_CATEGORY", "0.9", "false", "reply", "AI"));
  }

  @ParameterizedTest(name = "confidence {0} rejected with FAILED")
  @ValueSource(strings = {"null", "1.1", "-0.01", "NaN", "1e309", "-1e309"})
  void invalidConfidence(String confidence) {
    assertAnalysisFailed(analysisJson("ORDER_QUERY", confidence, "false", "reply", "AI"));
  }

  @Test
  @DisplayName("Invalid analysis source -> inquiry kept, analysis FAILED")
  void invalidAnalysisSource() {
    assertAnalysisFailed(analysisJson("ORDER_QUERY", "0.9", "false", "reply", "GROQ"));
  }

  @Test
  @DisplayName("Missing review flag -> inquiry kept, analysis FAILED")
  void missingReviewFlag() {
    assertAnalysisFailed(analysisJson("ORDER_QUERY", "0.9", "null", "reply", "AI"));
  }

  @Test
  @DisplayName("Review flag inconsistent with category -> inquiry kept, analysis FAILED")
  void inconsistentReviewFlag() {
    // EN: REFUND/COMPLAINT/OTHER require true; ORDER_QUERY/PRODUCT_INQUIRY require false.
    // 中文：REFUND/COMPLAINT/OTHER 必须为 true；ORDER_QUERY/PRODUCT_INQUIRY 必须为 false。
    assertAnalysisFailed(analysisJson("REFUND", "0.9", "false", "reply", "AI"));
    assertAnalysisFailed(analysisJson("ORDER_QUERY", "0.9", "true", "reply", "AI"));
  }

  @Test
  @DisplayName("Malformed JSON -> inquiry kept, analysis FAILED (AI_INVALID_RESPONSE)")
  void malformedJson() {
    handler.response = response(200, "not-json-at-all");

    InquiryResponse result = service.create(payload(), "key-bad-json");

    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.analysisError()).isEqualTo("AI_INVALID_RESPONSE");
    assertThat(savedSnapshots).hasSize(1);
    assertThat(savedSnapshots.get(0).customerEmail).isEqualTo(EMAIL);
  }

  @Test
  @DisplayName("AI succeeded but DB analysis update failed -> inquiry kept, FAILED result")
  void dbUpdateFailureKeepsRawInquiry() {
    handler.response = response(200, AI_JSON);
    failSaveOnCall =
        2; // EN: raw INSERT (1st) succeeds, analysis UPDATE (2nd) fails. 中文：原始 INSERT 成功，分析 UPDATE
    // 失败。

    InquiryResponse result = service.create(payload(), "key-db-fail");

    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.analysisError()).isEqualTo("AI_PERSIST_FAILED");
    // EN: Never expose AI fields that were NOT persisted. 中文：绝不暴露未落库的 AI 字段。
    assertThat(result.category()).isNull();
    assertThat(result.confidence()).isNull();
    assertThat(result.analysisSource()).isNull();
    assertThat(saveCalls.get()).isEqualTo(2);
    // EN: The raw INSERT (first save) succeeded and stayed clean; only the analysis UPDATE failed.
    // 中文：原始 INSERT（第一次 save）成功且保持干净；仅分析 UPDATE 失败。
    assertThat(store.get("key-db-fail")).isNotNull();
    assertThat(savedSnapshots).hasSize(1); // EN: only the raw write committed. 中文：只有原始写入已提交。
    assertThat(savedSnapshots.get(0).analysisSource).isNull();
  }

  @Test
  @DisplayName("Blank idempotency key -> 400, nothing persisted, no Python call")
  void blankKeyRejected() {
    assertThatThrownBy(() -> service.create(payload(), "  "))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(savedSnapshots).isEmpty();
    assertThat(handler.callCount.get()).isZero();
  }

  @Test
  @DisplayName("Same key submitted twice with different content returns the original record")
  void sameKeyDifferentContentReturnsOriginal() {
    handler.response = response(200, AI_JSON);
    InquiryResponse first = service.create(payload(), "key-same");

    Map<String, String> different =
        Map.of(
            "customer_email", "another@example.com", "subject", "changed", "body", "changed body");
    InquiryResponse second = service.create(different, "key-same");

    assertThat(second.inquiryId()).isEqualTo(first.inquiryId());
    assertThat(second.category()).isEqualTo("ORDER_QUERY");
    assertThat(second.replayed()).isTrue();
    assertThat(second.analysisStatus()).isEqualTo("SUCCESS");
    assertThat(store).hasSize(1);
    assertThat(handler.callCount.get()).isEqualTo(1); // EN: no second Python call. 中文：未二次调用 Python。
  }

  @Test
  @DisplayName("Different keys create different inquiries and each calls Python once")
  void differentKeysCreateDifferentInquiries() {
    handler.response = response(200, AI_JSON);

    InquiryResponse a = service.create(payload(), "key-a");
    InquiryResponse b = service.create(payload(), "key-b");

    assertThat(a.inquiryId()).isNotEqualTo(b.inquiryId());
    assertThat(a.replayed()).isFalse();
    assertThat(b.replayed()).isFalse();
    assertThat(store).hasSize(2);
    assertThat(handler.callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("Two concurrent requests with the same key create one inquiry and call Python once")
  void concurrentSameKeyCreatesOnceAndCallsPythonOnce() throws Exception {
    handler.response = response(200, AI_JSON);
    int threads = 2;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    List<InquiryResponse> results = new CopyOnWriteArrayList<>();

    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              start.await();
              results.add(service.create(payload(), "key-concurrent"));
            } catch (Exception ignored) {
              // EN: recorded via results size below. 中文：通过 results 数量体现。
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    boolean finished = done.await(10, TimeUnit.SECONDS);
    pool.shutdownNow();

    assertThat(finished).isTrue();
    assertThat(results).hasSize(2);
    assertThat(store).hasSize(1); // EN: no duplicate inquiry. 中文：无重复咨询。
    assertThat(handler.callCount.get()).isEqualTo(1); // EN: no duplicate AI call. 中文：无重复 AI 调用。
    assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    assertThat(results.stream().filter(r -> r.replayed()).count()).isEqualTo(1);
    assertThat(results.stream().anyMatch(r -> "SUCCESS".equals(r.analysisStatus()))).isTrue();
  }

  @Test
  @DisplayName("schema.sql and the V6 migration declare the same nullable analysis columns")
  void schemaAndMigrationMatch() throws IOException {
    String schema = resource("/schema.sql");
    String migration = resource("/db/migration/V6__persist_inquiry_ai_analysis.sql");
    for (String column :
        new String[] {
          "confidence DECIMAL(5,4)",
          "needs_human_review BOOLEAN",
          "suggested_reply TEXT",
          "analysis_source VARCHAR(20)"
        }) {
      assertThat(schema).as("schema.sql must declare %s", column).contains(column);
      assertThat(migration).as("V6 migration must declare %s", column).contains(column);
    }
    assertThat(schema).contains("NULL");
    assertThat(migration).contains("ALTER TABLE inquiries", "NULL");

    // EN: V7 adds ai_category without backfilling historical rows from `category`.
    // 中文：V7 增加 ai_category，且不用 `category` 回填历史记录。
    String v7 = resource("/db/migration/V7__distinguish_ai_category.sql");
    assertThat(schema).contains("ai_category VARCHAR(40) NULL");
    assertThat(v7).contains("ALTER TABLE inquiries", "ai_category VARCHAR(40) NULL");
    assertThat(v7).doesNotContain("UPDATE inquiries");
  }

  // ---------- helpers ----------

  private AtomicInteger invalidKeySeq = new AtomicInteger();

  private void assertAnalysisFailed(String json) {
    handler.response = response(200, json);
    InquiryResponse result =
        service.create(payload(), "key-invalid-" + invalidKeySeq.incrementAndGet());
    assertThat(result.analysisStatus()).isEqualTo("FAILED");
    assertThat(result.analysisError()).isEqualTo("AI_INVALID_RESPONSE");
    assertThat(result.category()).isNull();
    assertThat(savedSnapshots).hasSize(invalidKeySeq.get());
    assertThat(savedSnapshots.get(savedSnapshots.size() - 1).customerEmail).isEqualTo(EMAIL);
  }

  /**
   * EN: Build analysis JSON with raw values so missing/invalid fields are representable. 中文：用原始片段拼接
   * JSON，便于表达缺失或非法值。
   */
  private static String analysisJson(
      String category,
      String confidence,
      String needsReview,
      String suggestedReply,
      String source) {
    return "{\"category\":"
        + jsonStringOrNull(category)
        + ",\"confidence\":"
        + confidence
        + ",\"needs_human_review\":"
        + needsReview
        + ",\"suggested_reply\":"
        + jsonStringOrNull(suggestedReply)
        + ",\"analysis_source\":"
        + jsonStringOrNull(source)
        + "}";
  }

  private static String jsonStringOrNull(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private static String resource(String path) throws IOException {
    try (InputStream in = InquiryAiIntegrationTest.class.getResourceAsStream(path)) {
      assertThat(in).as("resource %s must exist", path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static StubResponse response(int status, String body) {
    return new StubResponse(status, body, 0);
  }

  private static StubResponse response(int status, String body, int sleepMs) {
    return new StubResponse(status, body, sleepMs);
  }

  private record StubResponse(int status, String body, int sleepMs) {}

  private static class StubHandler implements com.sun.net.httpserver.HttpHandler {
    volatile StubResponse response = response(200, "{}");
    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicReference<String> requestId = new AtomicReference<>();
    final AtomicInteger callCount = new AtomicInteger();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      callCount.incrementAndGet();
      method.set(exchange.getRequestMethod());
      requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
      // EN: Drain the request body so the client is not blocked. 中文：读掉请求体，避免客户端阻塞。
      exchange.getRequestBody().readAllBytes();
      StubResponse r = response;
      if (r.sleepMs() > 0) {
        try {
          Thread.sleep(r.sleepMs());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(r.status(), bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }
}
