package com.adpilot.modules.apisync.connector;

import java.util.List;

import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformCancelResult;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformStatusResult;
import com.adpilot.modules.apisync.model.PlatformVerifyResult;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.apisync.model.SubmissionMetadata;

/**
 * Service Provider Interface (SPI) for <em>writing</em> a change back to an
 * external platform. This is the write-side counterpart to the pull-only
 * {@link PlatformDataConnector}: where that SPI retrieves orders/products/etc.,
 * this one submits an approved change (a bid update, a negative keyword, a
 * listing edit) to the live platform through the store's
 * {@link com.adpilot.modules.apisync.entity.PlatformConnectionEntity} (Req 13.1.1,
 * 13.2.x).
 *
 * <p>One implementation exists per platform that supports write-back. The
 * {@code WriteBackService} (Req 13.1) and {@code AutomationRunner} (Req 13.2)
 * resolve the connector for a connection's platform and call
 * {@link #submit(ConnectionContext, PlatformChange)}.
 *
 * <h2>Status, cancellation, and status mapping (Req 55 extension)</h2>
 * <p>Beyond {@code submit}, this contract is extended (as an agreed extension to
 * the {@code core-platform-completion} connector contract, not a fork) with
 * {@link #queryStatus} (idempotent, non-mutating status read used for polling
 * and reconciliation), {@link #requestCancel} together with
 * {@link #supportsCancel}, and {@link #mapPlatformStatus} (platform status to a
 * {@link com.adpilot.modules.advertising.operation.SyncState}). These are
 * declared as default methods so connectors that only implement {@code submit}
 * remain compatible; a platform overrides them where its API supports the
 * capability. The platform reference returned from {@code submit} (Req 55.7)
 * correlates {@code queryStatus}, {@code requestCancel}, and inbound callbacks
 * back to the originating Operation.</p>
 *
 * <h2>Credentials &amp; security</h2>
 * <p>Credentials are supplied via {@link ConnectionContext#credentials()},
 * decrypted on demand via {@code CryptoUtil} by the caller that builds the
 * context. Implementations MUST NOT log credential values.
 *
 * <h2>Rejection contract (Req 13.1.3 / 13.2.7)</h2>
 * <p>When the platform rejects a change, the implementation SHOULD return a
 * {@link PlatformWriteResult#rejected(String) rejected} result carrying the
 * platform's failure reason rather than throwing, so callers can record the
 * reason and leave the internal record unchanged. Exceptions are reserved for
 * transport/credential failures and are treated by callers as a rejection with
 * the exception message as the reason.
 *
 * <h2>Integration point</h2>
 * <p>No concrete per-platform write connectors are registered yet; each platform
 * (Amazon Ads, etc.) registers a Spring bean implementing this interface to make
 * write-back live for that platform. Until a platform's connector is registered,
 * {@code WriteBackService} reports that write-back is unavailable for that
 * platform and leaves the internal record unchanged.
 */
public interface PlatformWriteConnector {

    /**
     * The platform key this connector serves, matching the values in
     * {@link PlatformConnector#SUPPORTED} (e.g. {@code "amazon_ads"},
     * {@code "shopify"}).
     */
    String platform();

