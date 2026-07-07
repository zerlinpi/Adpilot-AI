package com.adpilot.common.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CircuitBreaker} (reliability fix H3).
 *
 * <p>Covers the core state machine and the two safety guarantees the breaker must uphold:
 * fail-open on internal error and full bypass when disabled.</p>
 */
@DisplayName("CircuitBreaker unit tests")
class CircuitBreakerTest {

    private static final String KEY = "amazon_ads:status:conn-1";

    @Test
    @DisplayName("Opens after threshold consecutive failures and blocks further calls")
    void opensAfterThresholdAndBlocks() {
        // threshold=3, long cooldown so it stays OPEN for the test
        CircuitBreaker cb = new CircuitBreaker(true, 3, 3600);

        assertThat(cb.allow(KEY)).isTrue(); // CLOSED initially

        cb.recordFailure(KEY);
        cb.recordFailure(KEY);
        // Still below threshold — remains CLOSED and allows.
        assertThat(cb.allow(KEY)).isTrue();
        assertThat(cb.stateName(KEY)).isEqualTo("CLOSED");

        cb.recordFailure(KEY); // third consecutive failure trips it OPEN
        assertThat(cb.stateName(KEY)).isEqualTo("OPEN");
        assertThat(cb.allow(KEY)).isFalse(); // OPEN within cooldown blocks
    }

    @Test
    @DisplayName("A success before the threshold resets the consecutive-failure count")
    void successResetsFailureCount() {
        CircuitBreaker cb = new CircuitBreaker(true, 3, 3600);

        cb.recordFailure(KEY);
        cb.recordFailure(KEY);
        cb.recordSuccess(KEY); // resets
        cb.recordFailure(KEY);
        cb.recordFailure(KEY);

        // Only two consecutive failures since the reset — still CLOSED.
        assertThat(cb.stateName(KEY)).isEqualTo("CLOSED");
        assertThat(cb.allow(KEY)).isTrue();
    }

    @Test
    @DisplayName("Half-opens after cooldown (permits one probe) then closes on probe success")
    void halfOpensAfterCooldownAndClosesOnSuccess() {
        // cooldown=0 so the OPEN window elapses immediately and a probe is permitted.
        CircuitBreaker cb = new CircuitBreaker(true, 1, 0);

        cb.recordFailure(KEY); // threshold=1 -> OPEN immediately
        assertThat(cb.stateName(KEY)).isEqualTo("OPEN");

        // Cooldown (0s) has elapsed: the next allow() half-opens and permits ONE probe.
        assertThat(cb.allow(KEY)).isTrue();
        assertThat(cb.stateName(KEY)).isEqualTo("HALF_OPEN");
        // While a probe is in flight, other calls are held back.
        assertThat(cb.allow(KEY)).isFalse();

        // Probe succeeds -> breaker closes and normal flow resumes.
        cb.recordSuccess(KEY);
        assertThat(cb.stateName(KEY)).isEqualTo("CLOSED");
        assertThat(cb.allow(KEY)).isTrue();
    }

    @Test
    @DisplayName("Half-open probe failure re-opens the breaker for another cooldown window")
    void halfOpenProbeFailureReopens() {
        CircuitBreaker cb = new CircuitBreaker(true, 1, 0);

        cb.recordFailure(KEY);            // OPEN
        assertThat(cb.allow(KEY)).isTrue(); // -> HALF_OPEN (probe permitted)
        cb.recordFailure(KEY);            // probe fails -> OPEN again
        assertThat(cb.stateName(KEY)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("Per-key isolation: opening one key never affects another key")
    void perKeyIsolation() {
        CircuitBreaker cb = new CircuitBreaker(true, 2, 3600);
        String other = "feishu:integration-9";

        cb.recordFailure(KEY);
        cb.recordFailure(KEY); // KEY -> OPEN

        assertThat(cb.allow(KEY)).isFalse();
        assertThat(cb.allow(other)).isTrue(); // healthy key unaffected
    }

    @Test
    @DisplayName("Disabled breaker bypasses everything: allow() always true, record*() are no-ops")
    void disabledBypasses() {
        CircuitBreaker cb = new CircuitBreaker(false, 1, 3600);

        // Even after many failures, a disabled breaker never blocks.
        for (int i = 0; i < 10; i++) {
            cb.recordFailure(KEY);
        }
        assertThat(cb.allow(KEY)).isTrue();
        // record*() were no-ops, so the key never left CLOSED.
        assertThat(cb.stateName(KEY)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("Fail-open: an internal error while evaluating allow() returns true")
    void failsOpenOnInternalError() {
        CircuitBreaker cb = new CircuitBreaker(true, 1, 3600) {
            @Override
            boolean evaluateAllow(String key) {
                throw new IllegalStateException("boom");
            }
        };
        // Despite the internal error, the call is permitted (never blocks a healthy dependency).
        assertThat(cb.allow(KEY)).isTrue();
    }

    @Test
    @DisplayName("Null key is always allowed and record*() tolerate null")
    void nullKeyIsSafe() {
        CircuitBreaker cb = new CircuitBreaker(true, 1, 3600);
        assertThat(cb.allow(null)).isTrue();
        cb.recordFailure(null); // no throw
        cb.recordSuccess(null); // no throw
        assertThat(cb.allow(null)).isTrue();
    }
}
