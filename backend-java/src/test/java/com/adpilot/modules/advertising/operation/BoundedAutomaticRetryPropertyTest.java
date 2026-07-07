package com.adpilot.modules.advertising.operation;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test for the bounding of AUTOMATIC retries of a single logical Operation.
 *
 * <p>Feature: advertising-workspace-rework, Property 11: Automatic retries are bounded.
 *
 * <p>Validates: Requirements 4.5.
 *
 * <p><b>Property.</b> <em>For any</em> logical Operation, the number of automatic retry attempts
 * (tracked by {@code attemptNumber} under one {@code logicalOperationId}) never exceeds
 * {@value AutomaticRetryPolicy#MAX_AUTOMATIC_ATTEMPTS}; beyond that an explicit operator retry is
 * required.
 *
 * <p><b>Subject.</b> The bound is owned by the pure, side-effect-free
 * {@link AutomaticRetryPolicy}. The asynchronous {@code OutboxWorker} (task 10.1) is not yet
 * implemented; it will consume this policy unchanged to decide whether to schedule another automatic
 * attempt under the same {@code logicalOperationId}. This test exercises the real policy directly and
 * additionally drives the exact automatic-retry loop the worker will run — repeatedly failing and
 * retrying while the policy permits — to prove the produced attempt numbers stay within the cap and
 * that an operator retry is required exactly when (and only when) automatic retries are exhausted.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 11: Automatic retries are bounded")
class BoundedAutomaticRetryPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    /**
     * Feature: advertising-workspace-rework, Property 11: Automatic retries are bounded.
     *
     * <p>Validates: Requirements 4.5.
     *
     * <p>For any current 1-based {@code attemptNumber}, the automatic-retry decision is a clean,
     * mutually-exclusive partition: an automatic retry is permitted iff the attempt is below the cap,
     * an operator retry is required iff it is at or beyond the cap, and a permitted automatic retry
     * never produces an attempt number that exceeds the cap.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 11: automatic retry is permitted iff below the cap, and never produces an attempt beyond the cap")
    void automaticRetryDecisionIsBoundedAndComplementary(
            @ForAll @IntRange(min = 1, max = 200) int attemptNumber) {

        boolean canAuto = AutomaticRetryPolicy.canAutomaticallyRetry(attemptNumber);
        boolean needsOperator = AutomaticRetryPolicy.requiresOperatorRetry(attemptNumber);

        // The two decisions are exact complements for any valid 1-based attempt number: never both,
        // never neither (Req 4.5 — automatic OR operator retry, deterministically).
        assertThat(canAuto).isNotEqualTo(needsOperator);

        // Automatic retry is permitted EXACTLY while the latest attempt is below the cap.
        assertThat(canAuto)
                .isEqualTo(attemptNumber < AutomaticRetryPolicy.MAX_AUTOMATIC_ATTEMPTS);

        if (canAuto) {
            int next = AutomaticRetryPolicy.nextAutomaticAttemptNumber(attemptNumber);
            // A permitted automatic retry advances by exactly one and never crosses the cap (Req 4.5).
            assertThat(next).isEqualTo(attemptNumber + 1);
            assertThat(next).isLessThanOrEqualTo(AutomaticRetryPolicy.MAX_AUTOMATIC_ATTEMPTS);
        } else {
            // Once exhausted, the worker has no next automatic attempt to schedule; asking for one is
            // an error — an explicit operator retry is required instead (Req 4.5).
            assertThatThrownBy(() -> AutomaticRetryPolicy.nextAutomaticAttemptNumber(attemptNumber))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Feature: advertising-workspace-rework, Property 11: Automatic retries are bounded.
     *
     * <p>Validates: Requirements 4.5.
     *
     * <p>Drives the exact loop the {@code OutboxWorker} will run: starting from a fresh logical
     * Operation (attempt 1) and failing every submission, it keeps retrying automatically for as long
     * as {@link AutomaticRetryPolicy} permits. The generated {@code submissionFailures} count is large
     * and arbitrary to model an unbounded run of failures; the property is that no matter how many
     * failures occur, the automatic worker stops at the cap.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 11: an automatic worker that keeps failing never exceeds the cap and then hands off to operator retry")
    void automaticRetryLoopNeverExceedsTheCap(
            @ForAll @IntRange(min = 0, max = 1000) int submissionFailures) {

        // The full history of attempt numbers the worker would label, starting at the first attempt.
        List<Integer> attempts = new ArrayList<>();
        int attemptNumber = 1;
        attempts.add(attemptNumber);

        int automaticRetries = 0;
        // Each iteration models one failed submission followed by the worker's automatic-retry
        // decision. The loop stops either when the worker would stop retrying or when the modeled
        // failures run out — whichever comes first.
        for (int failure = 0; failure < submissionFailures; failure++) {
            if (!AutomaticRetryPolicy.canAutomaticallyRetry(attemptNumber)) {
                break; // automatic retries exhausted — operator retry now required.
            }
            attemptNumber = AutomaticRetryPolicy.nextAutomaticAttemptNumber(attemptNumber);
            attempts.add(attemptNumber);
            automaticRetries++;
        }

        // --- Invariant 1: no attempt number the automatic worker ever produces exceeds the cap. ---
        assertThat(attempts).allSatisfy(n ->
                assertThat(n).isBetween(1, AutomaticRetryPolicy.MAX_AUTOMATIC_ATTEMPTS));

        // --- Invariant 2: the automatic worker makes at most (cap - 1) automatic retries (the
        //     original attempt is attempt 1, so 2 automatic retries reach the cap of 3). ---
        assertThat(automaticRetries)
                .isLessThanOrEqualTo(AutomaticRetryPolicy.MAX_AUTOMATIC_ATTEMPTS - 1);

        // --- Invariant 3: attempt numbers are strictly increasing and contiguous from 1 (each retry
        //     advances the same logicalOperationId's attempt by exactly one). ---
        for (int i = 0; i < attempts.size(); i++) {
            assertThat(attempts.get(i)).isEqualTo(i + 1);
        }

        // --- Invariant 4: when there were enough failures to exhaust automatic retries, the worker
        //     stopped exactly at the cap and an explicit operator retry is now required. With fewer
        //     failures than the cap allows, the latest attempt is still below the cap and automatic
        //     retry remains available (no premature hand-off). ---
        int latest = attempts.get(attempts.size() - 1);
        if (submissionFailures >= AutomaticRetryPolicy.MAX_AUTOMATIC_ATTEMPTS - 1) {
            assertThat(latest).isEqualTo(AutomaticRetryPolicy.MAX_AUTOMATIC_ATTEMPTS);
            assertThat(AutomaticRetryPolicy.requiresOperatorRetry(latest)).isTrue();
            assertThat(AutomaticRetryPolicy.canAutomaticallyRetry(latest)).isFalse();
        } else {
            assertThat(latest).isLessThan(AutomaticRetryPolicy.MAX_AUTOMATIC_ATTEMPTS);
            assertThat(AutomaticRetryPolicy.canAutomaticallyRetry(latest)).isTrue();
        }
    }
}
