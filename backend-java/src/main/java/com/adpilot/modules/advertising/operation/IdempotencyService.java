package com.adpilot.modules.advertising.operation;

import java.util.Optional;
import java.util.UUID;

import com.adpilot.modules.advertising.entity.OperationEntity;

/**
 * Owns the two distinct idempotency concerns of the Operation model and keeps them strictly
 * separate so that a retry is never blocked by a prior attempt's submission key while repeated
 * operator clicks are still coalesced (Req 5.2, 5.3, 5.7).
 *
 * <p>The key model (see the requirements glossary) has two layers:</p>
 * <ul>
 *   <li><strong>{@code logicalIdempotencyKey}</strong> — dedupes repeated operator <em>activations</em>
 *       (coalesces repeated clicks) for the SAME logical change. Two activations carrying the same
 *       {@code logicalIdempotencyKey} are the same logical Operation and MUST be coalesced rather
 *       than producing two logical Operations (Req 5.3).</li>
 *   <li><strong>{@code submissionIdempotencyKey}</strong> — a key unique to a single platform
 *       submission <em>attempt</em>, sent to the platform so it can dedupe that exact submission.
 *       Because every attempt carries its OWN {@code submissionIdempotencyKey}, a retry (a new key)
 *       is NEVER blocked or deduped by a prior attempt's key; only an exact re-delivery of the SAME
 *       submission is recognized as a duplicate (Req 5.2, 5.7).</li>
 * </ul>
 *
 * <p>This service exposes only the idempotency primitives. The {@code createOperation} pipeline
 * (task 6.1) uses {@link #findLogicalOperation(UUID, String)} to coalesce, the retry path (task 7.3)
 * uses {@link #newSubmissionIdempotencyKey()} to mint a fresh per-attempt submission key, and the
 * callback / poll handlers (tasks 10.2, 10.3, 10.10) use
 * {@link #findBySubmissionIdempotencyKey(String)} / {@link #isSubmissionProcessed(String)} to
 * recognize an already-processed submission and avoid producing a duplicate platform request.</p>
 *
 * <p>Validates: Requirements 5.2, 5.3, 5.7.</p>
 */
public interface IdempotencyService {

    /**
     * Coalesces repeated operator activations: finds the existing logical Operation for the given
     * {@code logicalIdempotencyKey} within the Store, if one already exists (Req 5.3).
     *
     * <p>When a result is present, the caller MUST treat the new activation as the SAME logical
     * Operation (it is a repeated click) and MUST NOT create a second logical Operation. When the
     * result is empty, the activation is new and the caller proceeds to create a fresh logical
     * Operation. The returned Operation is the most recent attempt recorded under that key, so its
     * {@code logicalOperationId} identifies the logical change to attach further attempts to.</p>
     *
     * @param storeId               the owning Store; coalescing is scoped per Store so that a key
     *                              collision across Stores never merges two Stores' Operations
     *                              (a {@code null} Store never coalesces)
     * @param logicalIdempotencyKey the click-coalescing key carried by the activation
     * @return the existing logical Operation (latest attempt) for the key, or empty when the
     *         activation is new
     */
    Optional<OperationEntity> findLogicalOperation(UUID storeId, String logicalIdempotencyKey);

    /**
     * Issues a fresh, globally-unique {@code submissionIdempotencyKey} for a NEW platform-submission
     * attempt (Req 5.2, 5.7).
     *
     * <p>Each attempt — including every retry of the same logical Operation — gets its OWN key, so a
     * retry is never blocked or deduped by a prior attempt's submission key. The returned value is a
     * fresh random identifier that has never been issued before.</p>
     *
     * @return a new, unique submission idempotency key
     */
    String newSubmissionIdempotencyKey();

    /**
     * Resolves the Operation attempt that carries the given {@code submissionIdempotencyKey}, if any.
     *
     * <p>Because each attempt owns a unique submission key, at most one Operation matches. The
     * callback / poll / re-delivery handlers use this to correlate an inbound platform result back to
     * the exact attempt that produced the submission and to process it idempotently (Req 5.7).</p>
     *
     * @param submissionIdempotencyKey the per-submission key delivered by a callback, poll, or
     *                                 re-delivery
     * @return the Operation attempt carrying the key, or empty when the key is unknown
     */
    Optional<OperationEntity> findBySubmissionIdempotencyKey(String submissionIdempotencyKey);

    /**
     * Reports whether the submission identified by {@code submissionIdempotencyKey} has already been
     * processed to a final platform result, so that a duplicate delivery (callback, poll result, or
     * re-delivery) carrying that key is recognized and produces no duplicate platform request
     * (Req 5.2, 5.7).
     *
     * <p>A submission is considered already processed when the Operation attempt carrying its key has
     * reached a settled platform Sync_State ({@code effective}, {@code failed}, or {@code cancelled}).
     * An unknown key, or a key whose attempt is still in flight, is NOT yet processed.</p>
     *
     * @param submissionIdempotencyKey the per-submission key delivered by a callback, poll, or
     *                                 re-delivery
     * @return {@code true} iff the submission has reached a settled platform result
     */
    boolean isSubmissionProcessed(String submissionIdempotencyKey);
}
