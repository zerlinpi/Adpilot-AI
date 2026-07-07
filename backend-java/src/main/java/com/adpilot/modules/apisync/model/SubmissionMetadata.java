package com.adpilot.modules.apisync.model;

/**
 * Metadata persisted on an Operation at acceptance time, carrying the identifiers
 * and expected state needed for read-after-write verification (Req 16.6, 20.2).
 *
 * <p>When the VerificationWorker invokes
 * {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector#verify},
 * it passes this metadata so the connector knows which entity/field to re-read
 * and what the expected value should be.
 *
 * @param amazonRequestId    the Amazon request ID from the accepted submission
 * @param externalEntityId   the Amazon external entity ID affected by the change
 * @param entityType         the entity type (e.g. "keyword", "campaign")
 * @param field              the field that was changed (e.g. "bid", "dailyBudget", "state")
 * @param expectedAfterValue the value the Operation expects to see after the change is applied
 */
public record SubmissionMetadata(
        String amazonRequestId,
        String externalEntityId,
        String entityType,
        String field,
        String expectedAfterValue) {
}
