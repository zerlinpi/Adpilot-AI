package com.adpilot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused, context-free check that the JPA {@code spring.jpa.hibernate.ddl-auto}
 * value in each application configuration file is Flyway-safe.
 *
 * <p>Flyway is the sole owner of the database schema, so Hibernate must only
 * {@code validate} (or do {@code none}) and must never {@code update} or
 * {@code create} the schema.
 *
 * <p>This reads the YAML resources directly from the classpath and parses only
 * the {@code spring.jpa.hibernate.ddl-auto} value. It deliberately avoids a DB
 * connection or a full Spring context.
 *
 * Validates: Requirements 5.2, 5.3
 */
class DdlAutoConfigTest {

    @ParameterizedTest(name = "{0} has a Flyway-safe ddl-auto value")
    @ValueSource(strings = {
            "application.yml",
            "application-dev.yml",
            "application-prod.yml"
    })
    @DisplayName("ddl-auto must be validate/none and never update/create")
    void ddlAutoIsValidateOrNone(String resourceName) throws Exception {
        String ddlAuto = readDdlAuto(resourceName);

        assertNotNull(ddlAuto,
                () -> resourceName + " must define spring.jpa.hibernate.ddl-auto");

        String normalized = ddlAuto.trim().toLowerCase(Locale.ROOT);

        assertTrue(normalized.equals("validate") || normalized.equals("none"),
                () -> resourceName + " has ddl-auto='" + ddlAuto
                        + "', expected 'validate' or 'none'");

        assertTrue(!normalized.equals("update") && !normalized.equals("create")
                        && !normalized.equals("create-drop"),
                () -> resourceName + " must never use a schema-mutating ddl-auto value (was '"
                        + ddlAuto + "')");
    }

    /**
     * Reads {@code spring.jpa.hibernate.ddl-auto} from a YAML resource on the
     * test classpath (the same files packaged from src/main/resources).
     *
     * @return the configured value, or {@code null} if not present in the file
     */
    @SuppressWarnings("unchecked")
    private String readDdlAuto(String resourceName) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(in, () -> "Could not find " + resourceName + " on the classpath");

            // Spring config files may contain multiple YAML documents (--- separators).
            Yaml yaml = new Yaml();
            for (Object document : yaml.loadAll(in)) {
                if (!(document instanceof Map)) {
                    continue;
                }
                Object value = navigate((Map<String, Object>) document,
                        "spring", "jpa", "hibernate", "ddl-auto");
                if (value != null) {
                    return String.valueOf(value);
                }
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Object navigate(Map<String, Object> root, String... path) {
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
