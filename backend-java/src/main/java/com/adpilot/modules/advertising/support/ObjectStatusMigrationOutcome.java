package com.adpilot.modules.advertising.support;

import java.util.Objects;

/**
 * The pure result of classifying a single stored Object_Status value against the canonical
 * vocabulary and the fixed legacy mapping (Requirement 16.5, 16.6).
 *
 * <p>An outcome is exactly one of three {@link Kind kinds}:</p>
 * <ul>
 *   <li>{@link Kind#UNCHANGED} — the value is already a canonical Object_Status and is kept as-is;
 *       {@link #value()} is the canonical value to keep (possibly {@code null} when the input was
 *       {@code null}).</li>
 *   <li>{@link Kind#NORMALIZED} — the value is a known legacy value and is mapped to its canonical
 *       form via the fixed mapping (for example {@code active → enabled}); {@link #value()} is the
 *       canonical value to store.</li>
 *   <li>{@link Kind#FLAGGED} — the value is unknown/unrecognized and MUST be written to the migration
 *       exception list for manual review rather than auto-mapped to {@code enabled} or any other
 *       canonical value (Requirement 16.6); {@link #reason()} explains why and {@link #value()} is
 *       {@code null}.</li>
 * </ul>
 *
 * <p>This is an immutable value object with no persistence or framework concerns, so the
 * normalization classifier ({@link ObjectStatusVocabulary#normalize}) can be property-tested in
 * isolation (Property 38, task 14.16).</p>
 */
public final class ObjectStatusMigrationOutcome {

    /** The three mutually exclusive classifications of a migrated Object_Status value. */
    public enum Kind {
        /** Value is already a canonical Object_Status (or null) and is kept unchanged. */
        UNCHANGED,
        /** Value is a known legacy value and has been normalized to its canonical form. */
        NORMALIZED,
        /** Value is unknown/unrecognized and is flagged for manual review, never auto-mapped. */
        FLAGGED
    }

    private final Kind kind;
    private final String value;
    private final String reason;

    private ObjectStatusMigrationOutcome(Kind kind, String value, String reason) {
        this.kind = kind;
        this.value = value;
        this.reason = reason;
    }

    /** @return an {@link Kind#UNCHANGED} outcome carrying the (possibly {@code null}) canonical value to keep. */
    public static ObjectStatusMigrationOutcome unchanged(String value) {
        return new ObjectStatusMigrationOutcome(Kind.UNCHANGED, value, null);
    }

    /** @return a {@link Kind#NORMALIZED} outcome carrying the canonical value mapped from a legacy value. */
    public static ObjectStatusMigrationOutcome normalized(String value) {
        return new ObjectStatusMigrationOutcome(Kind.NORMALIZED, Objects.requireNonNull(value, "value"), null);
    }

    /** @return a {@link Kind#FLAGGED} outcome carrying the human-readable reason for manual review. */
    public static ObjectStatusMigrationOutcome flagged(String reason) {
        return new ObjectStatusMigrationOutcome(Kind.FLAGGED, null, Objects.requireNonNull(reason, "reason"));
    }

    public Kind kind() {
        return kind;
    }

    /**
     * @return the resulting canonical value for {@link Kind#UNCHANGED} (possibly {@code null}) and
     *         {@link Kind#NORMALIZED} outcomes; always {@code null} for {@link Kind#FLAGGED}.
     */
    public String value() {
        return value;
    }

    /** @return the manual-review reason for a {@link Kind#FLAGGED} outcome; {@code null} otherwise. */
    public String reason() {
        return reason;
    }

    /** @return {@code true} when this value is unknown and must be recorded in the exception list. */
    public boolean isFlagged() {
        return kind == Kind.FLAGGED;
    }

    /** @return {@code true} when this outcome changes the stored value (a {@link Kind#NORMALIZED} result). */
    public boolean isWrite() {
        return kind == Kind.NORMALIZED;
    }

    @Override
    public String toString() {
        return "ObjectStatusMigrationOutcome{kind=" + kind + ", value=" + value + ", reason=" + reason + '}';
    }
}
