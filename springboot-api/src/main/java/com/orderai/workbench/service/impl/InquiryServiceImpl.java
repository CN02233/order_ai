package com.orderai.workbench.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderai.workbench.dto.InquiryAnalysisRequest;
import com.orderai.workbench.dto.request.InquiryHandlingUpdateRequest;
import com.orderai.workbench.dto.request.InquiryUpdateRequest;
import com.orderai.workbench.dto.response.InquiryAnalysisResponse;
import com.orderai.workbench.dto.response.InquiryHandlingResponse;
import com.orderai.workbench.dto.response.InquiryManagementResponse;
import com.orderai.workbench.dto.response.InquiryResponse;
import com.orderai.workbench.dto.response.InquiryStatusResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.entity.InquiryHandlingRecord;
import com.orderai.workbench.repository.InquiryHandlingRecordRepository;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

  // EN: Fixed priority whitelist and field-source tokens. HUMAN wins over any later AI analysis.
  // 中文：固定优先级白名单与字段来源标记。HUMAN 优先级高于后续任何 AI 分析结果。
  private static final Set<String> ALLOWED_PRIORITIES =
      Set.of("LOW", "MEDIUM", "HIGH", "URGENT");
  private static final String SOURCE_AI = "AI";
  private static final String SOURCE_HUMAN = "HUMAN";
  private static final String ACTION_MANUAL_UPDATE = "MANUAL_UPDATE";

  // EN: Request-size guards. 100 matches handled_by's DB column; 2000 is a deliberate API cap for
  // a free-text note stored as TEXT. 中文：请求体大小保护。100 与 handled_by 数据库列一致；备注虽存 TEXT，
  // 接口仍主动限制为 2000 字符。
  private static final int MAX_HANDLED_BY_LENGTH = 100;
  private static final int MAX_NOTE_LENGTH = 2000;

  // EN: The five business statuses and their complete transition table. A status absent from the
  // map or a target absent from the value set is rejected; there is no implicit "allow anything".
  // 中文：五种业务状态及其完整转换表。映射中缺失的当前状态或值集中缺失的目标状态一律拒绝，不存在“默认全放行”。
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
  private static final String STATUS_WAITING_CUSTOMER = "WAITING_CUSTOMER";
  private static final String STATUS_RESOLVED = "RESOLVED";
  private static final String STATUS_CLOSED = "CLOSED";

  private static final Set<String> ALLOWED_STATUSES =
      Set.of(
          STATUS_PENDING,
          STATUS_IN_PROGRESS,
          STATUS_WAITING_CUSTOMER,
          STATUS_RESOLVED,
          STATUS_CLOSED);

  private static final Map<String, Set<String>> STATUS_TRANSITIONS =
      Map.of(
          STATUS_PENDING, Set.of(STATUS_IN_PROGRESS, STATUS_CLOSED),
          STATUS_IN_PROGRESS, Set.of(STATUS_WAITING_CUSTOMER, STATUS_RESOLVED, STATUS_CLOSED),
          STATUS_WAITING_CUSTOMER, Set.of(STATUS_IN_PROGRESS, STATUS_CLOSED),
          STATUS_RESOLVED, Set.of(STATUS_IN_PROGRESS, STATUS_CLOSED),
          STATUS_CLOSED, Set.of(STATUS_PENDING));

  // EN: Fixed reason only: never include customer email/subject/body in an exception or log.
  // 中文：固定原因文本：异常与日志中绝不携带客户邮箱、主题或正文。
  private static final String CONTENT_MISMATCH_REASON =
      "Idempotency-Key reused with different request content";

  private final InquiryRepository repo;
  private final InquiryHandlingRecordRepository handlingRecords;
  private final RestClient pythonClient;
  private final ObjectMapper objectMapper;
  private final String aiBaseUrl;
  private final Duration connectTimeout;
  private final Duration readTimeout;

  public InquiryServiceImpl(
      InquiryRepository r,
      InquiryHandlingRecordRepository handlingRecords,
      ObjectMapper objectMapper,
      RestClient.Builder restClientBuilder,
      @Value("${app.ai.base-url:http://127.0.0.1:8000}") String url,
      // EN: Timeouts are configurable; defaults keep Java waiting longer than Python's 15s LLM
      // timeout.
      // 中文：超时可配置；默认值保证 Java 等待时间长于 Python 的 15 秒 LLM 超时，避免 Java 先超时。
      @Value("${app.ai.connect-timeout-seconds:3}") long connectTimeoutSeconds,
      @Value("${app.ai.read-timeout-seconds:20}") long readTimeoutSeconds) {
    repo = r;
    this.handlingRecords = handlingRecords;
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
   * EN: Create an inquiry reliably. Order: validate -> fingerprint -> persist raw inquiry -> call
   * Python -> write analysis. There is deliberately NO @Transactional here: repository calls each
   * commit in their own short transaction, so the remote HTTP call never holds a database
   * transaction. The raw inquiry is committed before Python is contacted and is never rolled back
   * on an AI failure. Idempotency relies on the UNIQUE constraint on inquiries.idempotency_key
   * (DB-level, atomic). A reused key is replayed ONLY when the SHA-256 request fingerprint matches;
   * a different body under the same key is a 409 Conflict.
   *
   * <p>The fingerprint is NOT stored in a new column: it is computed deterministically from the
   * already persisted customer_email/subject/body, so T1 needs no V8 migration. Those values are
   * never changed after creation (the update path only edits `category`).
   *
   * 中文：可靠地创建咨询。顺序：校验 -> 计算指纹 -> 落库原始咨询 -> 调 Python -> 回填分析结果。此处刻意不加 @Transactional：
   * 每次 repository 调用各自在短事务中提交，远程 HTTP 调用绝不持有数据库事务。原始咨询在调用 Python 之前就已提交，
   * AI 失败时绝不回滚。幂等依赖 inquiries.idempotency_key 的唯一约束（数据库级、原子）。只有当 SHA-256
   * 请求指纹一致时才重放；同一 Key 携带不同内容是 409 Conflict。
   *
   * <p>指纹不新增数据库列：它由已落库的 customer_email/subject/body 确定性计算得出，因此 T1 无需 V8
   * 迁移；这三个字段创建后不会被修改（更新接口只改 `category`）。
   */
  public InquiryResponse create(Map<String, String> p, String key) {
    // Step 1: parameter validation. The key defines idempotency scope and must be usable.
    if (key == null || key.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Idempotency-Key must not be blank");
    }
    // EN: Resolve each field exactly once so the persisted row, the fingerprint and the Python
    // payload can never disagree about null-vs-empty normalization.
    // 中文：每个字段只解析一次，确保落库记录、指纹与 Python 请求对 null/空字符串的归一化完全一致。
    String email = p.get("customer_email");
    String subject = p.getOrDefault("subject", "");
    String body = p.get("body");
    String fingerprint = requestFingerprint(email, subject, body);

    // Step 2: fast-path replay. An existing key never calls Python again, but its content must match.
    Optional<Inquiry> old = repo.findByIdempotencyKey(key);
    if (old.isPresent()) {
      Inquiry existing = old.get();
      verifySameRequest(existing, fingerprint);
      log.info("Inquiry idempotency replay: inquiryId={}", existing.id);
      return replayResponse(existing);
    }

    // Step 3: persist the raw inquiry FIRST, with all AI fields left null. Own transaction.
    Inquiry original;
    try {
      original = repo.saveAndFlush(new Inquiry(key, email, subject, body, null));
    } catch (DataIntegrityViolationException duplicate) {
      // EN: Concurrent request won the unique-key race. A replay is only safe when the winner's
      // stored content matches this request; otherwise the key was reused for a different inquiry.
      // 中文：并发请求抢到了唯一键。只有获胜者落库内容与当前请求一致时才可重放；否则这个 Key 被用于不同咨询。
      log.info("Inquiry idempotency conflict on insert; verifying stored inquiry");
      Inquiry winner =
          repo.findByIdempotencyKey(key)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.CONFLICT, "Concurrent inquiry creation"));
      verifySameRequest(winner, fingerprint);
      return replayResponse(winner);
    }
    log.info("Inquiry original persisted: inquiryId={}", original.id);

    // Step 4: delegate analysis to Python. No database transaction is held across this HTTP call.
    InquiryAnalysisRequest request = new InquiryAnalysisRequest(email, subject, body);
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

  /**
   * EN: Move an inquiry to a new status. Validation order: load (404) -> current status initialized
   * (409) -> target is a known status (400) -> same status is a no-op (200) -> transition allowed
   * (409). State update and handling history share ONE transaction: a failed flush rolls both back.
   * The {@code @Version} field on Inquiry supplies the optimistic lock; saveAndFlush forces the
   * version check inside this transaction so a stale writer gets 409, never a silent overwrite.
   *
   * <p>中文：推进咨询状态。校验顺序：加载（404）-> 当前状态已初始化（409）-> 目标状态合法（400）-> 同状态无操作（200）
   * -> 转换允许（409）。状态更新与处理历史共用同一事务：flush 失败时两者一起回滚。Inquiry 上的 {@code @Version}
   * 提供乐观锁；saveAndFlush 让版本检查在当前事务内发生，过期写入得到 409，绝不静默覆盖。
   */
  @Override
  @Transactional
  public InquiryStatusResponse changeStatus(long id, String targetStatus) {
    // Step 1: load the inquiry; a missing id is a 404, never an implicit creation.
    Inquiry inquiry =
        repo.findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inquiry not found"));

    // Step 2: historical rows have no status. Never guess PENDING for them.
    String previousStatus = inquiry.status;
    if (previousStatus == null || previousStatus.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Inquiry status is not initialized");
    }

    // Step 3: the target must be one of the five defined statuses.
    if (targetStatus == null || !ALLOWED_STATUSES.contains(targetStatus)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid inquiry status");
    }

    // Step 4: same status is an idempotent no-op: no write, no history row.
    if (previousStatus.equals(targetStatus)) {
      return new InquiryStatusResponse(
          inquiry.id, previousStatus, previousStatus, inquiry.updatedAt);
    }

    // Step 5: the transition must be explicitly listed; everything else is a 409.
    if (!STATUS_TRANSITIONS.getOrDefault(previousStatus, Set.of()).contains(targetStatus)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Illegal status transition: " + previousStatus + " -> " + targetStatus);
    }

    // Step 6-7: apply the status and the terminal timestamps. Terminal fields describe ONLY the
    // current terminal state; reopening clears both, so no stale terminal time survives.
    // 中文：更新状态与终态时间。终态字段只描述当前这次终态；重新打开时两个字段都清空，不留存过期终态时间。
    LocalDateTime now = LocalDateTime.now();
    inquiry.status = targetStatus;
    if (STATUS_RESOLVED.equals(targetStatus)) {
      inquiry.resolvedAt = now;
      inquiry.closedAt = null;
    } else if (STATUS_CLOSED.equals(targetStatus)) {
      inquiry.closedAt = now;
      inquiry.resolvedAt = null;
    } else {
      inquiry.resolvedAt = null;
      inquiry.closedAt = null;
    }

    // Step 8-9: flush the state change first so the optimistic-lock check runs in THIS transaction,
    // then append the handling record. A conflict or any later failure rolls the whole tx back.
    // 中文：先 flush 状态变更，让乐观锁检查在本事务内发生，再追加处理历史；冲突或后续失败会让整个事务回滚。
    Inquiry saved;
    try {
      saved = repo.saveAndFlush(inquiry);
      InquiryHandlingRecord record = new InquiryHandlingRecord();
      record.inquiry = saved;
      // EN: No authenticated user yet; keep null rather than fabricate an identity.
      // 中文：尚无登录用户；保持 null，不伪造身份。
      record.operatorId = null;
      record.actionType = actionTypeFor(previousStatus, targetStatus);
      record.oldValue = statusJson(previousStatus);
      record.newValue = statusJson(targetStatus);
      record.note = null;
      handlingRecords.saveAndFlush(record);
    } catch (ObjectOptimisticLockingFailureException conflict) {
      // EN: Another writer committed first; report 409 instead of overwriting its change.
      // 中文：另一个写入方先提交；返回 409，绝不覆盖它的修改。
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Inquiry was modified concurrently");
    }

    log.info(
        "Inquiry status changed: inquiryId={} from={} to={} action={}",
        saved.id,
        previousStatus,
        targetStatus,
        actionTypeFor(previousStatus, targetStatus));
    return new InquiryStatusResponse(
        saved.id, previousStatus, targetStatus, saved.updatedAt);
  }

  /**
   * EN: Apply partial manual handling. Validation happens before any write; only genuinely changed
   * business fields enter the JSON snapshot. ANY real field change (category, priority, handled_by,
   * note) refreshes handled_at; a no-op returns without saving, so handled_at, version and history
   * all stay untouched. State, source markers and history share one transaction through
   * flushWithHistory.
   *
   * <p>中文：应用人工部分更新。所有校验先于写库；只有真实变化的业务字段进入 JSON 快照。任何真实字段变化
   * （分类、优先级、处理人、备注）都会刷新 handled_at；无变化时直接返回，handled_at、version 与历史都不动。
   * 状态、来源标记与历史由 flushWithHistory 在同一事务中提交。
   */
  @Override
  @Transactional
  public InquiryHandlingResponse applyManualHandling(
      long id, InquiryHandlingUpdateRequest request) {
    Inquiry inquiry = loadInquiry(id);
    if (request == null) {
      throw badRequest("Request body must not be null");
    }

    String requestedCategory = request.category();
    String requestedPriority = request.priority();
    // EN: Trimmed blanks mean "not supplied", never "clear the stored value".
    // 中文：去空白后为空即视为“未提供”，绝不表示清空已存值。
    String operatorId = trimmedOptional(request.handledBy(), MAX_HANDLED_BY_LENGTH, "handled_by");
    String note = trimmedOptional(request.note(), MAX_NOTE_LENGTH, "note");

    if (requestedCategory == null
        && requestedPriority == null
        && operatorId == null
        && note == null) {
      throw badRequest("At least one handling field is required");
    }
    validateCategoryIfPresent(requestedCategory);
    validatePriorityIfPresent(requestedPriority);

    Map<String, Object> oldValues = new LinkedHashMap<>();
    Map<String, Object> newValues = new LinkedHashMap<>();
    boolean changed = false;

    if (requestedCategory != null && !Objects.equals(inquiry.category, requestedCategory)) {
      putChange(oldValues, newValues, "category", inquiry.category, requestedCategory);
      inquiry.category = requestedCategory;
      inquiry.categorySource = SOURCE_HUMAN;
      changed = true;
    }
    if (requestedPriority != null && !Objects.equals(inquiry.priority, requestedPriority)) {
      putChange(oldValues, newValues, "priority", inquiry.priority, requestedPriority);
      inquiry.priority = requestedPriority;
      inquiry.prioritySource = SOURCE_HUMAN;
      changed = true;
    }
    if (operatorId != null && !Objects.equals(inquiry.handledBy, operatorId)) {
      putChange(oldValues, newValues, "handled_by", inquiry.handledBy, operatorId);
      inquiry.handledBy = operatorId;
      changed = true;
    }
    if (note != null && !Objects.equals(inquiry.handlingNote, note)) {
      putChange(oldValues, newValues, "note", inquiry.handlingNote, note);
      inquiry.handlingNote = note;
      changed = true;
    }
    // EN: Any real field change refreshes the handling time, not just handled_by / note.
    // 中文：任何真实字段变化都会刷新处理时间，而不仅是处理人或备注变化。
    if (changed) {
      inquiry.handledAt = LocalDateTime.now();
    }
    if (!changed) {
      return toHandlingResponse(inquiry, false);
    }

    Inquiry saved = flushWithHistory(inquiry, oldValues, newValues, operatorId, note);
    return toHandlingResponse(saved, true);
  }

  /**
   * EN: Legacy PUT compatibility. customer_email/subject/body keep their old behavior and are NOT
   * copied into handling history; a category edit, however, must set HUMAN source, leave history and
   * refresh handled_at just like the new endpoint. The controller no longer mutates the entity itself.
   *
   * <p>中文：兼容旧 PUT。customer_email/subject/body 保持旧行为且不写入处理历史；但分类修改必须和新接口一样
   * 设置 HUMAN 来源、留下历史并刷新 handled_at。Controller 不再直接修改实体。
   */
  @Override
  @Transactional
  public InquiryManagementResponse update(long id, InquiryUpdateRequest request) {
    Inquiry inquiry = loadInquiry(id);
    if (request == null) {
      throw badRequest("Request body must not be null");
    }

    Map<String, Object> oldValues = new LinkedHashMap<>();
    Map<String, Object> newValues = new LinkedHashMap<>();
    boolean changed = false;
    boolean categoryChanged = false;

    if (request.customerEmail() != null
        && !Objects.equals(inquiry.customerEmail, request.customerEmail())) {
      inquiry.customerEmail = request.customerEmail();
      changed = true;
    }
    if (request.subject() != null && !Objects.equals(inquiry.subject, request.subject())) {
      inquiry.subject = request.subject();
      changed = true;
    }
    if (request.body() != null && !Objects.equals(inquiry.body, request.body())) {
      inquiry.body = request.body();
      changed = true;
    }
    if (request.category() != null) {
      validateCategoryIfPresent(request.category());
      if (!Objects.equals(inquiry.category, request.category())) {
        putChange(oldValues, newValues, "category", inquiry.category, request.category());
        inquiry.category = request.category();
        inquiry.categorySource = SOURCE_HUMAN;
        changed = true;
        categoryChanged = true;
      }
    }
    // EN: A real manual category edit refreshes handled_at, matching the new PATCH endpoint.
    // 中文：真实的人工分类修改会刷新 handled_at，与新 PATCH 接口保持一致。
    if (categoryChanged) {
      inquiry.handledAt = LocalDateTime.now();
    }
    if (!changed) {
      return InquiryManagementResponse.from(inquiry);
    }

    Inquiry saved = flushWithHistory(inquiry, oldValues, newValues, null, null);
    return InquiryManagementResponse.from(saved);
  }

  private Inquiry loadInquiry(long id) {
    return repo.findById(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inquiry not found"));
  }

  /**
   * EN: One transaction boundary for every manual write. saveAndFlush forces the optimistic-lock
   * check before history insert; a history failure therefore rolls back the inquiry fields too.
   *
   * <p>中文：所有人工写入共用这一事务边界。saveAndFlush 让乐观锁检查先于历史插入发生，因此历史写入失败会一并回滚
   * 咨询字段。
   */
  private Inquiry flushWithHistory(
      Inquiry inquiry,
      Map<String, Object> oldValues,
      Map<String, Object> newValues,
      String operatorId,
      String note) {
    try {
      Inquiry saved = repo.saveAndFlush(inquiry);
      if (!oldValues.isEmpty()) {
        InquiryHandlingRecord record = new InquiryHandlingRecord();
        record.inquiry = saved;
        record.operatorId = operatorId;
        record.actionType = ACTION_MANUAL_UPDATE;
        record.oldValue = toJson(oldValues);
        record.newValue = toJson(newValues);
        record.note = note;
        handlingRecords.saveAndFlush(record);
      }
      return saved;
    } catch (ObjectOptimisticLockingFailureException conflict) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Inquiry was modified concurrently");
    }
  }

  private static void putChange(
      Map<String, Object> oldValues,
      Map<String, Object> newValues,
      String field,
      Object oldValue,
      Object newValue) {
    oldValues.put(field, oldValue);
    newValues.put(field, newValue);
  }

  private static String trimmedOptional(String value, int maxLength, String field) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > maxLength) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Field " + field + " is too long");
    }
    return trimmed;
  }

  private void validateCategoryIfPresent(String category) {
    if (category != null && !ALLOWED_CATEGORIES.contains(category)) {
      throw badRequest("Invalid inquiry category");
    }
  }

  private void validatePriorityIfPresent(String priority) {
    if (priority != null && !ALLOWED_PRIORITIES.contains(priority)) {
      throw badRequest("Invalid inquiry priority");
    }
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  private String toJson(Map<String, Object> values) {
    try {
      return objectMapper.writeValueAsString(values);
    } catch (JsonProcessingException failure) {
      // EN: Only String/Boolean values are serialized; this cannot fail in practice.
      // 中文：只序列化 String/Boolean，实际不可能失败。
      throw new IllegalStateException("Cannot serialize handling snapshot", failure);
    }
  }

  private static InquiryHandlingResponse toHandlingResponse(
      Inquiry inquiry, boolean historyWritten) {
    return new InquiryHandlingResponse(
        inquiry.id,
        inquiry.category,
        inquiry.categorySource,
        inquiry.priority,
        inquiry.prioritySource,
        inquiry.handledBy,
        inquiry.handledAt,
        inquiry.handlingNote,
        inquiry.version == null ? 0L : inquiry.version,
        inquiry.updatedAt,
        historyWritten);
  }

  /**
   * EN: The action recorded for a transition. Entering RESOLVED/CLOSED maps to RESOLVE/CLOSE;
   * reopening a CLOSED inquiry maps to REOPEN; all other legal moves are STATUS_CHANGE.
   * 中文：转换对应的历史动作。进入 RESOLVED/CLOSED 记为 RESOLVE/CLOSE；CLOSED 重新打开记为 REOPEN；其余合法转换为 STATUS_CHANGE。
   */
  private static String actionTypeFor(String previousStatus, String targetStatus) {
    if (STATUS_RESOLVED.equals(targetStatus)) {
      return "RESOLVE";
    }
    if (STATUS_CLOSED.equals(targetStatus)) {
      return "CLOSE";
    }
    if (STATUS_CLOSED.equals(previousStatus)) {
      return "REOPEN";
    }
    return "STATUS_CHANGE";
  }

  /** EN: JSON snapshot of a status value for old_value/new_value. 中文：状态值的 JSON 快照，用于 old_value/new_value。 */
  private String statusJson(String status) {
    try {
      return objectMapper.writeValueAsString(Map.of("status", status));
    } catch (JsonProcessingException e) {
      // EN: A String value cannot fail JSON serialization; this only satisfies checked-exception syntax.
      // 中文：String 值的 JSON 序列化不会失败；此处仅为满足受检异常语法。
      throw new IllegalStateException("Cannot serialize status value", e);
    }
  }

  /**
   * EN: SHA-256 over a canonical, length-prefixed encoding of the three request fields. Each field
   * is written as: UTF-8 field name, ':', byte length (or -1 for null), ':', raw UTF-8 bytes, '\n'.
   * Length prefixes make boundaries unambiguous: ("a","bc","d") and ("ab","c","d") hash differently,
   * unlike naive concatenation. null and "" are also distinct values.
   * 中文：对三个请求字段的规范化长度前缀编码计算 SHA-256。每个字段写成：UTF-8 字段名、':'、字节长度（null 记为
   * -1）、':'、原始 UTF-8 字节、'\n'。长度前缀消除字段边界歧义：("a","bc","d") 与 ("ab","c","d")
   * 的摘要不同，简单字符串拼接则会碰撞；null 与 "" 同样可区分。
   */
  private static String requestFingerprint(String email, String subject, String body) {
    ByteArrayOutputStream canonical = new ByteArrayOutputStream();
    writeFingerprintField(canonical, "customer_email", email);
    writeFingerprintField(canonical, "subject", subject);
    writeFingerprintField(canonical, "body", body);
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray());
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      // EN: SHA-256 is mandatory in every Java runtime; this can only indicate a broken JVM.
      // 中文：SHA-256 是所有 Java 运行时的必备算法；出现此异常只可能是 JVM 实现异常。
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void writeFingerprintField(
      ByteArrayOutputStream out, String name, String value) {
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    byte[] valueBytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    out.writeBytes(nameBytes);
    out.write(':');
    out.writeBytes(
        (valueBytes == null ? "-1" : Integer.toString(valueBytes.length))
            .getBytes(StandardCharsets.UTF_8));
    out.write(':');
    if (valueBytes != null) {
      out.writeBytes(valueBytes);
    }
    out.write('\n');
  }

  /**
   * EN: Reject a reused key whose stored request content differs. The stored row is the source of
   * truth because email/subject/body are immutable after creation, so no extra fingerprint column
   * is needed. Logs carry only the inquiry id and a fixed reason; no customer data is logged.
   * 中文：拒绝 Key 相同但请求内容不同的情况。由于邮箱/主题/正文创建后不可变，已落库记录即为可信来源，
   * 无需额外指纹列。日志只记录咨询 id 与固定原因，绝不记录客户信息。
   */
  private static void verifySameRequest(Inquiry existing, String fingerprint) {
    String storedFingerprint =
        requestFingerprint(existing.customerEmail, existing.subject, existing.body);
    if (!fingerprint.equals(storedFingerprint)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, CONTENT_MISMATCH_REASON);
    }
  }

  private void applyAnalysis(Inquiry inquiry, InquiryAnalysisResponse analysis) {
    // EN: HUMAN is authoritative. A later AI analysis may refresh the snapshot and other fields,
    // but must never overwrite a category a support agent has explicitly maintained.
    // 中文：HUMAN 具有最高优先级。后续 AI 分析可以刷新快照与其他字段，但绝不能覆盖客服已明确维护的分类。
    if (!SOURCE_HUMAN.equals(inquiry.categorySource)) {
      inquiry.category = analysis.category();
      inquiry.categorySource = SOURCE_AI;
    }
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
