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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderai.workbench.dto.response.InquiryAnalysisResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.impl.InquiryServiceImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
import org.slf4j.LoggerFactory;
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

    assertThat(result.category()).isEqualTo("ORDER_QUERY");
    assertThat(result.needsHumanReview()).isFalse();
    assertThat(result.replayed()).isFalse();
    assertThat(handler.method.get()).isEqualTo("POST");
    assertThat(handler.requestId.get()).isNotBlank();
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
    assertThat(result.needsHumanReview()).isTrue();
    assertThat(result.replayed()).isFalse();
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

  // ---------- helpers ----------

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
