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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * EN: Integration tests for the Spring Boot -> Python boundary using an in-JVM HTTP stub.
 * No real Python service or network is required; every scenario is controlled locally.
 *
 * 中文：使用 JVM 内 HTTP 桩服务测试 Spring Boot -> Python 边界。
 * 不需要真实 Python 服务或外网；每个场景都在本地精确控制。
 */
class InquiryAiIntegrationTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String EMAIL = "private.customer@example.com";
  private static final String BODY = "PRIVATE_BODY_MARKER order not shipped";
  private static final String SUBJECT = "PRIVATE_SUBJECT_MARKER";

  private HttpServer server;
  private StubHandler handler;
  private InquiryRepository repo;
  private InquiryServiceImpl service;

  @BeforeEach
  void startStub() throws IOException {
    handler = new StubHandler();
    server = startServer(handler);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    // EN: Mock repository: no replay, assign an id on save.
    // 中文：Mock repository：不返回幂等重放，保存时分配 id。
    repo = mock(InquiryRepository.class);
    when(repo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    when(repo.saveAndFlush(any(Inquiry.class))).thenAnswer(invocation -> {
      Inquiry entity = invocation.getArgument(0);
      entity.id = 1L;
      return entity;
    });
    service = new InquiryServiceImpl(repo, MAPPER, RestClient.builder(), baseUrl, 3, 5);
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
    handler.response = response(200,
        "{\"category\":\"ORDER_QUERY\",\"confidence\":0.95,\"needs_human_review\":false,"
            + "\"suggested_reply\":\"Track your order.\",\"analysis_source\":\"AI\","
            + "\"error_message\":null}");

    InquiryResponse result = service.create(payload(), "key-ai-success");

    assertThat(result.inquiryId()).isEqualTo(1L);
    assertThat(result.category()).isEqualTo("ORDER_QUERY");
    assertThat(result.confidence()).isEqualTo(0.95);
    assertThat(result.needsHumanReview()).isFalse();
    assertThat(result.suggestedReply()).isEqualTo("Track your order.");
    assertThat(result.analysisSource()).isEqualTo("AI");
    assertThat(result.replayed()).isFalse();
    assertThat(handler.method.get()).isEqualTo("POST");
    assertThat(handler.requestId.get()).isNotBlank();

    // EN: Every analysis field must reach the database together with the inquiry.
    // 中文：所有分析字段必须与咨询记录一起写入数据库。
    ArgumentCaptor<Inquiry> captor = ArgumentCaptor.forClass(Inquiry.class);
    verify(repo).saveAndFlush(captor.capture());
    Inquiry saved = captor.getValue();
    assertThat(saved.category).isEqualTo("ORDER_QUERY");
    assertThat(saved.confidence).isEqualTo(0.95);
    assertThat(saved.needsHumanReview).isFalse();
    assertThat(saved.suggestedReply).isEqualTo("Track your order.");
    assertThat(saved.analysisSource).isEqualTo("AI");
  }

  @Test
  @DisplayName("Python returns RULE_BASED result")
  void ruleBasedResult() {
    handler.response = response(200,
        "{\"category\":\"OTHER\",\"confidence\":0.5,\"needs_human_review\":true,"
            + "\"suggested_reply\":null,\"analysis_source\":\"RULE_BASED\","
            + "\"error_message\":\"LLM is not configured\"}");

    InquiryResponse result = service.create(payload(), "key-rule-based");

    assertThat(result.category()).isEqualTo("OTHER");
    assertThat(result.confidence()).isEqualTo(0.5);
    assertThat(result.needsHumanReview()).isTrue();
    assertThat(result.suggestedReply()).isNull();
    assertThat(result.analysisSource()).isEqualTo("RULE_BASED");
    assertThat(result.replayed()).isFalse();
    // EN: The internal diagnostic message must never appear in the API payload.
    // 中文：内部诊断信息绝不能出现在 API 响应中。
    assertThat(MAPPER.valueToTree(result).has("error_message")).isFalse();

    ArgumentCaptor<Inquiry> captor = ArgumentCaptor.forClass(Inquiry.class);
    verify(repo).saveAndFlush(captor.capture());
    Inquiry saved = captor.getValue();
    assertThat(saved.confidence).isEqualTo(0.5);
    assertThat(saved.needsHumanReview).isTrue();
    assertThat(saved.suggestedReply).isNull();
    assertThat(saved.analysisSource).isEqualTo("RULE_BASED");
  }

  @Test
  @DisplayName("Idempotency replay returns the original complete result without calling Python again")
  void replayReturnsOriginalAnalysis() {
    Inquiry stored =
        new Inquiry(
            "key-replay", EMAIL, SUBJECT, BODY, "COMPLAINT", 0.8, true, "We will follow up", "AI");
    stored.id = 42L;
    when(repo.findByIdempotencyKey("key-replay")).thenReturn(Optional.of(stored));

    InquiryResponse result = service.create(payload(), "key-replay");

    assertThat(result.inquiryId()).isEqualTo(42L);
    assertThat(result.category()).isEqualTo("COMPLAINT");
    assertThat(result.confidence()).isEqualTo(0.8);
    assertThat(result.needsHumanReview()).isTrue();
    assertThat(result.suggestedReply()).isEqualTo("We will follow up");
    assertThat(result.analysisSource()).isEqualTo("AI");
    assertThat(result.replayed()).isTrue();
    assertThat(handler.method.get()).isNull(); // EN: no HTTP call. 中文：没有发生 HTTP 调用。
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("Management list view carries every analysis field and no error_message")
  void managementListIncludesAnalysisFields() {
    Inquiry row =
        new Inquiry("key-list", EMAIL, SUBJECT, BODY, "REFUND", 0.75, true, "Refund reply", "AI");
    row.id = 7L;
    InquiryRepository listRepo = mock(InquiryRepository.class);
    when(listRepo.findAll(
            ArgumentMatchers.<Specification<Inquiry>>any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(row)));
    InquiryController controller =
        new InquiryController(mock(InquiryService.class), listRepo);

    ApiResponse<Page<InquiryManagementResponse>> response =
        controller.list(null, null, null, null, null, PageRequest.of(0, 10));
    InquiryManagementResponse view = response.data().getContent().get(0);

    assertThat(view.id()).isEqualTo(7L);
    assertThat(view.confidence()).isEqualTo(0.75);
    assertThat(view.needsHumanReview()).isTrue();
    assertThat(view.suggestedReply()).isEqualTo("Refund reply");
    assertThat(view.analysisSource()).isEqualTo("AI");
    JsonNode json = MAPPER.valueToTree(view);
    assertThat(json.has("needs_human_review")).isTrue();
    assertThat(json.has("suggested_reply")).isTrue();
    assertThat(json.has("analysis_source")).isTrue();
    assertThat(json.has("error_message")).isFalse();
  }

  @Test
  @DisplayName("Python returns 500 -> 502 and nothing persisted")
  void pythonServerError() {
    handler.response = response(500, "{\"detail\":\"boom\"}");

    assertThatThrownBy(() -> service.create(payload(), "key-500"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY));

    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("Python connection failure -> 502")
  void connectionFailure() {
    server.stop(0); // EN: close the stub so the port refuses connections. 中文：关闭桩服务，让端口拒绝连接。

    assertThatThrownBy(() -> service.create(payload(), "key-conn-fail"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY));
  }

  @Test
  @DisplayName("Python call timeout -> 502")
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

    assertThatThrownBy(() -> service.create(payload(), "key-timeout"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY));
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
      handler.response = response(200,
          "{\"category\":\"ORDER_QUERY\",\"confidence\":0.9,\"needs_human_review\":false,"
              + "\"analysis_source\":\"AI\",\"error_message\":null}");
      service.create(payload(), "key-log-success");

      // Failure path: only the exception type is logged.
      server.stop(0);
      assertThatThrownBy(() -> service.create(payload(), "key-log-fail"))
          .isInstanceOf(ResponseStatusException.class);

      String all = String.join("\n", appender.list.stream().map(e -> e.getFormattedMessage()).toList());
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
    when(listRepo.findAll(
            ArgumentMatchers.<Specification<Inquiry>>any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(legacy)));
    InquiryController controller = new InquiryController(mock(InquiryService.class), listRepo);

    InquiryManagementResponse view =
        controller.list(null, null, null, null, null, PageRequest.of(0, 10))
            .data()
            .getContent()
            .get(0);

    assertThat(view.id()).isEqualTo(9L);
    assertThat(view.category()).isEqualTo("OTHER");
    assertThat(view.confidence()).isNull();
    assertThat(view.needsHumanReview()).isNull();
    assertThat(view.suggestedReply()).isNull();
    assertThat(view.analysisSource()).isNull();
  }

  @Test
  @DisplayName("Invalid category -> 502 and nothing persisted")
  void invalidCategory() {
    assertRejected(analysisJson("UNKNOWN_CATEGORY", "0.9", "false", "reply", "AI"));
  }

  @ParameterizedTest(name = "confidence {0} is rejected with 502")
  @ValueSource(strings = {"null", "1.1", "-0.01", "NaN", "1e309", "-1e309"})
  void invalidConfidence(String confidence) {
    assertRejected(analysisJson("ORDER_QUERY", confidence, "false", "reply", "AI"));
  }

  @Test
  @DisplayName("Invalid analysis source -> 502 and nothing persisted")
  void invalidAnalysisSource() {
    assertRejected(analysisJson("ORDER_QUERY", "0.9", "false", "reply", "GROQ"));
  }

  @Test
  @DisplayName("Missing review flag -> 502 and nothing persisted")
  void missingReviewFlag() {
    assertRejected(analysisJson("ORDER_QUERY", "0.9", "null", "reply", "AI"));
  }

  @Test
  @DisplayName("Review flag inconsistent with category -> 502 and nothing persisted")
  void inconsistentReviewFlag() {
    // EN: REFUND/COMPLAINT/OTHER require true; ORDER_QUERY/PRODUCT_INQUIRY require false.
    // 中文：REFUND/COMPLAINT/OTHER 必须为 true；ORDER_QUERY/PRODUCT_INQUIRY 必须为 false。
    assertRejected(analysisJson("REFUND", "0.9", "false", "reply", "AI"));
    assertRejected(analysisJson("ORDER_QUERY", "0.9", "true", "reply", "AI"));
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
  }

  // ---------- helpers ----------

  private void assertRejected(String json) {
    handler.response = response(200, json);
    assertThatThrownBy(() -> service.create(payload(), "key-invalid"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY));
    verify(repo, never()).saveAndFlush(any());
  }

  /** EN: Build analysis JSON with raw values so missing/invalid fields are representable. 中文：用原始片段拼接 JSON，便于表达缺失或非法值。 */
  private static String analysisJson(
      String category, String confidence, String needsReview, String suggestedReply, String source) {
    return "{\"category\":" + jsonStringOrNull(category)
        + ",\"confidence\":" + confidence
        + ",\"needs_human_review\":" + needsReview
        + ",\"suggested_reply\":" + jsonStringOrNull(suggestedReply)
        + ",\"analysis_source\":" + jsonStringOrNull(source)
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

    @Override
    public void handle(HttpExchange exchange) throws IOException {
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
