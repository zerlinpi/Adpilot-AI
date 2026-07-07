package com.adpilot.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RetryBackoff} (reliability fix L2).
 *
 * <p>Asserts the base/exponent are unchanged and that the jittered value always
 * stays within {@code [capped, capped + jitterFraction * capped]}, capped at the
 * configured maximum.</p>
 */
@DisplayName("RetryBackoff")
class RetryBackoffTest {

    private static final long BASE_MS = 2000L;

    @Test
    @DisplayName("exponential() keeps the original base * 2^(attempt-1) computation")
    void exponentialUnchanged() {
        assertThat(RetryBackoff.exponential(BASE_MS, 1)).isEqualTo(2000L);
        assertThat(RetryBackoff.exponential(BASE_MS, 2)).isEqualTo(4000L);
        assertThat(RetryBackoff.exponential(BASE_MS, 3)).isEqualTo(8000L);
    }

    @Test
    @DisplayName("withJitter() stays within [base, base + jitter] across many draws")
    void jitterWithinBounds() {
        double fraction = 0.5;
        long max = 60_000L;
        for (int attempt = 1; attempt <= 4; attempt++) {
            long base = RetryBackoff.exponential(BASE_MS, attempt);
            long upper = base + (long) (base * fraction);
            for (int i = 0; i < 1000; i++) {
                long value = RetryBackoff.withJitter(BASE_MS, attempt, fraction, max);
                assertThat(value)
                        .as("attempt=%d value must be within [%d, %d]", attempt, base, upper)
                        .isBetween(base, upper);
            }
        }
    }

    @Test
    @DisplayName("withJitter() caps the exponential term at maxBackoffMs before jitter")
    void jitterRespectsCap() {
        double fraction = 0.5;
        long max = 5_000L;
        // attempt 10 would be 2000 * 2^9 = ~1s well over the cap; capped to 5000.
        long upper = max + (long) (max * fraction);
        for (int i = 0; i < 1000; i++) {
            long value = RetryBackoff.withJitter(BASE_MS, 10, fraction, max);
            assertThat(value).isBetween(max, upper);
        }
    }

    @Test
    @DisplayName("withJitter() with zero fraction returns exactly the capped backoff")
    void zeroJitterFraction() {
        long value = RetryBackoff.withJitter(BASE_MS, 3, 0.0, 60_000L);
        assertThat(value).isEqualTo(8000L);
    }
}
