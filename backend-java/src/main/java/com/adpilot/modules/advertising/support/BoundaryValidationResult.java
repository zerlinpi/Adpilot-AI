package com.adpilot.modules.advertising.support;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a {@link SafetyBoundaryValidator} validation check.
 *
 * <p>Contains:
 * <ul>
 *   <li>{@link #valid()} — whether the proposed boundary set passes all checks</li>
 *   <li>{@link #violations()} — per-limit detail on which constraints were violated,
 *       including the constraining higher-level boundary when an only-tighten check fails
 *       (Req 6.3) and any cross-field constraint failures (Req 6.8)</li>
 * </ul>
 *
 * <p>This is a pure value object with no Spring dependencies.
 */
public record BoundaryValidationResult(
        boolean valid,
        List<ConstraintViolation> violations
) {

    public BoundaryValidationResult {
        violations = violations == null ? List.of() : Collections.unmodifiableList(violations);
    }

    /** A successful validation with no violations. */
    public static BoundaryValidationResult success() {
        return new BoundaryValidationResult(true, List.of());
    }

    /** A failed validation with the given violations. */
    public static BoundaryValidationResult failure(List<ConstraintViolation> violations) {
        Objects.requireNonNull(violations, "violations must not be null");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("failure must have at least one violation");
        }
        return new BoundaryValidationResult(false, violations);
    }

    /**
     * Describes a single constraint violation found during boundary validation.
     *
     * @param type            the type of violation (ONLY_TIGHTEN or CROSS_FIELD)
     * @param limit           the limit that violated the constraint (for ONLY_TIGHTEN),
     *                        or the "lower" limit in the cross-field pair
     * @param proposedValue   the value the user tried to set
     * @param constrainingValue the higher-level value that constrains this limit (ONLY_TIGHTEN),
     *                          or the conflicting field value (CROSS_FIELD)
     * @param constrainingLevel the level that provided the constraining boundary (ONLY_TIGHTEN only, null for CROSS_FIELD)
     * @param relatedLimit    for CROSS_FIELD: the other limit involved in the constraint; null for ONLY_TIGHTEN
     * @param message         human-readable description
     */
    public record ConstraintViolation(
            ViolationType type,
            SafetyBoundaryLimit limit,
            java.math.BigDecimal proposedValue,
            java.math.BigDecimal constrainingValue,
            SafetyBoundaryLevel constrainingLevel,
            SafetyBoundaryLimit relatedLimit,
            String message
    ) {
        public ConstraintViolation {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(limit, "limit must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    /**
     * The type of boundary validation violation.
     */
    public enum ViolationType {
        /** The proposed value is less restrictive than the resolved higher-level boundary (Req 6.3). */
        ONLY_TIGHTEN,
        /** A cross-field ordering constraint is violated (Req 6.8). */
        CROSS_FIELD
    }
}
