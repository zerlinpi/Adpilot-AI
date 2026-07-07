package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable record of a single boundary limit change, capturing
 * before/after values and the acting user (Req 6.8, 12.4).
 *
 * <p>Created by {@link SafetyBoundaryValidator} when a boundary update is
 * validated and accepted.
 */
public record BoundaryChangeAuditEntry(
        SafetyBoundaryLimit limit,
        String scope,
        UUID scopeId,
        BigDecimal beforeValue,
        BigDecimal afterValue,
        UUID actorId,
        String actorName,
        Instant changedAt
) {

    public BoundaryChangeAuditEntry {
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(afterValue, "afterValue must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(changedAt, "changedAt must not be null");
    }

    /**
     * Create an audit entry for a new limit being set (no previous value).
     */
    public static BoundaryChangeAuditEntry ofCreate(SafetyBoundaryLimit limit, String scope,
                                                     UUID scopeId, BigDecimal newValue,
                                                     UUID actorId, String actorName) {
        return new BoundaryChangeAuditEntry(limit, scope, scopeId, null, newValue,
                actorId, actorName, Instant.now());
    }

    /**
     * Create an audit entry for a limit being updated.
     */
    public static BoundaryChangeAuditEntry ofUpdate(SafetyBoundaryLimit limit, String scope,
                                                     UUID scopeId, BigDecimal oldValue,
                                                     BigDecimal newValue, UUID actorId,
                                                     String actorName) {
        return new BoundaryChangeAuditEntry(limit, scope, scopeId, oldValue, newValue,
                actorId, actorName, Instant.now());
    }
}
