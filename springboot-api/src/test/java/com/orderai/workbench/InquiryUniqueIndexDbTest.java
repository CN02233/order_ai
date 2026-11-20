package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderai.workbench.entity.Inquiry;
import com.orderai.workbench.repository.InquiryRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * EN: Real-database check for the idempotency unique index. This runs against H2 in MySQL mode
 * (the `test` profile), NOT against production MySQL. It verifies the DDL derived from schema.sql
 * and JPA @Column(unique = true) really creates a database-level constraint. The concurrent
 * fingerprint/branch tests remain mock-based and are deliberately not presented as DB integration
 * tests.
 *
 * <p>中文：针对幂等唯一索引的真实数据库检查。运行在 MySQL 模式的 H2（`test` profile）上，不是生产 MySQL。
 * 它验证由 schema.sql 与 JPA @Column(unique = true) 推导出的 DDL 确实创建了数据库级约束。
 * 并发指纹与分支测试仍基于 mock，刻意不称作数据库集成测试。
 */
@SpringBootTest
@ActiveProfiles("test")
class InquiryUniqueIndexDbTest {
  @Autowired InquiryRepository inquiries;
  @Autowired DataSource dataSource;

  @Test
  void databaseRejectsDuplicateIdempotencyKey() {
    String key = "db-uq-" + UUID.randomUUID();

    inquiries.saveAndFlush(new Inquiry(key, "first@example.com", "subject", "body", null));

    assertThat(inquiries.findByIdempotencyKey(key)).isPresent();
    assertThatThrownBy(
            () ->
                inquiries.saveAndFlush(
                    new Inquiry(key, "second@example.com", "other subject", "other body", null)))
        .isInstanceOf(DataIntegrityViolationException.class);

    // EN: The rejected INSERT left exactly one row behind.
    // 中文：被拒绝的 INSERT 没有留下第二条记录。
    assertThat(
            inquiries.findAll().stream()
                .filter(row -> key.equals(row.idempotencyKey))
                .count())
        .isEqualTo(1);
    inquiries.deleteAllInBatch();
  }

  @Test
  void uniqueIndexIsVisibleInDatabaseMetadata() throws Exception {
    boolean indexed = false;
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData metaData = connection.getMetaData();
      try (ResultSet indexes = metaData.getIndexInfo(null, null, "INQUIRIES", true, false)) {
        while (indexes.next()) {
          if ("IDEMPOTENCY_KEY".equalsIgnoreCase(indexes.getString("COLUMN_NAME"))) {
            indexed = true;
            break;
          }
        }
      }
    }
    assertThat(indexed).as("inquiries.idempotency_key must have a UNIQUE index").isTrue();
  }
}
