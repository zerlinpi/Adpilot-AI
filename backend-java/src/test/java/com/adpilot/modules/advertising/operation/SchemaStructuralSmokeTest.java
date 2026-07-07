package com.adpilot.modules.advertising.operation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema smoke / structural checks for the advertising-workspace-rework spec
 * (task 2.5 — Requirements 7.1, 27.1, 49.5).
 *
 * <p>These checks parse {@code backend-java/db/schema.sql} — the single
 * authoritative schema imported manually into a fresh MySQL 8.0 database — and
 * assert the structural invariants this spec relies on, without requiring a live
 * database. The project deliberately ships no test-database infrastructure
 * (no H2, no Testcontainers, no DB-backed {@code @SpringBootTest}); the schema is
 * MySQL-8.0-specific ({@code DEFAULT (UUID())}, JSON, {@code CHECK}, {@code DATETIME(3)})
 * and cannot be replayed into an embedded engine. See the documented convention in
 * {@code com.adpilot.modules.logistics.SchemaSqlNewTablesIntegrationTest}.
 *
 * <p>Three invariants are verified:
 * <ol>
 *   <li><b>Pending_Overlay honored (Req 7.1).</b> Pending values live in the
 *       {@code operation_pending_changes} overlay table, NOT in a second physical
 *       column per writable field. No advertising entity table may carry a
 *       {@code *_pending} / {@code pending_*} companion column.</li>
 *   <li><b>Permission code seeded (Req 27.1).</b> The {@code advertising:execute}
 *       permission code is present in the permissions seed.</li>
 *   <li><b>Personality policy seed present (Req 49.5).</b> The
 *       {@code personality_policies} seed defines one {@code scope='system'} row per
 *       personality (conservative / balanced / aggressive), each carrying a
 *       {@code rule_version}.</li>
 * </ol>
 */
@DisplayName("schema.sql structural smoke checks (Req 7.1, 27.1, 49.5)")
class SchemaStructuralSmokeTest {

    /**
     * Advertising entity tables that carry writable fields surfaced through the
     * Pending_Overlay. None of them may duplicate a writable field into a pending
     * companion column (Req 7.1).
     */
    private static final List<String> ENTITY_TABLES_WITH_WRITABLE_FIELDS = List.of(
            "campaigns",
            "ad_groups",
            "keywords",
            "targets",
            "negative_keywords",
            "goals"
    );

    @Nested
    @DisplayName("Pending_Overlay: no duplicate pending column per writable field (Req 7.1)")
    class PendingOverlay {

        @Test
        @DisplayName("the operation_pending_changes overlay table backs pending values")
        void overlayTableExists() {
            // The overlay is the single place pending values live; it joins to the
            // entity by (entity_type, entity_id, field) rather than per-field columns.
            String block = tableBlock("operation_pending_changes");
            assertColumn(block, "operation_pending_changes", "operation_id");
            assertColumn(block, "operation_pending_changes", "entity_type");
            assertColumn(block, "operation_pending_changes", "entity_id");
            assertColumn(block, "operation_pending_changes", "field");
            assertColumn(block, "operation_pending_changes", "before_value");
            assertColumn(block, "operation_pending_changes", "after_value");
        }

        @Test
        @DisplayName("no advertising entity table carries a *_pending / pending_* companion column")
        void entityTablesHaveNoPendingCompanionColumns() {
            // A second physical column per writable field would be named like
            // bid_pending / budget_pending / pending_bid etc. The overlay approach
            // (Req 7.1) forbids any such duplication.
            Pattern pendingColumn = Pattern.compile(
                    "(^|[(,\\n])\\s*(\\w*_pending|pending_\\w+)\\s+",
                    Pattern.CASE_INSENSITIVE);
            for (String table : ENTITY_TABLES_WITH_WRITABLE_FIELDS) {
                String block = tableBlock(table);
                Matcher m = pendingColumn.matcher(block);
                boolean found = m.find();
                assertFalse(found,
                        () -> table + " must not duplicate a writable field into a pending "
                                + "companion column (found '" + (found ? m.group(2) : "")
                                + "'); pending values belong in operation_pending_changes (Req 7.1)");
            }
        }
    }

    @Nested
    @DisplayName("Permission seed: advertising:execute present (Req 27.1)")
    class PermissionSeed {

