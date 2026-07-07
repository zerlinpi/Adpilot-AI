package com.adpilot.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserContextService userContextService;
    private final SessionControlService sessionControlService;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/health"
    );

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            String path = request.getRequestURI();

            // Skip JWT processing for public endpoints
            if (isPublicPath(path)) {
                filterChain.doFilter(request, response);
                return;
            }

            String token = extractToken(request);

            if (StringUtils.hasText(token) && jwtUtil.validateToken(token)
                    && !sessionControlService.isTokenRevoked(token)) {
                try {
                    String userId = jwtUtil.getUserIdFromToken(token);
                    String email = jwtUtil.getEmailFromToken(token);
                    Set<String> roles = jwtUtil.getRolesFromToken(token);

                    // Populate the principal per request: load effective permissions,
                    // orgId, and departmentId (permission set is Redis-cached). Fall back
                    // to the thin token-derived principal if the user cannot be resolved.
                    CurrentUser currentUser = userContextService.load(userId);
                    if (currentUser == null) {
                        currentUser = CurrentUser.builder()
                                .userId(userId)
                                .email(email)
                                .roles(roles)
                                .build();
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    currentUser, null, currentUser.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (Exception e) {
                    log.error("Cannot set user authentication: {}", e.getMessage());
                }
            }

            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            // Any exception thrown from within the filter (including the downstream
            // chain) must surface as a JSON error envelope rather than Spring Boot's
            // Whitelabel HTML page. This mirrors the JSON shape produced by the
            // SecurityConfig authentication entry point and access-denied handler.
            writeJsonError(response, ex);
        }
    }

    /**
     * Serializes a JSON error envelope ({@code {success:false, error:{code,message}}})
     * with HTTP 500 when an unhandled exception escapes the filter chain. If the
     * response has already been committed there is nothing safe to write, so the
     * exception is only logged.
     */
    private void writeJsonError(HttpServletResponse response, Exception ex) throws IOException {
        log.error("Unhandled exception in JwtAuthFilter: {}", ex.getMessage(), ex);
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        objectMapper.writeValue(response.getOutputStream(),
                Map.of(
                        "success", false,
                        "error", Map.of(
                                "code", "500",
                                "message", "Internal server error"
                        )
                ));
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream()
                .anyMatch(pattern -> pathMatcher.match(Objects.requireNonNull(pattern),
                        Objects.requireNonNull(path)));
    }
}