    /**
     * Submit a single change to the live platform.
     *
     * <p>An accepted {@link PlatformWriteResult} carries the platform's own
     * reference/identifier for the change
     * ({@link PlatformWriteResult#platformReference()}). The caller stores that
     * reference on the {@code platform_mutation} Operation (Req 55.7) and uses it
     * to correlate later {@link #queryStatus} results, {@link #requestCancel}
     * requests, and inbound platform callbacks back to the originating Operation.
     *
     * @param ctx    decrypted connection context (never logged)
     * @param change the normalized change to apply on the platform
     * @return the platform's response: accepted (with an optional reference) or
     *         rejected (with the failure reason)
     */
    PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change);

    // -------------------------------------------------------------------------
    // Req 55 extension — status query, cancellation, and status mapping.
    //
    // These are an agreed EXTENSION to the core-platform-completion connector
    // contract (Req 55.1). They are declared as DEFAULT methods so existing
    // connectors that only implement submit() stay reconciled and compile
    // unchanged; a platform whose API supports these capabilities overrides them.
    // -------------------------------------------------------------------------

    /**
     * Query the platform for the current status of a previously submitted change,
     * correlated by the {@code platformReference} returned from {@link #submit}
     * (Req 55.7). Usable for both polling and reconciliation per Requirement 4
     * (Req 55.2).
     *
     * <p>This operation MUST be idempotent and non-mutating (Req 55.6): repeated
     * calls for the same reference return the same result for an unchanged
     * platform state and never change platform state. Resolve the returned raw
     * {@link PlatformStatusResult#platformStatus()} to a single
     * {@link SyncState} via {@link #mapPlatformStatus(String)}.
     *
     * <p>The default implementation reports the reference as not found, suitable
     * for a connector whose platform does not expose a status query.
     *
     * @param ctx               decrypted connection context (never logged)
     * @param platformReference the platform reference returned at submission
     * @return the platform's current status for the reference
     */
    default PlatformStatusResult queryStatus(ConnectionContext ctx, String platformReference) {
        return PlatformStatusResult.notFound(platformReference);
    }

    /**
     * Request cancellation of an already-submitted or in-flight change,
     * correlated by its {@code platformReference} (Req 55.3, 55.7). The returned
     * {@link PlatformCancelResult} tells the operation state machine how to
     * resolve the {@code cancel_requested} path of Requirement 4 (to
     * {@code cancelled}, {@code effective}, or {@code reconciliation_required}),
     * and a failed cancellation is never resolved to {@code failed} (Req 4.9).
     *
     * <p>The default implementation reports cancellation as unsupported, matching
     * the default {@link #supportsCancel()} value of {@code false}.
     *
     * @param ctx               decrypted connection context (never logged)
     * @param platformReference the platform reference returned at submission
     * @return the cancellation outcome the platform reported
     */
    default PlatformCancelResult requestCancel(ConnectionContext ctx, String platformReference) {
        return PlatformCancelResult.unsupported(platformReference);
    }

    /**
     * Whether the platform supports cancellation of an already-submitted or
     * in-flight change, so the {@code cancel_requested} path of Requirement 4 can
     * branch on cancel support (Req 55.3).
     *
     * @return {@code true} if {@link #requestCancel} is meaningful for this
     *         platform; {@code false} by default
     */
    default boolean supportsCancel() {
        return false;
    }

    /**
     * Map a platform-reported raw status string to exactly one {@link SyncState},
     * so platform statuses, callbacks, and {@link #queryStatus} results all
     * resolve to a single defined Sync_State (Req 55.4).
     *
     * <p>The default mapping recognizes common, case-insensitive normalized
     * statuses and conservatively maps an unknown or {@code null} status to
     * {@link SyncState#RECONCILIATION_REQUIRED}, so an unrecognized platform
     * status forces reconciliation rather than being silently treated as a
     * terminal success or failure. A platform with richer statuses overrides this.
     *
     * @param platformStatus the platform's raw status string (may be {@code null})
     * @return the single Sync_State the status maps to
     */
    default SyncState mapPlatformStatus(String platformStatus) {
        if (platformStatus == null || platformStatus.isBlank()) {
            return SyncState.RECONCILIATION_REQUIRED;
        }
        return switch (platformStatus.trim().toUpperCase()) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "COMPLETE", "APPLIED", "EFFECTIVE", "ENABLED", "ACTIVE" ->
                    SyncState.EFFECTIVE;
            case "PENDING", "QUEUED", "SUBMITTED", "ACCEPTED" ->
                    SyncState.SUBMITTED;
            case "IN_PROGRESS", "INPROGRESS", "PROCESSING", "RUNNING" ->
                    SyncState.AMAZON_PROCESSING;
            case "FAILED", "FAILURE", "ERROR", "REJECTED", "INVALID" ->
                    SyncState.FAILED;
            case "CANCELLED", "CANCELED", "ABORTED" ->
                    SyncState.CANCELLED;
            default -> SyncState.RECONCILIATION_REQUIRED;
        };
    }

    // -------------------------------------------------------------------------
    // Req 16.6 — Read-after-write verification SPI extension.
    //
    // The existing queryStatus(ctx, platformReference) cannot verify a value match
    // because it has no entity type, field, or expected value. This method allows a
    // connector to re-read the changed entity field and return the live value for
    // comparison by the VerificationWorker.
    // -------------------------------------------------------------------------

    /**
     * Verify a previously submitted change by re-reading the entity's current field
     * value from the platform and returning it as a structured result (Req 16.6).
     *
     * <p>The VerificationWorker invokes this after a configurable delay following a
     * successful submission. The business layer compares
     * {@link PlatformVerifyResult#liveValue()} against the Operation's
     * {@code after_value}: a match → {@code effective}, a mismatch →
     * {@code reconciliation_required}, a read failure → retry/timeout handling
     * (Req 1.13, 1.14, 20.2, 20.3).
     *
     * <p>The default implementation returns {@link PlatformVerifyResult#unsupported()}
     * so existing connectors are unaffected.
     *
     * @param ctx    decrypted connection context (never logged)
     * @param change the original change that was submitted
     * @param meta   metadata persisted at acceptance time (Amazon request ID, external
     *              entity ID, entity type, field, expected value)
     * @return the verification result with the live field value, or unsupported/failed
     */
    default PlatformVerifyResult verify(ConnectionContext ctx, PlatformChange change,
                                        SubmissionMetadata meta) {
        return PlatformVerifyResult.unsupported();
    }

    // -------------------------------------------------------------------------
    // Req 16.3 — Batch submission extension point (declared but deferred).
    //
    // A future optimization where the platform supports batch writes. The Outbox
    // may group multiple PlatformChanges into a single batch call. This is declared
    // here so the SPI contract is stable; implementations will be provided when the
    // Amazon Ads batch endpoint is integrated.
    // -------------------------------------------------------------------------

    /**
     * Submit a batch of changes to the live platform in a single call (Req 16.3).
     *
     * <p>This is a <strong>declared but deferred</strong> extension point. The default
     * implementation throws {@link UnsupportedOperationException} until a platform
     * connector provides a batch-capable implementation.
     *
     * <p>When implemented, the returned list MUST have the same size and order as
     * the input list, with each element representing the outcome of the corresponding
     * change.
     *
     * @param ctx     decrypted connection context (never logged)
     * @param changes the batch of changes to submit
     * @return a list of results, one per input change, in the same order
     * @throws UnsupportedOperationException if batch submission is not yet supported
     */
    default List<PlatformWriteResult> submitBatch(ConnectionContext ctx, List<PlatformChange> changes) {
        throw new UnsupportedOperationException(
                "Batch submission is not yet supported by connector for platform: " + platform());
    }
}