        @Test
        @DisplayName("the permissions seed includes the advertising:execute code")
        void advertisingExecutePermissionSeeded() {
            String sql = schemaSql();
            Pattern p = Pattern.compile("'advertising:execute'");
            assertTrue(p.matcher(sql).find(),
                    () -> "schema.sql permission seed must include 'advertising:execute' (Req 27.1)");
        }
    }

    @Nested
    @DisplayName("Personality policy seed: 3 system rows with rule_version (Req 49.5)")
    class PersonalityPolicySeed {

        @Test
        @DisplayName("personality_policies defines the (scope, scope_id, personality, rule_version) uniqueness and rule_version columns")
        void personalityPoliciesTableShape() {
            String block = tableBlock("personality_policies");
            assertColumn(block, "personality_policies", "scope");
            assertColumn(block, "personality_policies", "personality");
            assertColumn(block, "personality_policies", "rule_version");
            // rule_version must be NOT NULL so every seeded row records the in-effect version.
            assertTrue(block.toLowerCase(Locale.ROOT)
                            .matches("(?s).*rule_version\\s+varchar\\([0-9]+\\)\\s+not\\s+null.*"),
                    () -> "personality_policies.rule_version must be NOT NULL");
            // The (scope, scope_id, personality, rule_version) tuple must be unique. The spec
            // (amazon-ads-ai-hosting-system task 1.1 / Req 38) deliberately replaced the old
            // UNIQUE(scope, personality) with uq_pp_scope so multiple rule_versions (and
            // scope_ids) can coexist for the same scope+personality.
            assertTrue(block.toLowerCase(Locale.ROOT)
                            .matches("(?s).*unique\\s*\\(\\s*scope\\s*,\\s*scope_id\\s*,\\s*"
                                    + "personality\\s*,\\s*rule_version\\s*\\).*"),
                    () -> "personality_policies must enforce "
                            + "UNIQUE(scope, scope_id, personality, rule_version)");
        }

        @Test
        @DisplayName("the seed defines one scope='system' row per personality, each with a rule_version")
        void systemPolicySeedRowsPresent() {
            String seed = personalityPoliciesSeedBlock();
            for (String personality : List.of("conservative", "balanced", "aggressive")) {
                Pattern p = Pattern.compile(
                        "'system'\\s*,\\s*'" + Pattern.quote(personality) + "'",
                        Pattern.CASE_INSENSITIVE);
                assertTrue(p.matcher(seed).find(),
                        () -> "personality_policies seed must include a ('system', '"
                                + personality + "') row (Req 49.5)");
            }
            // Each seeded row terminates with a quoted rule_version literal (e.g. 'v1').
            int ruleVersionLiterals = countMatches(seed, Pattern.compile("'v\\d+'"));
            assertThat(ruleVersionLiterals)
                    .as("each of the 3 system personality_policies rows must carry a rule_version")
                    .isGreaterThanOrEqualTo(3);
        }
    }

    // =====================================================================
    // Helpers (mirrors com.adpilot.modules.logistics.SchemaSqlNewTablesIntegrationTest)
    // =====================================================================

    private static void assertColumn(String tableBlock, String table, String column) {
        Pattern p = Pattern.compile("(^|[(,\\n])\\s*" + Pattern.quote(column) + "\\s+",
                Pattern.CASE_INSENSITIVE);
        assertTrue(p.matcher(tableBlock).find(),
                () -> table + " must define a '" + column + "' column");
    }

    private static int countMatches(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
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
        Pattern next = Pattern.compile("create\\s+(table|index|unique)", Pattern.CASE_INSENSITIVE);
        Matcher n = next.matcher(sql);
        int to = sql.length();
        if (n.find(m.end())) {
            to = n.start();
        }
        return sql.substring(from, to);
    }

    /** Returns the {@code INSERT ... INTO personality_policies ... VALUES ...} seed statement. */
    private static String personalityPoliciesSeedBlock() {
        String sql = schemaSql();
        Pattern start = Pattern.compile(
                "insert\\s+(ignore\\s+)?into\\s+personality_policies\\s*\\(",
                Pattern.CASE_INSENSITIVE);
        Matcher m = start.matcher(sql);
        assertTrue(m.find(), () -> "schema.sql must include a personality_policies seed INSERT");
        int from = m.start();
        // The seed statement ends at its terminating semicolon.
        int semi = sql.indexOf(';', m.end());
        int to = semi < 0 ? sql.length() : semi;
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
                return candidate;
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
        throw new IllegalStateException(
                "Could not locate db/schema.sql from " + Paths.get("").toAbsolutePath());
    }
}
