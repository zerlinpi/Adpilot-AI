package com.adpilot.modules.advertising.operation;

/**
 * The pure, side-effect-free contract that bounds AUTOMATIC retries of a single logical Operation
 * (Req 4.5).
 *
 * <p>A {@code platform_mutation} Operation that fails to submit is re-attempted automatically by the
 * {@code OutboxWorker} (task 10.1). Each attempt of one logical change shares the same
 * {@code logicalOperationId} and carries a 1-based {@code attemptNumber} (the first attempt is 1, the
 * first automatic retry is 2, and so on — see the requirements glossary). Requirement 4.5 caps the
 * number of automatic attempts of a single logical Operation at {@link #MAX_AUTOMATIC_ATTEMPTS}
 * (3): once that many attempts have been made, the worker MUST stop retrying automatically and an
 * explicit operator retry (the {@code OperationService.retry} path) is required to make any further
 * attempt.</p>
 *
 * <p>This class is the single authority for that bound. It is deliberately a pure decision function
 * over the current {@code attemptNumber} so it can be (a) consumed unchanged by the asynchronous
 * {@code OutboxWorker} when it decides whether to schedule another automatic attempt, and (b)
 * verified exhaustively by a property-based test without any worker, database, or platform wiring.
 * The worker increments {@code attemptNumber} to {@code attemptNumber + 1} for each automatic retry,
 * and because an automatic retry is permitted only while {@link #canAutomaticallyRetry(int)} holds,
 * the resulting attempt number can never exceed {@link #MAX_AUTOMATIC_ATTEMPTS}.</p>
 *
 * <p>Validates: Requirements 4.5.</p>
 */
public final class AutomaticRetryPolicy {

    /**
     * The maximum number of automatic attempts permitted for a single logical Operation, counting the
     * original submission as attempt 1 (Req 4.5). With this bound the attempt numbers an automatic
     * worker may ever produce are exactly {@code {1, 2, 3}}; reaching {@code 3} exhausts automatic
     * retries.
     */
    public static final int MAX_AUTOMATIC_ATTEMPTS = 3;

    private AutomaticRetryPolicy() {
        // Pure utility — no instances.
    }

    /**
     * Decide whether a logical Operation whose latest attempt is {@code attemptNumber} may be retried
     * AUTOMATICALLY by the worker.
     *
     * <p>An automatic retry produces a new attempt numbered {@code attemptNumber + 1}; it is permitted
     * if and only if {@code attemptNumber} is a valid 1-based attempt strictly below
     * {@link #MAX_AUTOMATIC_ATTEMPTS}, so the new attempt's number never exceeds the cap. At or beyond
     * the cap no further automatic retry is allowed (see {@link #requiresOperatorRetry(int)}).</p>
     *
     * @param attemptNumber the 1-based ordinal of the latest attempt of the logical Operation
     * @return {@code true} iff a further AUTOMATIC retry is permitted
     */
    public static boolean canAutomaticallyRetry(int attemptNumber) {
        return attemptNumber >= 1 && attemptNumber < MAX_AUTOMATIC_ATTEMPTS;
    }

    /**
     * Decide whether automatic retries have been exhausted for a logical Operation whose latest
     * attempt is {@code attemptNumber}, so that any further attempt requires an EXPLICIT operator
     * retry (Req 4.5).
     *
     * <p>For any valid 1-based {@code attemptNumber} this is the exact complement of
     * {@link #canAutomaticallyRetry(int)}: the operation is either still automatically retryable or it
     * requires an operator retry, never both and never neither.</p>
     *
     * @param attemptNumber the 1-based ordinal of the latest attempt of the logical Operation
     * @return {@code true} iff automatic retries are exhausted and an operator retry is required
     */
    public static boolean requiresOperatorRetry(int attemptNumber) {
        return attemptNumber >= MAX_AUTOMATIC_ATTEMPTS;
    }

    /**
     * The attempt number a single automatic retry would produce from {@code attemptNumber}.
     *
     * <p>This must only be called when {@link #canAutomaticallyRetry(int)} holds; the worker uses it
     * to label the next automatic attempt under the same {@code logicalOperationId}.</p>
     *
     * @param attemptNumber the 1-based ordinal of the latest (failed) attempt
     * @return the 1-based ordinal of the next automatic attempt ({@code attemptNumber + 1})
     * @throws IllegalStateException if automatic retries are already exhausted at {@code attemptNumber}
     */
    public static int nextAutomaticAttemptNumber(int attemptNumber) {
        if (!canAutomaticallyRetry(attemptNumber)) {
            throw new IllegalStateException(
                    "Automatic retries are exhausted at attemptNumber=" + attemptNumber
                            + " (max=" + MAX_AUTOMATIC_ATTEMPTS + "); an explicit operator retry is required");
        }
        return attemptNumber + 1;
    }
}
