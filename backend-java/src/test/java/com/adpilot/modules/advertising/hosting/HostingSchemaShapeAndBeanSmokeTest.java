package com.adpilot.modules.advertising.hosting;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-shape and bean smoke tests for the Amazon Ads AI Hosting System.
 *
 * <p>Validates that {@code db/schema.sql} defines all new hosting tables with the
 * expected columns and constraints, and that existing tables have been extended
 * with the required new columns and unique constraints.
 *
 * <p><b>Validates: Requirements 1.1, 6.1, 14.1, 14.3, 22.1, 31.2, 32.2, 32.3, 32.5, 37.1</b>
 *
 * <p>The test is split into two parts:
 * <ol>
 *   <li><b>Source-of-truth verification (always runs).</b> Parses {@code db/schema.sql}
 *       and asserts every new hosting table is defined with expected columns and
 *       constraints. Also verifies existing table modifications.</li>
 *   <li><b>Live MySQL load (opt-in).</b> When a test MySQL is provided via the
 *       {@code adpilot.schema.test.jdbcUrl} system property, imports schema.sql
 *       and verifies tables exist in INFORMATION_SCHEMA.</li>
 * </ol>
 */
@DisplayName("Hosting schema shape and bean smoke tests (Req 1.1, 6.1, 14.1, 14.3, 22.1, 31.2, 32.2, 32.3, 32.5, 37.1)")
class HostingSchemaShapeAndBeanSmokeTest {

    /**
     * New hosting tables that must exist in schema.sql (task 1.2).
     */
    private static final List<String> NEW_HOSTING_TABLES = List.of(
            "report_sync_runs",
            "report_sync_errors",
            "search_term_daily",
            "metric_quarantine",
            "safety_boundaries",
            "brand_word_lists",
            "ai_decisions",
            "effect_attributions",
            "optimization_runs",
            "notification_delivery_log",
            "hosting_kill_switches",
            "hosting_configs"
    );

    // =====================================================================
    // Part 1 — Source-of-truth verification (always runs, no database)
    // =====================================================================

    @Nested
    @DisplayName("db/schema.sql source of truth — new hosting tables")
    class NewHostingTables {

        @Test
        @DisplayName("all new hosting tables are declared with CREATE TABLE IF NOT EXISTS")
        void allNewHostingTablesExist() {
            String sql = schemaSql();
            for (String table : NEW_HOSTING_TABLES) {
                Pattern p = Pattern.compile(
                        "create\\s+table\\s+if\\s+not\\s+exists\\s+" + Pattern.quote(table) + "\\s*\\(",
                        Pattern.CASE_INSENSITIVE);
                assertTrue(p.matcher(sql).find(),
                        () -> "schema.sql must declare CREATE TABLE IF NOT EXISTS " + table);
            }
        }

        @Test
        @DisplayName("report_sync_runs has store_id, report_type, report_status, data_status columns (Req 31.2)")
        void reportSyncRunsShape() {
            String block = tableBlock("report_sync_runs");
            assertColumn(block, "report_sync_runs", "store_id");
            assertColumn(block, "report_sync_runs", "report_type");
            assertColumn(block, "report_sync_runs", "report_status");
            assertColumn(block, "report_sync_runs", "data_status");
            assertColumn(block, "report_sync_runs", "row_count");
            assertColumn(block, "report_sync_runs", "amazon_report_id");
        }

        @Test
        @DisplayName("report_sync_errors has run_id, store_id, attempt columns")
        void reportSyncErrorsShape() {
            String block = tableBlock("report_sync_errors");
            assertColumn(block, "report_sync_errors", "run_id");
            assertColumn(block, "report_sync_errors", "store_id");
            assertColumn(block, "report_sync_errors", "attempt");
        }

        @Test
        @DisplayName("search_term_daily has the expected structure (Req 32.5)")
        void searchTermDailyShape() {
            String block = tableBlock("search_term_daily");
            assertColumn(block, "search_term_daily", "store_id");
            assertColumn(block, "search_term_daily", "campaign_id");
            assertColumn(block, "search_term_daily", "ad_group_id");
            assertColumn(block, "search_term_daily", "search_term");
            assertColumn(block, "search_term_daily", "report_date");
            assertColumn(block, "search_term_daily", "data_status");
            assertColumn(block, "search_term_daily", "data_version");
            // Unique constraint
            assertContains(block, "uq_std", "search_term_daily must define uq_std constraint");
        }

