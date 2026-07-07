package com.adpilot.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast guard tests for {@link JwtUtil} (audit L2).
 *
 * <p>A production deployment must never sign tokens with the committed, guessable
 * development default (or a blank secret) — that lets anyone forge valid JWTs.
 * Under the {@code prod} profile {@link JwtUtil#validateSecret()} refuses to start
 * in that case; dev/test keep working with the built-in default, and prod with a
 * strong secret works normally. This mirrors {@code CryptoUtil}'s fail-fast.
 */
class JwtUtilSecretGuardTest {

    /** A strong, non-default secret (>= 256 bits for HS256). */
    private static final String STRONG_SECRET =
            "a-very-strong-production-jwt-secret-value-01234567890";
    private static final long EXPIRATION_MS = 7_200_000L;

    private JwtUtil newJwtUtil(String[] activeProfiles, String secret) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        JwtUtil jwtUtil = new JwtUtil(env);
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        ReflectionTestUtils.setField(jwtUtil, "expiration", EXPIRATION_MS);
        return jwtUtil;
    }

    // L2: prod + insecure dev-default secret -> refuse to start.
    @Test
    void prodProfileWithDevDefaultSecret_failsFast() {
        JwtUtil jwtUtil = newJwtUtil(new String[]{"prod"}, JwtUtil.INSECURE_DEV_DEFAULT_SECRET);

        assertThatThrownBy(jwtUtil::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adpilot.jwt.secret");
    }

    // L2: prod + blank secret -> refuse to start.
    @Test
    void prodProfileWithBlankSecret_failsFast() {
        JwtUtil jwtUtil = newJwtUtil(new String[]{"prod"}, "   ");

        assertThatThrownBy(jwtUtil::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }

    // L2: dev profile keeps the built-in default working (startup + token round-trip).
    @Test
    void devProfileWithDevDefaultSecret_startsAndWorks() {
        JwtUtil jwtUtil = newJwtUtil(new String[]{"dev"}, JwtUtil.INSECURE_DEV_DEFAULT_SECRET);

        assertThatCode(jwtUtil::validateSecret).doesNotThrowAnyException();

        String token = jwtUtil.generateToken("user-1", "user@example.com", List.of("operator"));
        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo("user-1");
    }

    // L2: prod + a strong secret starts and signs/validates tokens normally.
    @Test
    void prodProfileWithStrongSecret_startsAndWorks() {
        JwtUtil jwtUtil = newJwtUtil(new String[]{"prod"}, STRONG_SECRET);

        assertThatCode(jwtUtil::validateSecret).doesNotThrowAnyException();

        String token = jwtUtil.generateToken("user-2", "prod@example.com", List.of("admin"));
        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.getEmailFromToken(token)).isEqualTo("prod@example.com");
    }
}
