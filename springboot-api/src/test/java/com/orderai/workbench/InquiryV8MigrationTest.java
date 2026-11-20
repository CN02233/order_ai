package com.orderai.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * EN: V8 migration tests against a REAL database engine (H2 in MySQL mode) with Hibernate DDL
 * switched off. Each test gets a fresh in-memory catalog, applies the project's own SQL files and
 * inspects the resulting tables/columns/indexes. The V7 baseline is a test-only fixture that
 * reproduces the pre-V8 `inquiries` table; production migrations are never edited. H2 is not
 * production MySQL, so V8 deliberately uses syntax supported by both.
 *
 * <p>中文：针对真实数据库引擎（MySQL 模式的 H2）、关闭 Hibernate DDL 的 V8 迁移测试。每个用例使用全新的内存库，
 * 按项目原有方式执行 SQL 文件，并检查真实表/列/索引。V7 基线是仅用于测试的夹具，复现 V8 之前的 `inquiries`
 * 表；生产迁移脚本从不修改。H2 不等同于生产 MySQL，因此 V8 刻意使用双方都支持的语法。
 */
class InquiryV8MigrationTest {
  private static final String SCHEMA = "schema.sql";
  private static final String V7_BASELINE = "db/migration-test/V7_inquiries_baseline.sql";
  private static final String V8 = "db/migration/V8__inquiry_data_model.sql";

  private Connection connection;

  @BeforeEach
  void openFreshDatabase() throws Exception {
    String url =
        "jdbc:h2:mem:v8_"
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    connection = DriverManager.getConnection(url, "sa", "");
  }

  @AfterEach
  void closeDatabase() throws Exception {
    if (connection != null && !connection.isClosed()) {
      connection.close();
    }
  }

  private void apply(String resource) throws Exception {
    ScriptUtils.executeSqlScript(connection, new ClassPathResource(resource));
  }