        @Test
        @DisplayName("metric_quarantine has store_id, report_type, external_entity_id columns (Req 14.1)")
        void metricQuarantineShape() {
            String block = tableBlock("metric_quarantine");
            assertColumn(block, "metric_quarantine", "store_id");
            assertColumn(block, "metric_quarantine", "report_type");
            assertColumn(block, "metric_quarantine", "external_entity_type");
            assertColumn(block, "metric_quarantine", "external_entity_id");
            assertColumn(block, "metric_quarantine", "raw_row");
            assertColumn(block, "metric_quarantine", "resolved");
        }

        @Test
        @DisplayName("safety_boundaries has typed value model with comparison semantics (Req 6.1)")
        void safetyBoundariesShape() {
            String block = tableBlock("safety_boundaries");
            assertColumn(block, "safety_boundaries", "store_id");
            assertColumn(block, "safety_boundaries", "scope");
            assertColumn(block, "safety_boundaries", "scope_id");
            assertColumn(block, "safety_boundaries", "limit_type");
            assertColumn(block, "safety_boundaries", "value_type");
            assertColumn(block, "safety_boundaries", "value_amount");
            assertColumn(block, "safety_boundaries", "comparison_semantics");
            // Unique constraint
            assertContains(block, "uq_sb", "safety_boundaries must define uq_sb constraint");
        }

        @Test
        @DisplayName("brand_word_lists has store_id, word, match_type columns (Req 22.1)")
        void brandWordListsShape() {
            String block = tableBlock("brand_word_lists");
            assertColumn(block, "brand_word_lists", "store_id");
            assertColumn(block, "brand_word_lists", "word");
            assertColumn(block, "brand_word_lists", "match_type");
        }

        @Test
        @DisplayName("ai_decisions has the full decision store schema (Req 37.1)")
        void aiDecisionsShape() {
            String block = tableBlock("ai_decisions");
            assertColumn(block, "ai_decisions", "store_id");
            assertColumn(block, "ai_decisions", "run_id");
            assertColumn(block, "ai_decisions", "campaign_id");
            assertColumn(block, "ai_decisions", "engine");
            assertColumn(block, "ai_decisions", "decision_type");
            assertColumn(block, "ai_decisions", "execution_mode");
            assertColumn(block, "ai_decisions", "risk_score");
            assertColumn(block, "ai_decisions", "promoted_operation_id");
            assertColumn(block, "ai_decisions", "decision_snapshot");
        }

        @Test
        @DisplayName("effect_attributions has operation_id, metric_type, observed_change columns")
        void effectAttributionsShape() {
            String block = tableBlock("effect_attributions");
            assertColumn(block, "effect_attributions", "operation_id");
            assertColumn(block, "effect_attributions", "store_id");
            assertColumn(block, "effect_attributions", "metric_type");
            assertColumn(block, "effect_attributions", "observed_change");
            assertColumn(block, "effect_attributions", "estimated_incremental_impact");
            assertColumn(block, "effect_attributions", "attribution_confidence");
        }

        @Test
        @DisplayName("optimization_runs has trigger_type, status, and run counters")
        void optimizationRunsShape() {
            String block = tableBlock("optimization_runs");
            assertColumn(block, "optimization_runs", "store_id");
            assertColumn(block, "optimization_runs", "trigger_type");
            assertColumn(block, "optimization_runs", "status");
            assertColumn(block, "optimization_runs", "campaigns_processed");
            assertColumn(block, "optimization_runs", "operations_created");
            assertColumn(block, "optimization_runs", "per_campaign_results");
        }

        @Test
        @DisplayName("notification_delivery_log has notification_type, status, attempt_count columns")
        void notificationDeliveryLogShape() {
            String block = tableBlock("notification_delivery_log");
            assertColumn(block, "notification_delivery_log", "store_id");
            assertColumn(block, "notification_delivery_log", "notification_type");
            assertColumn(block, "notification_delivery_log", "status");
            assertColumn(block, "notification_delivery_log", "attempt_count");
            assertColumn(block, "notification_delivery_log", "last_error");
        }

