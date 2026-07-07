package com.adpilot.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
public class JwtUtil {

    /**
     * The well-known development signing secret shipped as the property default.
     * It is fine for local/dev/test but must never sign tokens in production: a
     * committed, guessable key lets anyone forge valid JWTs. The prod guard below
     * refuses to start if this value (or a blank value) is in effect under the
     * {@code prod} profile.
     */
    static final String INSECURE_DEV_DEFAULT_SECRET =
            "adpilot-dev-only-jwt-secret-key-change-before-production";

    @Value("${adpilot.jwt.secret:adpilot-dev-only-jwt-secret-key-change-before-production}")
    private String secret;

    @Value("${adpilot.jwt.expiration:7200000}")
    private long expiration; // Default 2 hours in ms

    private final Environment environment;

    public JwtUtil(Environment environment) {
        this.environment = environment;
    }

    /**
     * Fail fast at startup when a production deployment would run with an insecure
     * JWT signing key (mirrors {@link com.adpilot.common.utils.CryptoUtil}'s
     * fail-fast on a missing crypto secret). Only the {@code prod} profile is
     * guarded, so dev/test — which legitimately use the built-in default — start
     * unchanged.
     *
     * <p>A prod deployment that forgot to set {@code JWT_SECRET}, or that inherited
     * the committed dev default, is a critical security hole (forgeable tokens);
     * we refuse to boot rather than silently accept it.
     */
    @PostConstruct
    void validateSecret() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!prod) {
            return;
        }
        if (secret == null || secret.isBlank() || INSECURE_DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "adpilot.jwt.secret is not configured for the 'prod' profile (it is blank or "
                            + "still the insecure development default). Set the JWT_SECRET environment "
                            + "variable (or adpilot.jwt.secret property) to a strong, unique secret "
                            + "before starting the application in production.");
        }
        // HS256 requires a key of at least 256 bits (32 bytes). A shorter secret
        // passes the "not blank / not dev-default" checks above but would throw
        // io.jsonwebtoken.security.WeakKeyException at token-generation time —
        // surfacing as a confusing HTTP 500 on the very first /api/auth/login.
        // Fail fast at startup instead, with an actionable message.
        int secretBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < 32) {
            throw new IllegalStateException(
                    "adpilot.jwt.secret is too short for HS256: it must be at least 32 bytes (256 bits) "
                            + "but is only " + secretBytes + " bytes. Set JWT_SECRET to a longer value "
                            + "(a 32+ character random string) before starting in production.");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String userId, String email, List<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(email)
                .claim("userId", userId)
                .claim("email", email)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public String getUserIdFromToken(String token) {
        Claims claims = getClaims(token);
        return claims.get("userId", String.class);
    }

    public String getEmailFromToken(String token) {
        Claims claims = getClaims(token);
        return claims.get("email", String.class);
    }

    @SuppressWarnings("unchecked")
    public Set<String> getRolesFromToken(String token) {
        Claims claims = getClaims(token);
        List<String> roles = claims.get("roles", List.class);
        return roles != null ? Set.copyOf(roles) : Set.of();
    }

    /** The unique JWT id (jti) used for single-token revocation on logout. */
    public String getJtiFromToken(String token) {
        return getClaims(token).getId();
    }

    /** When the token was issued; used to reject tokens predating a session-epoch reset. */
    public Instant getIssuedAtFromToken(String token) {
        Date issued = getClaims(token).getIssuedAt();
        return issued != null ? issued.toInstant() : null;
    }

    /** When the token expires; used to size the logout denylist TTL. */
    public Instant getExpirationFromToken(String token) {
        Date exp = getClaims(token).getExpiration();
        return exp != null ? exp.toInstant() : null;
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
