package com.orderai.workbench.service.impl;

import com.orderai.workbench.entity.Inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderai.workbench.dto.InquiryAnalysisRequest;
import com.orderai.workbench.dto.response.InquiryAnalysisResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InquiryServiceImpl implements InquiryService {
  private static final Logger log = LoggerFactory.getLogger(InquiryServiceImpl.class);
  private final InquiryRepository repo;
  private final RestClient pythonClient;
  private final ObjectMapper objectMapper;
  private final String aiBaseUrl;
  private final Duration connectTimeout;
  private final Duration readTimeout;

  public InquiryServiceImpl(
      InquiryRepository r,
      ObjectMapper objectMapper,
      RestClient.Builder restClientBuilder,
      @Value("${app.ai.base-url:http://127.0.0.1:8000}") String url,
      // EN: Timeouts are configurable; defaults keep Java waiting longer than Python's 15s LLM timeout.
      // 中文：超时可配置；默认值保证 Java 等待时间长于 Python 的 15 秒 LLM 超时，避免 Java 先超时。
      @Value("${app.ai.connect-timeout-seconds:3}") long connectTimeoutSeconds,
      @Value("${app.ai.read-timeout-seconds:20}") long readTimeoutSeconds) {
    repo = r;
    this.objectMapper = objectMapper;
    this.aiBaseUrl = url.replaceAll("/+$", "");
    this.connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
    this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(this.connectTimeout);
    requestFactory.setReadTimeout(this.readTimeout);
    this.pythonClient = restClientBuilder.clone().requestFactory(requestFactory).build();
  }

  /** EN: Test seam: expose timeouts so tests can assert the configured values. 中文：测试接缝：暴露超时配置，便于测试断言。 */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  public Duration readTimeout() {
    return readTimeout;
  }

  @Transactional
  public InquiryResponse create(Map<String, String> p, String key) {
    // Return the stored result for retries instead of creating duplicate inquiries.
    Optional<Inquiry> old = repo.findByIdempotencyKey(key);
    if (old.isPresent()) {
      log.info("Inquiry idempotency replay: inquiryId={}", old.get().id);
      return new InquiryResponse(old.get().id, old.get().category, null, true);
    }
    // Delegate content analysis to Python; Java owns persistence and business workflow.
    InquiryAnalysisRequest request =
        new InquiryAnalysisRequest(
            p.get("customer_email"), p.getOrDefault("subject", ""), p.get("body"));
    // EN: Log only presence/length. Never log the email, subject or body values.
    // 中文：只记录是否存在与长度，绝不记录邮箱、主题或正文内容。
    log.info("Calling inquiry analysis service: customerEmailPresent={} subjectLength={} bodyLength={}",
        request.customerEmail() != null,
        request.subject() == null ? 0 : request.subject().length(),
        request.body() == null ? 0 : request.body().length());
    InquiryAnalysisResponse analysis = analyzeWithPython(request);
    Inquiry saved = repo.saveAndFlush(
        new Inquiry(
            key,
            p.get("customer_email"),
            p.getOrDefault("subject", ""),
            p.get("body"),
            analysis.category()));
    log.info("Inquiry persisted: inquiryId={} category={}", saved.id, saved.category);
    return new InquiryResponse(
        saved.id, analysis.category(), analysis.needsHumanReview(), false);
  }

  private InquiryAnalysisResponse analyzeWithPython(InquiryAnalysisRequest payload) {
    String requestId = UUID.randomUUID().toString();
    try {
      String json = objectMapper.writeValueAsString(payload);
      String target = aiBaseUrl + "/analyze-inquiry";
      // EN: Metadata only: no JSON body, so no customer email/subject/body can reach the log.
      // 中文：只记录元数据：不记录 JSON 正文，客户邮箱/主题/正文不会进入日志。
      log.info(
          "python_http_request requestId={} method={} url={} contentType={} bodyBytes={}",
          requestId,
          "POST",
          target,
          "application/json",
          json.getBytes(StandardCharsets.UTF_8).length);
      return pythonClient.post()
          .uri(target)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .header("X-Request-ID", requestId)
          .body(json)
          .exchange((httpRequest, response) -> {
            String responseBody = response.bodyTo(String.class);
            int status = response.getStatusCode().value();
            String responseContentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            log.info(
                "python_http_response requestId={} status={} contentType={} bodyBytes={}",
                requestId,
                status,
                responseContentType == null ? "unknown" : responseContentType,
                responseBody == null ? 0 : responseBody.getBytes(StandardCharsets.UTF_8).length);
            if (!response.getStatusCode().is2xxSuccessful()) {
              log.error(
                  "python_http_error requestId={} status={} response={}",
                  requestId,
                  status,
                  safeErrorSummary(responseBody));
              throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Inquiry analysis service failed");
            }
            InquiryAnalysisResponse result =
                objectMapper.readValue(responseBody, InquiryAnalysisResponse.class);
            // EN: Record source and fallback reason (from a fixed safe enum) so new fields are
            // consumed, not silently dropped. Never log the raw response body.
            // 中文：记录分析来源与降级原因（来自固定安全枚举），确保新增字段被消费而非丢弃；
            // 绝不记录原始响应正文。
            log.info(
                "python_analysis_result requestId={} source={} category={} errorPresent={}",
                requestId,
                result.analysisSource() == null ? "UNKNOWN" : result.analysisSource(),
                result.category(),
                result.errorMessage() != null);
            return result;
          });
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException | RestClientException exception) {
      // EN: Log only the exception type. The throwable may carry the request URI or a message
      // echoing request data, so never pass it to the logger. The cause is still attached to the
      // 502 for debugging, but ApiExceptionHandler logs only method/uri/status/reason.
      // 中文：只记录异常类型。异常对象可能携带请求 URI 或回显请求数据的消息，绝不传给 logger。
      // cause 仍挂在 502 上供调试，但 ApiExceptionHandler 只记录 method/uri/status/reason。
      log.error(
          "python_http_failed requestId={} errorType={}",
          requestId,
          exception.getClass().getSimpleName());
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Inquiry analysis service unavailable", exception);
    }
  }

  private String safeErrorSummary(String responseBody) {
    try {
      JsonNode root = objectMapper.readTree(responseBody);
      JsonNode details = root.path("detail");
      if (details.isArray()) {
        List<String> issues = new ArrayList<>();
        details.forEach(item -> issues.add(
            "type=" + item.path("type").asText("unknown")
                + ",loc=" + item.path("loc")
                + ",msg=" + item.path("msg").asText("")));
        return String.join(";", issues);
      }
      List<String> names = new ArrayList<>();
      root.fieldNames().forEachRemaining(names::add);
      return "responseFields=" + String.join(",", names);
    } catch (Exception ignored) {
      return "unparseableResponse";
    }
  }

}
