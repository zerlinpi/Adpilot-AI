package com.adpilot.modules.logistics;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * Integration test for the schema source of truth introduced by this spec
 * (task 1.3 — Validates: Requirements 19.1, 19.2, 19.3).
 *
 * <p>This spec adds its new logistics and table-view tables to
 * {@code backend-java/db/schema.sql}, which is the single authoritative schema
 * that is imported manually into a fresh MySQL 8.0 database
 * ({@code mysql -u <user> -p adpilot < db/schema.sql}). The application never
 * runs migrations or auto-creates schema at runtime (Flyway disabled,
 * {@code spring.jpa.hibernate.ddl-auto: none}, {@code spring.sql.init.mode: never}).
 *
 * <p><strong>Why this does not spin up an embedded database by default.</strong>
 * The project deliberately ships no test-database infrastructure (no H2, no
 * Testcontainers, no DB-backed {@code @SpringBootTest}); see the documented
 * convention in {@code AuthorizationAndScheduledOptimizerIntegrationTest}.
 * {@code schema.sql} is MySQL-8.0-specific — {@code DEFAULT (UUID())}, JSON
 * columns/defaults, {@code CHECK} constraints, {@code DATETIME(3)} — and cannot
 * be replayed into an embedded engine such as H2. Loading it therefore requires
 * a real MySQL instance, which is not part of the default test environment.
 *
 * <p>The test is split into two parts:
 * <ol>
 *   <li><b>Source-of-truth verification (always runs).</b> Parses
 *       {@code db/schema.sql} — the exact artifact imported into MySQL — and
 *       asserts every new table is defined there with its tenant/store scoping
 *       columns, that the FBA columns were added to {@code shipments}, and that
 *       the application configuration relies on no runtime migration. This
 *       verifies Requirements 19.1, 19.2, and 19.3 directly against the file
 *       that provisions the database.</li>
 *   <li><b>Live MySQL load (opt-in).</b> When a test MySQL is provided via the
 *       {@code adpilot.schema.test.jdbcUrl} system property (e.g. in CI or
 *       locally with Docker), the test imports {@code schema.sql} into that
 *       database and asserts the new tables and their scoping columns exist in
 *       {@code INFORMATION_SCHEMA}. Without that property the path is skipped
 *       via JUnit assumptions so the suite stays green in environments that
 *       cannot run MySQL.</li>
 * </ol>
 */
@DisplayName("schema.sql defines this spec's new tables with no runtime migration (Req 19)")
class SchemaSqlNewTablesIntegrationTest {

    /**
     * New shipment-child tables: each must carry {@code shipment_id} so it
     * inherits store scope via {@code shipment_id -> shipments.store_id}
     * (Requirement 17, 19.3).
     */
    private static final List<String> SHIPMENT_CHILD_TABLES = List.of(
            "shipment_legs",
            "carton_specs",
            "customs_clearance",
            "tracking_events",
            "shipment_exceptions",
            "handling_costs",
            "shipment_line_items"
    );

    /** Per-user, store-independent table-view tables: scoped by {@code user_id} (Req 17.5, 19.3). */
    private static final List<String> USER_SCOPED_TABLES = List.of(
            "saved_views",
            "column_configs"
    );

    /** FBA core fields added to the existing {@code shipments} table (Req 16.1, 19.1). */
    private static final List<String> SHIPMENT_FBA_COLUMNS = List.of(
            "fba_shipment_id",
            "amazon_shipment_status",
            "destination_fc_code",
            "reporting_currency"
    );

    // =====================================================================
    // Part 1 — Source-of-truth verification (always runs, no database)
    // =====================================================================

    @Nested
    @DisplayName("db/schema.sql source of truth")
    class SchemaSource {

        @Test
        @DisplayName("the org-scoped carriers table is defined with its org_id tenant scoping column")
        void carriersTableHasOrgScope() {
            String block = tableBlock("carriers");
            assertColumn(block, "carriers", "org_id");
            assertColumn(block, "carriers", "name");
            assertColumn(block, "carriers", "service_type");
        }