        @Test
        @DisplayName("hosting_kill_switches has scope, scope_id, status, activated_by columns")
        void hostingKillSwitchesShape() {
            String block = tableBlock("hosting_kill_switches");
            assertColumn(block, "hosting_kill_switches", "scope");
            assertColumn(block, "hosting_kill_switches", "scope_id");
            assertColumn(block, "hosting_kill_switches", "status");
            assertColumn(block, "hosting_kill_switches", "activated_by");
            // Unique constraint
            assertContains(block, "uq_ks", "hosting_kill_switches must define uq_ks constraint");
        }

        @Test
        @DisplayName("hosting_configs has store_id, scope, scope_id, config columns")
        void hostingConfigsShape() {
            String block = tableBlock("hosting_configs");
            assertColumn(block, "hosting_configs", "store_id");
            assertColumn(block, "hosting_configs", "scope");
            assertColumn(block, "hosting_configs", "scope_id");
            assertColumn(block, "hosting_configs", "config");
            // Unique constraint
            assertContains(block, "uq_hc", "hosting_configs must define uq_hc constraint");
        }
    }

    @Nested
    @DisplayName("db/schema.sql source of truth — existing table modifications")
    class ExistingTableModifications {

        @Test
        @DisplayName("performance_daily has report_date, currency, data_status, data_version, updated_at and uq_perf_daily (Req 32.2, 32.3)")
        void performanceDailyHardenedColumns() {
            String block = tableBlock("performance_daily");
            assertColumn(block, "performance_daily", "report_date");
            assertColumn(block, "performance_daily", "currency");
            assertColumn(block, "performance_daily", "data_status");
            assertColumn(block, "performance_daily", "data_version");
            assertColumn(block, "performance_daily", "updated_at");
            assertContains(block, "uq_perf_daily",
                    "performance_daily must define the uq_perf_daily unique constraint");
        }

        @Test
        @DisplayName("external_entity_mappings has the uq_eem_external constraint (Req 14.3)")
        void externalEntityMappingsUniqueConstraint() {
            String block = tableBlock("external_entity_mappings");
            assertContains(block, "uq_eem_external",
                    "external_entity_mappings must define the uq_eem_external unique constraint");
        }

        @Test
        @DisplayName("operation_outbox has next_attempt_at and last_error columns")
        void operationOutboxRetryColumns() {
            String block = tableBlock("operation_outbox");
            assertColumn(block, "operation_outbox", "next_attempt_at");
            assertColumn(block, "operation_outbox", "last_error");
        }

        @Test
        @DisplayName("personality_policies has scope_id, status, effective_from, effective_to, and uq_pp_scope")
        void personalityPoliciesScopingFix() {
            String block = tableBlock("personality_policies");
            assertColumn(block, "personality_policies", "scope_id");
            assertColumn(block, "personality_policies", "status");
            assertColumn(block, "personality_policies", "effective_from");
            assertColumn(block, "personality_policies", "effective_to");
            assertContains(block, "uq_pp_scope",
                    "personality_policies must define the uq_pp_scope unique constraint");
        }
    }

    @Nested
    @DisplayName("db/schema.sql source of truth — configuration validation")
    class ConfigurationValidation {

        @Test
        @DisplayName("the configuration relies on no runtime migration (Flyway off, ddl-auto none)")
        void noRuntimeMigration() {
            for (String resource : List.of("application.yml", "application-dev.yml")) {
                Boolean flywayEnabled = readBoolean(resource, "spring", "flyway", "enabled");
                assertNotNull(flywayEnabled, () -> resource + " must declare spring.flyway.enabled");
                assertThat(flywayEnabled)
                        .as("%s must keep Flyway disabled", resource)
                        .isFalse();

                String ddlAuto = readString(resource, "spring", "jpa", "hibernate", "ddl-auto");
                assertNotNull(ddlAuto, () -> resource + " must declare spring.jpa.hibernate.ddl-auto");
                String normalized = ddlAuto.trim().toLowerCase(Locale.ROOT);
                assertThat(normalized)
                        .as("%s must never auto-create/modify schema", resource)
                        .isIn("none", "validate");
            }
        }
    }

