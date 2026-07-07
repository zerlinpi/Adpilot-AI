package com.adpilot.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Allowed CORS origins, resolved from the {@code adpilot.cors.allowed-origins}
     * property. Accepts a comma-separated list (e.g.
     * {@code http://localhost:5173,http://localhost:3000}) which Spring binds to
     * this {@link List}. Defaults to the local dev frontend origin when the
     * property is absent, preserving prior behavior.
     */
    private final List<String> allowedOrigins;

    public WebConfig(
            @Value("${adpilot.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * Serves the React single-page application and its static assets while
     * delegating API routes to the REST layer.
     *
     * <p>Maps every request ({@code /**}) to {@code classpath:/static/} and
     * installs a custom {@link PathResourceResolver} so the lookup follows SPA
     * fallback semantics:
     * <ol>
     *   <li>{@code api}/{@code api/**} resolve to {@code null}, so they fall
     *       through to REST controllers and the {@code /api/**} not-found
     *       handler (always JSON; index.html is never served for API routes).</li>
     *   <li>An existing, readable static asset (JS/CSS/images) is served
     *       directly.</li>
     *   <li>Anything else returns {@code index.html} so the client-side router
     *       can render deep links.</li>
     * </ol>
     *
     * <p>This is PathPattern-legal (no wildcard-with-suffix mapping), so it works
     * under Spring MVC's default matcher without the legacy AntPathMatcher. Note
     * that {@code classpath:/static/index.html} may be absent in this repo (the
     * SPA is built/deployed separately); returning a possibly non-existent
     * {@link ClassPathResource} is acceptable and must not break startup.
     */
    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    public Resource getResource(@NonNull String resourcePath,
                                                @NonNull Resource location) throws IOException {
                        // 1. API routes never resolve to a static resource.
                        if (resourcePath.equals("api") || resourcePath.startsWith("api/")) {
                            return null;
                        }
                        // 2. Serve real static assets when they exist.
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 3. SPA deep-link fallback.
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        String[] origins = allowedOrigins.toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