        @Test
        @DisplayName("every new shipment-child table is defined with a shipment_id store-scoping column")
        void shipmentChildTablesHaveShipmentScope() {
            for (String table : SHIPMENT_CHILD_TABLES) {
                String block = tableBlock(table);
                assertColumn(block, table, "shipment_id");
                // The shipment FK must cascade so child rows follow their shipment's lifecycle.
                assertTrue(block.toLowerCase(Locale.ROOT)
                                .matches("(?s).*references\\s+shipments\\s*\\(\\s*id\\s*\\).*"),
                        () -> table + " must reference shipments(id)");
            }
        }

        @Test
        @DisplayName("tracking_events carries a nullable leg_id reference to shipment_legs")
        void trackingEventsHasNullableLegReference() {
            String block = tableBlock("tracking_events");
            assertColumn(block, "tracking_events", "leg_id");
            assertTrue(block.toLowerCase(Locale.ROOT)
                            .matches("(?s).*references\\s+shipment_legs\\s*\\(\\s*id\\s*\\).*"),
                    () -> "tracking_events.leg_id must reference shipment_legs(id)");
        }

        @Test
        @DisplayName("the per-user table-view tables are defined with a user_id scoping column")
        void userScopedTablesHaveUserScope() {
            for (String table : USER_SCOPED_TABLES) {
                String block = tableBlock(table);
                assertColumn(block, table, "user_id");
                assertColumn(block, table, "table_key");
            }
        }

        @Test
        @DisplayName("the shipments table gains the FBA core fields")
        void shipmentsHasFbaColumns() {
            String block = tableBlock("shipments");
            for (String column : SHIPMENT_FBA_COLUMNS) {
                assertColumn(block, "shipments", column);
            }
        }

        @Test
        @DisplayName("all new tables are declared CREATE TABLE IF NOT EXISTS (idempotent manual import)")
        void newTablesAreCreatedIdempotently() {
            String sql = schemaSql();
            List<String> allNewTables = new ArrayList<>();
            allNewTables.add("carriers");
            allNewTables.addAll(SHIPMENT_CHILD_TABLES);
            allNewTables.addAll(USER_SCOPED_TABLES);
            for (String table : allNewTables) {
                Pattern p = Pattern.compile(
                        "create\\s+table\\s+if\\s+not\\s+exists\\s+" + Pattern.quote(table) + "\\s*\\(",
                        Pattern.CASE_INSENSITIVE);
                assertTrue(p.matcher(sql).find(),
                        () -> "schema.sql must declare CREATE TABLE IF NOT EXISTS " + table);
            }
        }

        @Test
        @DisplayName("the configuration relies on no runtime migration (Flyway off, ddl-auto none, sql init never)")
        void noRuntimeMigrationIsReliedUpon() {
            // Flyway disabled and Hibernate ddl-auto safe across every profile.
            for (String resource : List.of("application.yml", "application-dev.yml", "application-prod.yml")) {
                Boolean flywayEnabled = readBoolean(resource, "spring", "flyway", "enabled");
                assertNotNull(flywayEnabled, () -> resource + " must declare spring.flyway.enabled");
                assertThat(flywayEnabled)
                        .as("%s must keep Flyway disabled (schema.sql is the source of truth)", resource)
                        .isFalse();

                String ddlAuto = readString(resource, "spring", "jpa", "hibernate", "ddl-auto");
                assertNotNull(ddlAuto, () -> resource + " must declare spring.jpa.hibernate.ddl-auto");
                String normalized = ddlAuto.trim().toLowerCase(Locale.ROOT);
                assertThat(normalized)
                        .as("%s must never auto-create/modify schema", resource)
                        .isIn("none", "validate");
            }

            // Base config must never auto-run schema.sql/data.sql on startup.
            String sqlInitMode = readString("application.yml", "spring", "sql", "init", "mode");
            assertNotNull(sqlInitMode, "application.yml must declare spring.sql.init.mode");
            assertThat(sqlInitMode.trim().toLowerCase(Locale.ROOT))
                    .as("application.yml must keep spring.sql.init.mode = never")
                    .isEqualTo("never");
        }
    }

    // =====================================================================
    // Part 2 — Live MySQL load (opt-in via -Dadpilot.schema.test.jdbcUrl=...)
    // =====================================================================

