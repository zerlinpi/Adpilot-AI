package com.adpilot.modules.apisync.model;

import java.util.UUID;

/**
 * Outcome of an idempotent upsert (Req 1.2). Reports whether the internal
 * record was newly created or an existing one was updated, along with the
 * internal record's identifier.
 *
 * @param outcome    whether the record was created or updated
 * @param internalId identifier of the affected internal record
 */
public record UpsertResult(Outcome outcome, UUID internalId) {

    public enum Outcome {
        CREATED,
        UPDATED
    }

    public static UpsertResult created(UUID internalId) {
        return new UpsertResult(Outcome.CREATED, internalId);
    }

    public static UpsertResult updated(UUID internalId) {
        return new UpsertResult(Outcome.UPDATED, internalId);
    }

    public boolean isCreated() {
        return outcome == Outcome.CREATED;
    }

    public boolean isUpdated() {
        return outcome == Outcome.UPDATED;
    }
}
