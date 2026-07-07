package com.adpilot.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused, context-free check that {@link WebConfig} resolves its CORS allowed
 * origins from the {@code adpilot.cors.allowed-origins} property (bound through
 * the constructor) and surfaces them in the produced {@link CorsFilter}.
 *
 * <p>This deliberately avoids a full Spring context (no DB/Redis) by using
 * constructor injection directly, mirroring how Spring binds the comma-separated
 * {@code adpilot.cors.allowed-origins} value into the {@code List<String>}
 * parameter.
 *
 * Validates: Requirements 14.2
 */
class WebConfigTest {

    /**
     * The deployment origins configured under the prod profile in
     * {@code application-prod.yml} (adpilot.cors.allowed-origins). Spring binds
     * the comma-separated property value into a {@code List<String>}, which is
     * what we supply here.
     */
    private static final List<String> PROD_ORIGINS =
            List.of("https://your-domain.example");

    @Test
    @DisplayName("CorsFilter exposes the origins resolved from adpilot.cors.allowed-origins")
    void corsFilterContainsConfiguredOrigins() {
        WebConfig webConfig = new WebConfig(PROD_ORIGINS);

        List<String> resolved = allowedOriginsFromFilter(webConfig.corsFilter());

        assertThat(resolved)
                .containsExactlyElementsOf(PROD_ORIGINS);
    }

    @Test
    @DisplayName("Prod profile deployment origins are included in the CORS configuration")
    void prodDeploymentOriginsAreIncluded() {
        WebConfig webConfig = new WebConfig(PROD_ORIGINS);

        List<String> resolved = allowedOriginsFromFilter(webConfig.corsFilter());

        assertThat(resolved)
                .contains("https://your-domain.example");
    }

    @Test
    @DisplayName("Default local dev origin is honored when only it is supplied")
    void defaultLocalOriginIsResolved() {
        WebConfig webConfig = new WebConfig(List.of("http://localhost:5173"));

        List<String> resolved = allowedOriginsFromFilter(webConfig.corsFilter());

        assertThat(resolved).containsExactly("http://localhost:5173");
    }

    /**
     * Extracts the allowed origins registered for the {@code /api/**} mapping
     * from the {@link CorsFilter}'s {@link CorsConfigurationSource}.
     *
     * <p>Spring 6's {@link UrlBasedCorsConfigurationSource} keys its internal
     * {@code corsConfigurations} map by parsed {@code PathPattern} objects (not
     * raw {@code String}s), so we read the single registered configuration by
     * value rather than by key to stay independent of the key type.
     */
    @SuppressWarnings("unchecked")
    private List<String> allowedOriginsFromFilter(CorsFilter filter) {
        Object source = ReflectionTestUtils.getField(filter, "configSource");
        assertThat(source).isInstanceOf(CorsConfigurationSource.class);

        Map<?, CorsConfiguration> configs =
                (Map<?, CorsConfiguration>) ReflectionTestUtils.getField(source, "corsConfigurations");
        assertThat(configs).hasSize(1);

        CorsConfiguration config = configs.values().iterator().next();
        return config.getAllowedOrigins();
    }
}
