package com.adpilot.modules.scheduler.service.impl;

import com.adpilot.modules.scheduler.service.CronEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example/edge-case unit tests for {@link CronEvaluatorImpl}.
 *
 * <p>Covers Requirements 4.1.4 (well-formedness validation), 4.1.2/4.1.5
 * (next run strictly after the base time). The universal property is exercised
 * separately by the property-based test (task 13.2).</p>
 */
class CronEvaluatorImplTest {

    private final CronEvaluator evaluator = new CronEvaluatorImpl();

    @Test
    void validRejectsNullAndBlank() {
        assertFalse(evaluator.isValid(null));
        assertFalse(evaluator.isValid(""));
        assertFalse(evaluator.isValid("   "));
    }

    @Test
    void validRejectsMalformedExpressions() {
        assertFalse(evaluator.isValid("not a cron"));
        assertFalse(evaluator.isValid("* * *"));               // too few fields
        assertFalse(evaluator.isValid("99 * * * * *"));        // seconds out of range
        assertFalse(evaluator.isValid("0 0 0 32 * *"));        // day-of-month out of range
    }

    @Test
    void validAcceptsWellFormedExpressions() {
        assertTrue(evaluator.isValid("0 0 * * * *"));          // top of every hour
        assertTrue(evaluator.isValid("0 0 0 * * *"));          // midnight daily
        assertTrue(evaluator.isValid("0 */15 * * * *"));       // every 15 minutes
        assertTrue(evaluator.isValid("@daily"));               // macro
        assertTrue(evaluator.isValid("  0 0 12 * * MON  "));   // trimmed, day-of-week
    }

    @Test
    void nextRunIsStrictlyAfterBaseTime() {
        Instant from = ZonedDateTime.of(2024, 1, 1, 10, 30, 0, 0, ZoneOffset.UTC).toInstant();
        Instant next = evaluator.nextRunAfter("0 0 * * * *", from);
        assertNotNull(next);
        assertTrue(next.isAfter(from), "next run must be strictly after the base time");
        // Next top-of-hour after 10:30 UTC is 11:00 UTC.
        assertEquals(ZonedDateTime.of(2024, 1, 1, 11, 0, 0, 0, ZoneOffset.UTC).toInstant(), next);
    }

    @Test
    void nextRunIsStrictlyAfterEvenWhenBaseExactlyMatches() {
        // Base time exactly on a fire time (11:00:00) must yield the *following* fire time.
        Instant onTheHour = ZonedDateTime.of(2024, 1, 1, 11, 0, 0, 0, ZoneOffset.UTC).toInstant();
        Instant next = evaluator.nextRunAfter("0 0 * * * *", onTheHour);
        assertNotNull(next);
        assertTrue(next.isAfter(onTheHour));
        assertEquals(ZonedDateTime.of(2024, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC).toInstant(), next);
    }

    @Test
    void nextRunAfterThrowsOnInvalidExpression() {
        Instant from = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> evaluator.nextRunAfter("bogus", from));
        assertThrows(IllegalArgumentException.class, () -> evaluator.nextRunAfter(null, from));
    }

    @Test
    void nextRunAfterThrowsOnNullBaseTime() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.nextRunAfter("0 0 * * * *", null));
    }
}