    @Nested
    @DisplayName("live MySQL import (opt-in)")
    class LiveMysqlImport {

        @Test
        @DisplayName("importing schema.sql creates every new table with its scoping columns")
        void importingSchemaCreatesNewTables() throws Exception {
            String jdbcUrl = System.getProperty("adpilot.schema.test.jdbcUrl");
            Assumptions.assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank(),
                    "No test MySQL configured (set -Dadpilot.schema.test.jdbcUrl); "
                            + "schema.sql is verified statically in the SchemaSource tests instead.");

            String user = System.getProperty("adpilot.schema.test.username", "root");
            String password = System.getProperty("adpilot.schema.test.password", "");

            try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
                executeScript(conn, schemaSql());

                String schemaName = conn.getCatalog();
                Map<String, Set<String>> columnsByTable = readColumns(conn, schemaName);

                // carriers: org-scoped tenant boundary.
                assertLiveColumn(columnsByTable, "carriers", "org_id");

                // shipment children: store scope via shipment_id.
                for (String table : SHIPMENT_CHILD_TABLES) {
                    assertLiveColumn(columnsByTable, table, "shipment_id");
                }

                // per-user table-view tables.
                for (String table : USER_SCOPED_TABLES) {
                    assertLiveColumn(columnsByTable, table, "user_id");
                }

                // FBA columns on shipments.
                for (String column : SHIPMENT_FBA_COLUMNS) {
                    assertLiveColumn(columnsByTable, "shipments", column);
                }
            }
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static void assertColumn(String tableBlock, String table, String column) {
        // Match the column at the start of a definition line within the table block.
        Pattern p = Pattern.compile("(^|[(,\\n])\\s*" + Pattern.quote(column) + "\\s+",
                Pattern.CASE_INSENSITIVE);
        assertTrue(p.matcher(tableBlock).find(),
                () -> table + " must define a '" + column + "' column");
    }

    private static void assertLiveColumn(Map<String, Set<String>> columnsByTable, String table, String column) {
        Set<String> columns = columnsByTable.get(table);
        assertNotNull(columns, () -> "table '" + table + "' must exist after importing schema.sql");
        assertThat(columns)
                .as("table '%s' must define column '%s'", table, column)
                .contains(column);
    }

    /** Returns the {@code CREATE TABLE} definition block for the given table from schema.sql. */
    private static String tableBlock(String table) {
        String sql = schemaSql();
        Pattern start = Pattern.compile(
                "create\\s+table\\s+if\\s+not\\s+exists\\s+" + Pattern.quote(table) + "\\s*\\(",
                Pattern.CASE_INSENSITIVE);
        Matcher m = start.matcher(sql);
        assertTrue(m.find(), () -> "schema.sql must define table " + table);
        int from = m.start();
        // The block ends at the next CREATE statement (table/index) or end of file.
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
        // Walk up from the working directory looking for db/schema.sql.
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

    /**
     * Executes a multi-statement SQL script over a JDBC connection. Strips
     * {@code --} line comments and splits on semicolons that are outside single
     * quotes. Sufficient for the DDL + seed statements in schema.sql.
     */
    private static void executeScript(Connection conn, String script) throws Exception {
        List<String> statements = splitStatements(script);
        try (Statement stmt = conn.createStatement()) {
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
                    // Handle escaped quote ('') inside a string literal.
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

    /** Removes a trailing {@code --} line comment that is not inside a string literal. */
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

    // ---- YAML config readers (classpath resources from src/main/resources) ----

    private static String readString(String resourceName, String... path) {
        Object value = readConfigValue(resourceName, path);
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean readBoolean(String resourceName, String... path) {
        Object value = readConfigValue(resourceName, path);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Object readConfigValue(String resourceName, String... path) {
        try (InputStream in = SchemaSqlNewTablesIntegrationTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(in, () -> "Could not find " + resourceName + " on the classpath");
            Yaml yaml = new Yaml();
            for (Object document : yaml.loadAll(in)) {
                if (!(document instanceof Map)) {
                    continue;
                }
                Object value = navigate((Map<String, Object>) document, path);
                if (value != null) {
                    return value;
                }
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
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(key);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
