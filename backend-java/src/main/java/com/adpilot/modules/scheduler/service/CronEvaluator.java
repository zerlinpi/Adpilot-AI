package com.adpilot.modules.scheduler.service;

import java.time.Instant;

/**
 * Evaluates recurrence (cron) expressions for the scheduling engine.
 *
 * <p>Backs Requirement 4.1.4 (validate that a recurrence expression is well-formed
 * before saving), Requirement 4.1.2 (compute the next run time when a job executes),
 * and Requirement 4.1.5 (always compute a next run strictly after the base time so a
 * failed or completed execution never blocks subsequent runs).</p>
 */
public interface CronEvaluator {

    /**
     * Tests whether the given recurrence expression is well-formed.
     *
     * @param expression the recurrence expression to validate; may be {@code null}
     * @return {@code true} if and only if the expression is non-blank and well-formed
     */
    boolean isValid(String expression);

    /**
     * Computes the next run time strictly after the supplied base time.
     *
     * @param expression a well-formed recurrence expression
     * @param from       the base time; the result is guaranteed to be strictly after this instant
     * @return the next run time strictly after {@code from}, or {@code null} if the
     *         expression will never fire again after {@code from}
     * @throws IllegalArgumentException if the expression is not well-formed or {@code from} is null
     */
    Instant nextRunAfter(String expression, Instant from);
}