    // =====================================================================
    // Part 2 — Live MySQL load (opt-in via -Dadpilot.schema.test.jdbcUrl=...)
    // =====================================================================

    @Nested
    @DisplayName("live MySQL import (opt-in)")
    class LiveMysqlImport {

        @Test
        @DisplayName("importing schema.sql creates all new hosting tables with expected columns")
        void importingSchemaCreatesHostingTables() throws Exception {
            String jdbcUrl = System.getProperty("adpilot.schema.test.jdbcUrl");
            Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank(),
                    "No test MySQL configured (set -Dadpilot.schema.test.jdbcUrl); "
                            + "schema.sql is verified statically in source-of-truth tests instead.");

            String user = System.getProperty("adpilot.schema.test.username", "root");
            String password = System.getProperty("adpilot.schema.test.password", "");

            try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
                executeScript(conn, schemaSql());

                String schemaName = conn.getCatalog();
                Map<String, Set<String>> columnsByTable = readColumns(conn, schemaName);

                // Verify all new hosting tables exist
                for (String table : NEW_HOSTING_TABLES) {
                    assertThat(columnsByTable).containsKey(table);
                }

                // performance_daily columns
                assertLiveColumn(columnsByTable, "performance_daily", "report_date");
                assertLiveColumn(columnsByTable, "performance_daily", "currency");
                assertLiveColumn(columnsByTable, "performance_daily", "data_status");
                assertLiveColumn(columnsByTable, "performance_daily", "data_version");
                assertLiveColumn(columnsByTable, "performance_daily", "updated_at");

                // operation_outbox columns
                assertLiveColumn(columnsByTable, "operation_outbox", "next_attempt_at");
                assertLiveColumn(columnsByTable, "operation_outbox", "last_error");

