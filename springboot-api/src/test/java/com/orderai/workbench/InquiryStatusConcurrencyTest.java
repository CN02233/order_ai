package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderai.workbench.dto.response.InquiryStatusResponse;
import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.repository.InquiryRepository;
import com.orderai.workbench.service.InquiryService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * EN: Deterministic optimistic-lock test on the real H2 database. A test-only proxy wraps the real
 * repository and only holds each writer at saveAndFlush until both have read version 0; the real
 * method is then invoked, so the version check and both commits happen in genuine transactions.
 *
 * <p>中文：真实 H2 数据库上的确定性乐观锁测试。仅测试用代理包装真实 Repository，把两个写入方都拦在 saveAndFlush
 * 处，直到双方都读到版本 0；随后调用真实方法，因此版本检查与两次提交都发生在真实事务中。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(InquiryStatusConcurrencyTest.GateConfiguration.class)
class InquiryStatusConcurrencyTest {
  /** EN: Test-only gate state, shared between the proxy and the test method. 中文：测试闸门状态，由代理与测试方法共享。 */
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
                  // 中文：解包，让 Service 看到真实的乐观锁异常。
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

  @Autowired InquiryRepository inquiries;
  @Autowired InquiryService service;
  @Autowired JdbcTemplate jdbc;

  private final List<Object> outcomes = new CopyOnWriteArrayList<>();
  private Long createdId;

  @AfterEach
  void disarmGateAndCleanUp() {
    GATE_ARMED.set(false);
    GATED_ID.set(-1L);
    BOTH_REACHED_WRITE[0] = new CountDownLatch(2);
    RELEASE_WRITES[0] = new CountDownLatch(1);
    if (createdId != null) {
      jdbc.update("DELETE FROM inquiry_handling_record WHERE inquiry_id = ?", createdId);
      jdbc.update("DELETE FROM inquiries WHERE id = ?", createdId);
      createdId = null;
    }
  }

  @Test
  @DisplayName("Concurrent status changes: one wins, the stale writer gets 409 and leaves no history")
  void concurrentUpdateMapsOptimisticLockConflictTo409() throws Exception {
    Inquiry seed =
        new Inquiry(
            "status-race-" + UUID.randomUUID(), "customer@example.com", "subject", "body", "OTHER");
    seed.status = "PENDING";
    Inquiry saved = inquiries.saveAndFlush(seed);
    createdId = saved.id;
    long id = saved.id;

    GATED_ID.set(id);
    GATE_ARMED.set(true);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = pool.submit(() -> runAndCapture(id));
      Future<?> second = pool.submit(() -> runAndCapture(id));

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
        outcomes.stream().filter(outcome -> outcome instanceof InquiryStatusResponse).count();
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
    assertThat(finalRow.status).isEqualTo("IN_PROGRESS");
    assertThat(finalRow.version).isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inquiry_handling_record WHERE inquiry_id = ?",
                Integer.class,
                id))
        .isEqualTo(1);
  }

  /** EN: Record either the successful response or the exception, never swallow either. 中文：成功响应与异常都记录，绝不吞掉任何一个。 */
  private void runAndCapture(long id) {
    try {
      outcomes.add(service.changeStatus(id, "IN_PROGRESS"));
    } catch (RuntimeException failure) {
      outcomes.add(failure);
    }
  }
}
