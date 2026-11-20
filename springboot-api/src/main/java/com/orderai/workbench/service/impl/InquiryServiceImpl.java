package com.orderai.workbench.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderai.workbench.dto.InquiryAnalysisRequest;
import com.orderai.workbench.dto.response.InquiryAnalysisResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InquiryServiceImpl implements InquiryService {
  private static final Logger log = LoggerFactory.getLogger(InquiryServiceImpl.class);

  // EN: Fixed allow-lists: an analysis outside these values is rejected with 502, never defaulted.
  // 中文：固定白名单：分析结果不在其中时直接返回 502 拒绝，绝不使用默认值替代。
  private static final Set<String> ALLOWED_CATEGORIES =
      Set.of("ORDER_QUERY", "PRODUCT_INQUIRY", "REFUND", "COMPLAINT", "OTHER");
  private static final Set<String> ALLOWED_ANALYSIS_SOURCES = Set.of("AI", "RULE_BASED");
  private static final Set<String> REVIEW_CATEGORIES = Set.of("REFUND", "COMPLAINT", "OTHER");

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
      // EN: Timeouts are configurable; defaults keep Java waiting longer than Python's 15s LLM
      // timeout.
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

  /**
   * EN: Test seam: expose timeouts so tests can assert the configured values.
   * 中文：测试接缝：暴露超时配置，便于测试断言。
   */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  public Duration readTimeout() {
    return readTimeout;
  }

  /**
   * EN: Create an inquiry reliably. Order: validate -> persist raw inquiry -> call Python -> write
   * analysis. There is deliberately NO @Transactional here: repository calls each commit in their
   * own short transaction, so the remote HTTP call never holds a database transaction. The raw
   * inquiry is committed before Python is contacted and is never rolled back on an AI failure.
   * Idempotency relies on the UNIQUE constraint on inquiries.idempotency_key (DB-level, atomic).
   * 中文：可靠地创建咨询。顺序：校验 -> 落库原始咨询 -> 调 Python -> 回填分析结果。此处刻意不加 @Transactional： 每次 repository
   * 调用各自在短事务中提交，远程 HTTP 调用绝不持有数据库事务。原始咨询在调用 Python 之前就已提交， AI 失败时绝不回滚。幂等依赖
   * inquiries.idempotency_key 的唯一约束（数据库级、原子）。
   */
  public InquiryResponse create(Map<String, String> p, String key) {
    // Step 1: parameter validation. The key defines idempotency scope and must be usable.
    if (key == null || key.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Idempotency-Key must not be blank");
    }

    // Step 2: fast-path replay. An existing key never calls Python again.
    Optional<Inquiry> old = repo.findByIdempotencyKey(key);
    if (old.isPresent()) {
      Inquiry existing = old.get();
      log.info("Inquiry idempotency replay: inquiryId={}", existing.id);
      return replayResponse(existing);
    }

    // Step 3: persist the raw inquiry FIRST, with all AI fields left null. Own transaction.
    Inquiry original;
    try {
      original =
          repo.saveAndFlush(
              new Inquiry(
                  key,
                  p.get("customer_email"),
                  p.getOrDefault("subject", ""),
                  p.get("body"),
                  null));
    } catch (DataIntegrityViolationException duplicate) {
      // EN: Concurrent request won the unique-key race. Replay the winner's row, never call Python.
      // 中文：并发请求抢到了唯一键。重放获胜者的记录，绝不调用 Python。
      log.info("Inquiry idempotency conflict on insert; replaying stored inquiry");
      Inquiry winner =
          repo.findByIdempotencyKey(key)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.CONFLICT, "Concurrent inquiry creation"));
      return replayResponse(winner);
    }
    log.info("Inquiry original persisted: inquiryId={}", original.id);

    // Step 4: delegate analysis to Python. No database transaction is held across this HTTP call.
    InquiryAnalysisRequest request =
        new InquiryAnalysisRequest(
            p.get("customer_email"), p.getOrDefault("subject", ""), p.get("body"));
    // EN: Log only presence/length. Never log the email, subject or body values.
    // 中文：只记录是否存在与长度，绝不记录邮箱、主题或正文内容。
    log.info(
        "Calling inquiry analysis service: customerEmailPresent={} subjectLength={} bodyLength={}",
        request.customerEmail() != null,
        request.subject() == null ? 0 : request.subject().length(),
        request.body() == null ? 0 : request.body().length());

    InquiryAnalysisResponse analysis;
    try {
      analysis = analyzeWithPython(request);
      // EN: Reject an incomplete or inconsistent analysis before writing any analysis result.
      // 中文：写入任何分析结果之前，拒绝不完整或自相矛盾的分析结果。
      validateAnalysis(analysis);
    } catch (ResponseStatusException failure) {
      // EN: AI failed but the raw inquiry is already committed. Return a FAILED result, never a
      // misleading "creation failed" error. The failure code is a fixed safe token.
      // 中文：AI 失败但原始咨询已提交。返回 FAILED 结果，绝不返回误导性的“创建失败”错误。失败码是固定安全令牌。
      String code = classifyAnalysisFailure(failure);
      log.warn("Inquiry analysis failed: inquiryId={} code={}", original.id, code);
      return failedResponse(original.id, code);
    }

    // Step 5: AI succeeded -> write the analysis result back onto the persisted row. Own
    // transaction.
    applyAnalysis(original, analysis);
    try {
      Inquiry updated = repo.saveAndFlush(original);
      log.info(
          "Inquiry analysis persisted: inquiryId={} category={} analysisSource={}",
          updated.id,
          updated.category,
          updated.analysisSource);
      return successResponse(updated);
    } catch (RuntimeException dbFailure) {
      // EN: Raw inquiry remains in the database; only the AI result was not saved. Report the
      // failure honestly so it can be retried/recovered (see T2: analysis_status persistence).
      // 中文：原始咨询仍在库中，仅 AI 结果未保存。如实上报失败以便重试/恢复（见 T2：analysis_status 落库）。
      log.error(
          "Inquiry analysis persist failed: inquiryId={} errorType={}",
          original.id,
          dbFailure.getClass().getSimpleName());
      return failedResponse(original.id, "AI_PERSIST_FAILED");
    }
  }

  private void applyAnalysis(Inquiry inquiry, InquiryAnalysisResponse analysis) {
    inquiry.category = analysis.category();
    inquiry.aiCategory = analysis.category();
    inquiry.confidence = analysis.confidence();
    inquiry.needsHumanReview = analysis.needsHumanReview();
    inquiry.suggestedReply = analysis.suggestedReply();
    inquiry.analysisSource = analysis.analysisSource();
  }

  private InquiryResponse successResponse(Inquiry inquiry) {
    return new InquiryResponse(
        inquiry.id,
        inquiry.category,
        inquiry.aiCategory,
        inquiry.confidence,
        inquiry.needsHumanReview,
        inquiry.suggestedReply,
        inquiry.analysisSource,
        "SUCCESS",
        null,
        false);
  }

  private InquiryResponse failedResponse(Long inquiryId, String code) {
    // EN: A failed analysis response deliberately carries NO analysis fields, because no analysis
    // result was persisted. Only the inquiry id (the raw row) and a safe failure code are returned.
    // 中文：分析失败的响应刻意不携带任何分析字段，因为没有任何分析结果被持久化。只返回咨询 id（原始记录）与安全失败码。
    return new InquiryResponse(
        inquiryId, null, null, null, null, null, null, "FAILED", code, false);
  }

  private InquiryResponse replayResponse(Inquiry inquiry) {
    // EN: analysis_status is derived from the persisted snapshot: a row with analysis_source is a
    // completed analysis; a row without it is still pending/failed (T1 cannot tell them apart
    // without a persisted status column -> T2 dependency). Never fabricate analysis values.
    // 中文：analysis_status 由持久化快照派生：有 analysis_source 即为分析完成；无则为处理中/失败（T1 无状态列，
    // 无法区分二者 -> T2 依赖）。绝不伪造分析值。
    String status = inquiry.analysisSource == null ? "PENDING" : "SUCCESS";
    return new InquiryResponse(
        inquiry.id,
        inquiry.category,
        inquiry.aiCategory,
        inquiry.confidence,
        inquiry.needsHumanReview,
        inquiry.suggestedReply,
        inquiry.analysisSource,
        status,
        null,
        true);
  }

  private String classifyAnalysisFailure(ResponseStatusException failure) {
    String reason = failure.getReason() == null ? "" : failure.getReason();
    if (reason.startsWith("Invalid inquiry analysis response")
        || hasCause(failure, JsonProcessingException.class)) {
      return "AI_INVALID_RESPONSE";
    }
    if (hasCause(failure, SocketTimeoutException.class)
        || hasCause(failure, InterruptedIOException.class)) {
      return "AI_TIMEOUT";
    }
    if ("Inquiry analysis service failed".equals(reason)) {
      return "AI_HTTP_ERROR";
    }
    return "AI_UNAVAILABLE";
  }

  private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
    Throwable current = throwable;
    while (current != null) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /**
   * EN: Validate every required analysis field. A missing value is distinguished from a valid one
   * through nullable Double/Boolean in the DTO; an invalid response is rejected so NO analysis
   * field is written (the raw inquiry, however, is already committed and kept). error_message is
   * internal diagnostics and is deliberately not part of inquiry data. 中文：校验每个必填分析字段。DTO 使用可空
   * Double/Boolean 区分字段缺失与合法值；非法响应被拒绝，任何分析字段都不写入 （但原始咨询已提交且保留）。error_message 属于内部诊断信息，刻意不进入咨询数据。
   */
  private void validateAnalysis(InquiryAnalysisResponse analysis) {
    if (analysis == null
        || analysis.category() == null
        || !ALLOWED_CATEGORIES.contains(analysis.category())) {
      throw invalidAnalysis("category");
    }
    Double confidence = analysis.confidence();
    if (confidence == null
        || !Double.isFinite(confidence)
        || confidence < 0.0
        || confidence > 1.0) {
      throw invalidAnalysis("confidence");
    }
    if (analysis.analysisSource() == null
        || !ALLOWED_ANALYSIS_SOURCES.contains(analysis.analysisSource())) {
      throw invalidAnalysis("analysis_source");
    }
    Boolean needsReview = analysis.needsHumanReview();
    if (needsReview == null || needsReview != REVIEW_CATEGORIES.contains(analysis.category())) {
      throw invalidAnalysis("needs_human_review");
    }
  }

  /**
   * EN: Fixed field name only, never a raw value, so logs stay redacted.
   * 中文：只使用固定字段名，绝不记录原始值，保持日志脱敏。
   */
  private ResponseStatusException invalidAnalysis(String field) {
    return new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, "Invalid inquiry analysis response: " + field);
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
      return pythonClient
          .post()
          .uri(target)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .header("X-Request-ID", requestId)
          .body(json)
          .exchange(
              (httpRequest, response) -> {
                String responseBody = response.bodyTo(String.class);
                int status = response.getStatusCode().value();
                String responseContentType =
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                log.info(
                    "python_http_response requestId={} status={} contentType={} bodyBytes={}",
                    requestId,
                    status,
                    responseContentType == null ? "unknown" : responseContentType,
                    responseBody == null
                        ? 0
                        : responseBody.getBytes(StandardCharsets.UTF_8).length);
                if (!response.getStatusCode().is2xxSuccessful()) {
                  log.error(
                      "python_http_error requestId={} status={} response={}",
                      requestId,
                      status,
                      safeErrorSummary(responseBody));
                  throw new ResponseStatusException(
                      HttpStatus.BAD_GATEWAY, "Inquiry analysis service failed");
                }
                InquiryAnalysisResponse result =
                    objectMapper.readValue(responseBody, InquiryAnalysisResponse.class);
                if (result == null) {
                  throw new ResponseStatusException(
                      HttpStatus.BAD_GATEWAY, "Invalid inquiry analysis response: empty");
                }
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
        details.forEach(
            item ->
                issues.add(
                    "type="
                        + item.path("type").asText("unknown")
                        + ",loc="
                        + item.path("loc")
                        + ",msg="
                        + item.path("msg").asText("")));
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