                // personality_policies columns
                assertLiveColumn(columnsByTable, "personality_policies", "scope_id");
                assertLiveColumn(columnsByTable, "personality_policies", "status");
                assertLiveColumn(columnsByTable, "personality_policies", "effective_from");
                assertLiveColumn(columnsByTable, "personality_policies", "effective_to");
            }
        }
    }

    // =====================================================================
    // Bean registration validation (connector and engine beans)
    // =====================================================================

    @Nested
    @DisplayName("connector and engine bean source files exist")
    class BeanSourceFiles {

        @Test
        @DisplayName("PlatformWriteConnector SPI interface exists (Req 1.1 prerequisite)")
        void platformWriteConnectorSpiExists() {
            // The SPI interface should exist in the apisync module
            boolean found = sourceFileExists(
                    "com/adpilot/modules/apisync/connector/PlatformWriteConnector.java")
                    || sourceFileExists(
                    "com/adpilot/modules/advertising/platform/PlatformWriteConnector.java");
            assertTrue(found, "PlatformWriteConnector SPI must exist as a source file");
        }

        @Test
        @DisplayName("PlatformWriteConnectorConfig bean registration exists")
        void platformWriteConnectorConfigExists() {
            boolean found = sourceFileExists(
                    "com/adpilot/modules/apisync/connector/PlatformWriteConnectorConfig.java");
            assertTrue(found, "PlatformWriteConnectorConfig (bean wiring) must exist as a source file");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static void assertColumn(String tableBlock, String table, String column) {
        Pattern p = Pattern.compile("(^|[(,\\n])\\s*" + Pattern.quote(column) + "\\s+",
                Pattern.CASE_INSENSITIVE);
        assertTrue(p.matcher(tableBlock).find(),
                () -> table + " must define a '" + column + "' column");
    }

    private static void assertContains(String block, String text, String message) {
        assertTrue(block.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)), message);
    }

    private static void assertLiveColumn(Map<String, Set<String>> columnsByTable, String table, String column) {
        Set<String> columns = columnsByTable.get(table);
        assertNotNull(columns, () -> "table '" + table + "' must exist after importing schema.sql");
        assertThat(columns)
                .as("table '%s' must define column '%s'", table, column)
                .contains(column);
    }

    /** Returns the CREATE TABLE definition block for the given table from schema.sql. */
    private static String tableBlock(String table) {
        String sql = schemaSql();
        Pattern start = Pattern.compile(
                "create\\s+table\\s+if\\s+not\\s+exists\\s+" + Pattern.quote(table) + "\\s*\\(",
                Pattern.CASE_INSENSITIVE);
        Matcher m = start.matcher(sql);
        assertTrue(m.find(), () -> "schema.sql must define table " + table);
        int from = m.start();
        // The block ends at the next CREATE statement or end of file.
        Matcher next = Pattern.compile("create\\s+(table|index|unique)", Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        int to = sql.length();
        if (next.find(m.end())) {
            to = next.start();
        }
        return sql.substring(from, to);
    }

    private static String schemaSql() {
        Path path = locateSchemaSql();
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read schema at " + path, e);
        }
    }

    /** Locates {@code db/schema.sql} relative to the module/working directory, walking up if needed. */
    private static Path locateSchemaSql() {
        List<Path> candidates = List.of(
                Paths.get("db", "schema.sql"),
                Paths.get("backend-java", "db", "schema.sql"),
                Paths.get("..", "db", "schema.sql"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("db").resolve("schema.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            candidate = dir.resolve("backend-java").resolve("db").resolve("schema.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate db/schema.sql from " + Paths.get("").toAbsolutePath());
    }

    private static boolean sourceFileExists(String relativePath) {
        // Check in src/main/java relative to the module root
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("src").resolve("main").resolve("java").resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return true;
            }
            candidate = dir.resolve("backend-java").resolve("src").resolve("main").resolve("java").resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return true;
            }
            dir = dir.getParent();
        }
        return false;
    }

    private static void assertSourceFileExists(String relativePath, String message) {
        assertTrue(sourceFileExists(relativePath), message);
    }

    /**
     * Executes a multi-statement SQL script over a JDBC connection.
     */
    private static void executeScript(Connection conn, String script) throws Exception {
        List<String> statements = splitStatements(script);
        try (var stmt = conn.createStatement()) {
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    stmt.execute(trimmed);
                }
            }
        }
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        String[] lines = script.split("\n");
        for (String rawLine : lines) {
            String line = stripLineComment(rawLine);
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'') {
                    if (inSingleQuote && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                        current.append("''");
                        i++;
                        continue;
                    }
                    inSingleQuote = !inSingleQuote;
                    current.append(c);
                } else if (c == ';' && !inSingleQuote) {
                    statements.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            current.append('\n');
        }
        if (!current.toString().trim().isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private static String stripLineComment(String line) {
        boolean inSingleQuote = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
            } else if (!inSingleQuote && c == '-' && line.charAt(i + 1) == '-') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static Map<String, Set<String>> readColumns(Connection conn, String schemaName) throws Exception {
        Map<String, Set<String>> columnsByTable = new LinkedHashMap<>();
        String query = "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = ?";
        try (var ps = conn.prepareStatement(query)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1).toLowerCase(Locale.ROOT);
                    String column = rs.getString(2).toLowerCase(Locale.ROOT);
                    columnsByTable.computeIfAbsent(table, k -> new TreeSet<>()).add(column);
                }
            }
        }
        return columnsByTable;
    }

    // ---- YAML config readers ----

    private static String readString(String resourceName, String... path) {
        Object value = readConfigValue(resourceName, path);
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean readBoolean(String resourceName, String... path) {
        Object value = readConfigValue(resourceName, path);
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        return Boolean.valueOf(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Object readConfigValue(String resourceName, String... path) {
        try (InputStream in = HostingSchemaShapeAndBeanSmokeTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(in, () -> "Could not find " + resourceName + " on the classpath");
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            for (Object document : yaml.loadAll(in)) {
                if (!(document instanceof Map)) continue;
                Object value = navigate((Map<String, Object>) document, path);
                if (value != null) return value;
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + resourceName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object navigate(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(key);
            if (current == null) return null;
        }
        return current;
    }
}
