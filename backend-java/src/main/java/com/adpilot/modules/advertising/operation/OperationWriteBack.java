package com.adpilot.modules.advertising.operation;

import java.util.UUID;

/**
 * The generic Operation_Write_Back capability (Req 2, 9, 53.5).
 *
 * <p>This is the single, source-agnostic entry point that submits an already-created
 * {@code platform_mutation} Operation to the live platform. It generalizes the recommendation-only
 * {@code WriteBackService.apply(recommendationId)} path: any Operation whose
 * {@link OperationScope} is {@link OperationScope#PLATFORM_MUTATION} — a manual pause, a budget
 * change, a bid change, a keyword add, a negative add, an AI-hosting adjustment, or a
 * recommendation-sourced change — routes through {@link #applyOperation(UUID)} regardless of its
 * {@link OperationSource} (Req 2.3, 9.1).</p>
 *
 * <p>{@code applyOperation} resolves the Active_Store's {@code PlatformConnection} and the platform's
 * {@code PlatformWriteConnector}, builds a {@code PlatformChange} from the Operation's pending
 * (after) value, and submits it with the Operation's {@code submissionIdempotencyKey} so the platform
 * can dedupe the exact submission (Req 5.2/5.7, 53.5). It performs NO platform call inside a database
 * transaction (Req 6.3): the submission happens outside any transaction and the resulting Sync_State
 * change is routed through {@link OperationService#transition} so the state machine stays the sole
 * authority.</p>
 *
 * <p>A {@code local_configuration} Operation is NEVER submitted here — it has no platform side and is
 * rejected by {@code applyOperation} (Req 2.3).</p>
 *
 * <p>Validates: Requirements 2.3, 9.1, 53.5.</p>
 */
public interface OperationWriteBack {

    /**
     * Submit the given {@code platform_mutation} Operation to the live platform.
     *
     * <p>Routes ONLY a {@link OperationScope#PLATFORM_MUTATION} Operation. The Operation must be in a
     * submittable ({@link SyncState#PENDING}) state; an Operation that has already left {@code pending}
     * is treated as an idempotent no-op (its current result is returned) so a duplicate apply never
     * produces a duplicate platform submission. On acceptance the Operation advances to
     * {@code submitted} and the platform reference is stored (Req 55.7); on a platform rejection or a
     * transport error the Operation advances to {@code failed} with the failure reason recorded, while
     * the internal confirmed value is left unchanged.</p>
     *
     * @param operationId the Operation to submit; must identify a persisted {@code platform_mutation}
     *                    Operation
     * @return the Operation's resulting state after the submission attempt
     */
    OperationResult applyOperation(UUID operationId);
}
