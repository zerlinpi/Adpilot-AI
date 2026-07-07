package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The pure result of classifying a single historical ACoS value against its column's
 * {@link AcosColumnScale known source semantics} (Requirement 17.5, 17.6).
 *
 * <p>An outcome is exactly one of three {@link Kind kinds}:</p>
 * <ul>
 *   <li>{@link Kind#UNCHANGED} — the value is already a canonical decimal ratio (or is {@code null}
 *       / zero) and is kept as-is; {@link #value()} is the ratio to keep (possibly {@code null}).</li>
 *   <li>{@link Kind#CONVERTED} — the value was percentage-scaled and is converted to a decimal ratio;
 *       {@link #value()} is the converted ratio.</li>
 *   <li>{@link Kind#AMBIGUOUS} — the value cannot be safely converted under the column's semantics and
 *       MUST be flagged for manual review rather than guessed; {@link #reason()} explains why and
 *       {@link #value()} is {@code null}.</li>
 * </ul>
 *
 * <p>This is an immutable value object with no persistence or framework concerns, so the migration
 * classifier ({@link AcosScale#migrate}) can be property-tested in isolation (Property 40, task 14.18).</p>
 */
public final class AcosMigrationOutcome {

    /** The three mutually exclusive classifications of a migrated ACoS value. */
    public enum Kind {
        /** Value is already a canonical decimal ratio (or null/zero) and is kept unchanged. */
        UNCHANGED,
        /** Value was percentage-scaled and has been converted to a decimal ratio. */
        CONVERTED,
        /** Value is ambiguous/unknown and is flagged for manual review, never guessed. */
        AMBIGUOUS
    }

    private final Kind kind;
    private final BigDecimal value;
    private final String reason;

    private AcosMigrationOutcome(Kind kind, BigDecimal value, String reason) {
        this.kind = kind;
        this.value = value;
        this.reason = reason;
    }

    /** @return an {@link Kind#UNCHANGED} outcome carrying the (possibly {@code null}) ratio to keep. */
    public static AcosMigrationOutcome unchanged(BigDecimal value) {
        return new AcosMigrationOutcome(Kind.UNCHANGED, value, null);
    }

    /** @return a {@link Kind#CONVERTED} outcome carrying the converted decimal ratio. */
    public static AcosMigrationOutcome converted(BigDecimal value) {
        return new AcosMigrationOutcome(Kind.CONVERTED, Objects.requireNonNull(value, "value"), null);
    }

    /** @return an {@link Kind#AMBIGUOUS} outcome carrying the human-readable reason for manual review. */
    public static AcosMigrationOutcome ambiguous(String reason) {
        return new AcosMigrationOutcome(Kind.AMBIGUOUS, null, Objects.requireNonNull(reason, "reason"));
    }

    public Kind kind() {
        return kind;
    }

    /**
     * @return the resulting decimal ratio for {@link Kind#UNCHANGED} (possibly {@code null}) and
     *         {@link Kind#CONVERTED} outcomes; always {@code null} for {@link Kind#AMBIGUOUS}.
     */
    public BigDecimal value() {
        return value;
    }

    /** @return the manual-review reason for an {@link Kind#AMBIGUOUS} outcome; {@code null} otherwise. */
    public String reason() {
        return reason;
    }

    public boolean isAmbiguous() {
        return kind == Kind.AMBIGUOUS;
    }

    /** @return {@code true} when this outcome changes the stored value (a {@link Kind#CONVERTED} result). */
    public boolean isWrite() {
        return kind == Kind.CONVERTED;
    }

    @Override
    public String toString() {
        return "AcosMigrationOutcome{kind=" + kind + ", value=" + value + ", reason=" + reason + '}';
    }
}