  private Set<String> columns(String table) throws SQLException {
    Set<String> names = new HashSet<>();
    try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, null)) {
      while (rs.next()) {
        names.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
      }
    }
    return names;
  }

  private Set<String> indexNames(String table, boolean unique) throws SQLException {
    Set<String> names = new HashSet<>();
    try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, table, unique, false)) {
      while (rs.next()) {
        String name = rs.getString("INDEX_NAME");
        if (name != null) {
          names.add(name.toUpperCase(Locale.ROOT));
        }
      }
    }
    return names;
  }

  /** EN: Check the indexed COLUMNS, because H2 renames declared unique indexes. 中文：按索引列判断，H2 会重命名声明的唯一索引。 */
  private boolean hasUniqueIndexOn(String table, String... expectedColumns) throws SQLException {
    Map<String, Set<String>> columnsByIndex = new HashMap<>();
    try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, table, true, false)) {
      while (rs.next()) {
        String index = rs.getString("INDEX_NAME");
        String column = rs.getString("COLUMN_NAME");
        if (index != null && column != null) {
          columnsByIndex
              .computeIfAbsent(index, ignored -> new HashSet<>())
              .add(column.toUpperCase(Locale.ROOT));
        }
      }
    }
    Set<String> expected = Set.of(expectedColumns);
    return columnsByIndex.values().stream().anyMatch(actual -> actual.containsAll(expected));
  }

  private boolean hasForeignKeyOn(String table, String column) throws SQLException {
    try (ResultSet rs = connection.getMetaData().getImportedKeys(null, null, table)) {
      while (rs.next()) {
        if (column.equalsIgnoreCase(rs.getString("FKCOLUMN_NAME"))) {
          return true;
        }
      }
    }
    return false;
  }

  private int count(String sql) throws SQLException {
    try (Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private String stringValue(String sql) throws SQLException {
    try (Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  private Long longValue(String sql) throws SQLException {
    try (Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      long value = rs.getLong(1);
      return rs.wasNull() ? null : value;
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement s = connection.createStatement()) {
      s.execute(sql);
    }
  }

  /** EN: Insert a post-V7 inquiry row with configurable analysis evidence. 中文：插入 V7 状态的历史咨询行。 */
  private long seedInquiry(
      String key,
      String category,
      String aiCategory,
      Double confidence,
      Boolean needsReview,
      String suggestedReply,
      String analysisSource)
      throws SQLException {
    String sql =
        "INSERT INTO inquiries (idempotency_key, customer_email, subject, body, category, "
            + "ai_category, confidence, needs_human_review, suggested_reply, analysis_source) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, key);
      ps.setString(2, key + "@example.com");
      ps.setString(3, "subject-" + key);
      ps.setString(4, "body-" + key);
      ps.setString(5, category);
      ps.setString(6, aiCategory);
      ps.setObject(7, confidence);
      ps.setObject(8, needsReview);
      ps.setString(9, suggestedReply);
      ps.setString(10, analysisSource);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private void assertV8Structure() throws SQLException {
    assertThat(columns("INQUIRIES"))
        .contains(
            "IDEMPOTENCY_KEY",
            "CUSTOMER_EMAIL",
            "SUBJECT",
            "BODY",
            "CATEGORY",
            "AI_CATEGORY",
            "CONFIDENCE",
            "NEEDS_HUMAN_REVIEW",
            "SUGGESTED_REPLY",
            "ANALYSIS_SOURCE",
            "STATUS",
            "PRIORITY",
            "CATEGORY_SOURCE",
            "PRIORITY_SOURCE",
            "ANALYSIS_STATUS",
            "CURRENT_ANALYSIS_ID",
            "HANDLED_BY",
            "HANDLED_AT",
            "HANDLING_NOTE",
            "UPDATED_AT",
            "RESOLVED_AT",
            "CLOSED_AT",
            "VERSION",
            "CREATED_AT");
    assertThat(columns("INQUIRY_ANALYSIS"))
        .contains(
            "ID",
            "INQUIRY_ID",
            "VERSION",
            "CATEGORY",
            "PRIORITY",
            "CONFIDENCE",
            "NEEDS_HUMAN_REVIEW",
            "ANALYSIS_REASON",
            "SUGGESTED_REPLY",
            "ANALYSIS_SOURCE",
            "STATUS",
            "ERROR_MESSAGE",
            "CREATED_AT",
            "COMPLETED_AT");
    assertThat(columns("INQUIRY_HANDLING_RECORD"))
        .contains(
            "ID",
            "INQUIRY_ID",
            "OPERATOR_ID",
            "ACTION_TYPE",
            "OLD_VALUE",
            "NEW_VALUE",
            "NOTE",
            "CREATED_AT");

    // EN: Real DB-level unique index and indexes, not just Java annotations.
    // 中文：真实数据库级唯一索引与普通索引，而非仅 Java 注解。
    assertThat(indexNames("INQUIRY_ANALYSIS", true))
        .anyMatch(name -> name.startsWith("UQ_INQUIRY_ANALYSIS_VERSION"));
    assertThat(hasUniqueIndexOn("INQUIRY_ANALYSIS", "INQUIRY_ID", "VERSION")).isTrue();
    assertThat(indexNames("INQUIRY_ANALYSIS", false)).contains("IDX_INQUIRY_ANALYSIS_INQUIRY");
    assertThat(indexNames("INQUIRY_HANDLING_RECORD", false))
        .contains("IDX_INQUIRY_HANDLING_INQUIRY_CREATED");
    assertThat(indexNames("INQUIRIES", false))
        .contains(
            "IDX_INQUIRIES_STATUS",
            "IDX_INQUIRIES_ANALYSIS_STATUS",
            "IDX_INQUIRIES_UPDATED_AT");
    assertThat(hasForeignKeyOn("INQUIRY_ANALYSIS", "INQUIRY_ID")).isTrue();
    assertThat(hasForeignKeyOn("INQUIRY_HANDLING_RECORD", "INQUIRY_ID")).isTrue();
    // EN: current_analysis_id is a logical reference: no cyclic physical FK / cascade chain.
    // 中文：current_analysis_id 是逻辑引用：不建循环物理外键 / 级联删除链。
    assertThat(hasForeignKeyOn("INQUIRIES", "CURRENT_ANALYSIS_ID")).isFalse();
  }

  @Test
  @DisplayName("Empty database: V7 baseline + V8 creates all three tables and their structures")
  void emptyDatabaseAppliesV8Successfully() throws Exception {
    apply(V7_BASELINE);
    assertThat(columns("INQUIRY_ANALYSIS")).isEmpty();

    apply(V8);

    assertV8Structure();
    assertThat(count("SELECT COUNT(*) FROM inquiries")).isZero();
    assertThat(count("SELECT COUNT(*) FROM inquiry_analysis")).isZero();
    assertThat(count("SELECT COUNT(*) FROM inquiry_handling_record")).isZero();
  }

  @Test
  @DisplayName("Fresh-install schema.sql matches the V7 + V8 upgraded structure")
  void freshSchemaMatchesUpgradedStructure() throws Exception {
    apply(SCHEMA);
    assertV8Structure();
  }

  @Test
  @DisplayName("V7 database with history upgrades: original content kept, analyses migrated")
  void v7HistoryMigratesIntoAnalysisTable() throws Exception {
    apply(V7_BASELINE);
    // EN: A: AI judgement REFUND later edited by a human to COMPLAINT.
    //     B: no analysis evidence at all. C: pre-V7 row, no ai_category snapshot.
    // 中文：A：AI 判定 REFUND 后被人工改成 COMPLAINT；B：完全没有分析证据；C：V7 之前的记录，无 ai_category 快照。
    long a = seedInquiry("hist-a", "COMPLAINT", "REFUND", 0.9, true, "refund reply", "AI");
    long b = seedInquiry("hist-b", "OTHER", null, null, null, null, null);
    long c = seedInquiry("hist-c", "PRODUCT_INQUIRY", null, 0.7, false, null, "AI");

    apply(V8);

    // EN: Original inquiry content and old analysis columns are untouched.
    // 中文：原始咨询内容与旧分析列保持不变。
    assertThat(stringValue("SELECT customer_email FROM inquiries WHERE id = " + a))
        .isEqualTo("hist-a@example.com");
    assertThat(stringValue("SELECT body FROM inquiries WHERE id = " + a)).isEqualTo("body-hist-a");
    assertThat(stringValue("SELECT category FROM inquiries WHERE id = " + a))
        .isEqualTo("COMPLAINT");
    assertThat(stringValue("SELECT ai_category FROM inquiries WHERE id = " + a))
        .isEqualTo("REFUND");
    assertThat(stringValue("SELECT analysis_source FROM inquiries WHERE id = " + c))
        .isEqualTo("AI");

    // EN: Only rows with evidence get one version=1 analysis row. The migrated analysis category
    //     comes ONLY from ai_category: A has the AI snapshot (REFUND), C has evidence but no AI
    //     snapshot, so its migrated category must be NULL -- never the human-editable current
    //     category. The current business category on the inquiry is untouched for every row.
    // 中文：只有存在分析证据的记录才生成一条 version=1 分析历史。迁入的分析分类只取 ai_category：
    //     A 有 AI 快照（REFUND）；C 有分析证据但没有 AI 快照，迁入分类必须为 NULL，绝不取可人工编辑的
    //     当前分类。每行咨询的当前业务分类保持不变。
    assertThat(count("SELECT COUNT(*) FROM inquiry_analysis")).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM inquiry_analysis WHERE inquiry_id = " + a)).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM inquiry_analysis WHERE inquiry_id = " + b)).isZero();
    assertThat(stringValue("SELECT category FROM inquiry_analysis WHERE inquiry_id = " + a))
        .isEqualTo("REFUND");
    assertThat(stringValue("SELECT category FROM inquiry_analysis WHERE inquiry_id = " + c))
        .isNull();
    assertThat(stringValue("SELECT status FROM inquiry_analysis WHERE inquiry_id = " + a))
        .isEqualTo("SUCCESS");

    // EN: The current business category is preserved, independent of the migrated analysis category.
    //     C keeps PRODUCT_INQUIRY even though its migrated analysis category is NULL.
    // 中文：当前业务分类原样保留，与迁入的分析分类无关。C 的迁入分析分类为 NULL，但当前分类仍是 PRODUCT_INQUIRY。
    assertThat(stringValue("SELECT category FROM inquiries WHERE id = " + c))
        .isEqualTo("PRODUCT_INQUIRY");

    // EN: current_analysis_id points at the row created for this inquiry, never at another one.
    // 中文：current_analysis_id 指向本咨询的分析记录，不会指向别的咨询。
    Long analysisIdA = longValue("SELECT id FROM inquiry_analysis WHERE inquiry_id = " + a);
    Long analysisIdC = longValue("SELECT id FROM inquiry_analysis WHERE inquiry_id = " + c);
    assertThat(longValue("SELECT current_analysis_id FROM inquiries WHERE id = " + a))
        .isEqualTo(analysisIdA);
    assertThat(longValue("SELECT current_analysis_id FROM inquiries WHERE id = " + c))
        .isEqualTo(analysisIdC);
    assertThat(longValue("SELECT current_analysis_id FROM inquiries WHERE id = " + b)).isNull();

    // EN: No fabricated states. Only migrated analyses are SUCCESS; unknown history stays NULL.
    // 中文：不伪造状态。只有迁入的分析为 SUCCESS，未知历史保持 NULL。
    assertThat(stringValue("SELECT analysis_status FROM inquiries WHERE id = " + a))
        .isEqualTo("SUCCESS");
    assertThat(stringValue("SELECT analysis_status FROM inquiries WHERE id = " + b)).isNull();
    assertThat(stringValue("SELECT status FROM inquiries WHERE id = " + a)).isNull();
    assertThat(stringValue("SELECT priority FROM inquiries WHERE id = " + a)).isNull();
    assertThat(stringValue("SELECT priority_source FROM inquiries WHERE id = " + a)).isNull();
    assertThat(longValue("SELECT version FROM inquiries WHERE id = " + a)).isZero();
    assertThat(stringValue("SELECT handled_by FROM inquiries WHERE id = " + a)).isNull();

    // EN: category_source is inferred only when the AI snapshot exists.
    // 中文：只有 AI 快照存在时才推断 category_source。
    assertThat(stringValue("SELECT category_source FROM inquiries WHERE id = " + a))
        .isEqualTo("HUMAN");
    assertThat(stringValue("SELECT category_source FROM inquiries WHERE id = " + c)).isNull();
    assertThat(stringValue("SELECT category_source FROM inquiries WHERE id = " + b)).isNull();

    // EN: No manual handling history existed before V8, so the new history table starts empty.
    // 中文：V8 之前没有人工处理历史，新历史表初始为空。
    assertThat(count("SELECT COUNT(*) FROM inquiry_handling_record")).isZero();
  }

  @Test
  @DisplayName("Analysis history: multiple versions allowed, duplicate version rejected by DB")
  void analysisVersionUniquenessIsEnforced() throws Exception {
    apply(V7_BASELINE);
    apply(V8);
    long inquiryId = seedInquiry("versions", "OTHER", null, null, null, null, null);

    execute(
        "INSERT INTO inquiry_analysis (inquiry_id, version, status) VALUES ("
            + inquiryId
            + ", 1, 'SUCCESS')");
    execute(
        "INSERT INTO inquiry_analysis (inquiry_id, version, status) VALUES ("
            + inquiryId
            + ", 2, 'FAILED')");
    assertThat(count("SELECT COUNT(*) FROM inquiry_analysis WHERE inquiry_id = " + inquiryId))
        .isEqualTo(2);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO inquiry_analysis (inquiry_id, version, status) VALUES ("
                        + inquiryId
                        + ", 2, 'SUCCESS')"))
        .isInstanceOf(SQLException.class);
    assertThat(count("SELECT COUNT(*) FROM inquiry_analysis WHERE inquiry_id = " + inquiryId))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("Handling history saves independently, keeps JSON text and enforces the FK")
  void handlingHistoryIsIndependentAndConstrained() throws Exception {
    apply(V7_BASELINE);
    apply(V8);
    long inquiryId = seedInquiry("handling", "OTHER", null, null, null, null, null);

    execute(
        "INSERT INTO inquiry_handling_record (inquiry_id, operator_id, action_type, old_value, "
            + "new_value, note) VALUES ("
            + inquiryId
            + ", 'agent-1', 'CATEGORY_CHANGE', '{\"category\":\"OTHER\"}', "
            + "'{\"category\":\"REFUND\"}', 'manual review')");
    execute(
        "INSERT INTO inquiry_handling_record (inquiry_id, operator_id, action_type) VALUES ("
            + inquiryId
            + ", 'agent-2', 'NOTE_ADD')");

    assertThat(count("SELECT COUNT(*) FROM inquiry_handling_record")).isEqualTo(2);
    assertThat(
            stringValue(
                "SELECT new_value FROM inquiry_handling_record WHERE action_type = "
                    + "'CATEGORY_CHANGE'"))
        .isEqualTo("{\"category\":\"REFUND\"}");

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO inquiry_handling_record (inquiry_id, action_type) VALUES "
                        + "(999999, 'NOTE_ADD')"))
        .isInstanceOf(SQLException.class);
    assertThat(count("SELECT COUNT(*) FROM inquiry_handling_record")).isEqualTo(2);
  }

  @Test
  @DisplayName("Status check constraints reject unknown values at the database level")
  void statusValuesAreConstrainedByDatabase() throws Exception {
    apply(V7_BASELINE);
    apply(V8);
    long inquiryId = seedInquiry("checks", "OTHER", null, null, null, null, null);

    assertThatThrownBy(
            () -> execute("UPDATE inquiries SET status = 'UNKNOWN' WHERE id = " + inquiryId))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () -> execute("UPDATE inquiries SET analysis_status = 'MAYBE' WHERE id = " + inquiryId))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO inquiry_analysis (inquiry_id, version, status) VALUES ("
                        + inquiryId
                        + ", 1, 'UNKNOWN')"))
        .isInstanceOf(SQLException.class);
  }
}
