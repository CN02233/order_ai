package com.orderai.workbench.service.impl;

import com.orderai.workbench.entity.Inquiry;

import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.orderai.workbench.dto.InquiryAnalysisRequest;
import com.orderai.workbench.dto.response.InquiryAnalysisResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class InquiryServiceImpl implements InquiryService {
  private static final Logger log = LoggerFactory.getLogger(InquiryServiceImpl.class);
  private final InquiryRepository repo;
  private final RestClient pythonClient;
  private final ObjectMapper objectMapper;
  private final String aiBaseUrl;

  /**
   * Initializes this component with its required dependencies.
   * @param r input used by this operation
   * @param url input used by this operation
   */
  public InquiryServiceImpl(
      InquiryRepository r,
      ObjectMapper objectMapper,
      RestClient.Builder restClientBuilder,
      @Value("${app.ai.base-url:http://127.0.0.1:8000}") String url) {
    repo = r;
    this.objectMapper = objectMapper;
    this.aiBaseUrl = url.replaceAll("/+$", "");
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    this.pythonClient =
        restClientBuilder.clone().requestFactory(requestFactory).build();
  }

  @Transactional
  /**
   * Creates a business record while honoring the idempotency key.
   * @param String input used by this operation
   * @param p input used by this operation
   * @param key input used by this operation
   * @return the result of this operation
   */
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
    try {
      String json = objectMapper.writeValueAsString(payload);
      String requestId = UUID.randomUUID().toString();
      String target = aiBaseUrl + "/analyze-inquiry";
      log.info(
          "python_http_request requestId={} method={} url={} contentType={} bodyBytes={} jsonBody={}",
          requestId,
          "POST",
          target,
          "application/json",
          json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
          json);
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
                responseBody == null ? 0 : responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if (!response.getStatusCode().is2xxSuccessful()) {
              log.error(
                  "python_http_error requestId={} status={} response={}",
                  requestId,
                  status,
                  safeErrorSummary(responseBody));
              throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Inquiry analysis service failed");
            }
            return objectMapper.readValue(responseBody, InquiryAnalysisResponse.class);
          });
    } catch (ResponseStatusException exception) {
      throw exception;
    } catch (IOException | RestClientException exception) {
      log.error("Could not communicate with inquiry analysis service", exception);
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Inquiry analysis service unavailable", exception);
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
