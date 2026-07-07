package com.adpilot.modules.advertising.support;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single, authoritative encoding of the Object_Status vocabulary convention (Requirement 16):
 * Campaign, Keyword, and Target lifecycle state is represented with exactly the canonical vocabulary
 * {@code enabled | paused | archived}, and historical values are normalized on read/migration using
 * the fixed legacy mapping {@code active → enabled}, {@code paused → paused}, {@code archived →
 * archived}. An unknown or unrecognized value is <strong>never auto-mapped</strong> to a canonical
 * value; it is flagged for manual review instead (Requirement 16.6).
 *
 * <p>This is a <strong>pure</strong>, stateless utility: every method is side-effect free and free of
 * persistence, JSON, or framework concerns, so it can be unit- and property-tested in isolation
 * (Property 38, task 14.16). It is the one place that defines:</p>
 * <ul>
 *   <li>the canonical vocabulary ({@link #canonicalValues()}, {@link #isCanonical}),</li>
 *   <li>the fixed legacy-to-canonical mapping ({@link #LEGACY_MAPPING}), and</li>
 *   <li>the migration classifier that normalizes a known value and flags an unknown one
 *       ({@link #normalize}).</li>
 * </ul>
 *
 * <p>Keeping the convention in one pure place is what lets the recommendation engine, AI hosting, and
 * status-restore logic all interpret Object_Status the same way (Requirement 16.3) while the Backend
 * normalizes legacy persisted values on migration (Requirement 16.5, 16.7).</p>
 *
 * <p>Validates: Requirements 16.1, 16.5, 16.6, 16.7.</p>
 */
public final class ObjectStatusVocabulary {

    /**
     * The fixed legacy-to-canonical mapping (Requirement 16.5). Keys are matched case-insensitively
     * after trimming; only these legacy values are recognized. Note that {@code paused} and
     * {@code archived} map to themselves (they are both legacy and canonical), and {@code active} is
     * the only legacy value whose canonical form differs from its stored form.
     */
    public static final Map<String, ObjectStatus> LEGACY_MAPPING = Map.of(
            "active", ObjectStatus.ENABLED,
            "enabled", ObjectStatus.ENABLED,
            "paused", ObjectStatus.PAUSED,
            "archived", ObjectStatus.ARCHIVED);

    private static final Set<String> CANONICAL_VALUES =
            Set.of(ObjectStatus.ENABLED.machineValue(),
                    ObjectStatus.PAUSED.machineValue(),
                    ObjectStatus.ARCHIVED.machineValue());

    private ObjectStatusVocabulary() {
        // Utility class — not instantiable.
    }

    /** @return the immutable set of canonical Object_Status machine values {@code enabled|paused|archived}. */
    public static Set<String> canonicalValues() {
        return CANONICAL_VALUES;
    }

    /**
     * Report whether a value is already a canonical Object_Status machine value (Requirement 16.1).
     * The comparison is exact (canonical values are lowercase), so a non-lowercase or padded variant
     * is not considered canonical and would be normalized.
     *
     * @param value the raw value (may be {@code null})
     * @return {@code true} iff {@code value} is exactly one of {@code enabled}, {@code paused}, or
     *         {@code archived}
     */
    public static boolean isCanonical(String value) {
        return value != null && CANONICAL_VALUES.contains(value);
    }

    /**
     * Classify a single stored Object_Status value against the canonical vocabulary and the fixed
     * legacy mapping (Requirement 16.5, 16.6), producing the migration {@link ObjectStatusMigrationOutcome
     * outcome}. This never guesses: a value is normalized only when it is a recognized canonical or
     * legacy value, and any other value is flagged for manual review rather than mapped to a canonical
     * value (Requirement 16.6).
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>A {@code null} value migrates {@link ObjectStatusMigrationOutcome.Kind#UNCHANGED unchanged}
     *       (nothing to normalize).</li>
     *   <li>An already-canonical value stored in its exact canonical form is kept
     *       {@link ObjectStatusMigrationOutcome.Kind#UNCHANGED unchanged}.</li>
     *   <li>A recognized legacy or non-canonically-cased value (matched case-insensitively after
     *       trimming) is {@link ObjectStatusMigrationOutcome.Kind#NORMALIZED normalized} to its
     *       canonical form via {@link #LEGACY_MAPPING}.</li>
     *   <li>Any other value — including a blank string — is
     *       {@link ObjectStatusMigrationOutcome.Kind#FLAGGED flagged} and recorded for manual review;
     *       it is never auto-mapped.</li>
     * </ul>
     *
     * @param raw the historical stored value (may be {@code null})
     * @return the migration outcome, never {@code null}
     */
    public static ObjectStatusMigrationOutcome normalize(String raw) {
        if (raw == null) {
            return ObjectStatusMigrationOutcome.unchanged(null);
        }
        // An exact canonical value is kept as-is (no rewrite needed).
        if (isCanonical(raw)) {
            return ObjectStatusMigrationOutcome.unchanged(raw);
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        ObjectStatus mapped = LEGACY_MAPPING.get(key);
        if (mapped != null) {
            return ObjectStatusMigrationOutcome.normalized(mapped.machineValue());
        }
        return ObjectStatusMigrationOutcome.flagged(
                "unknown Object_Status value '" + raw + "'; not in the canonical vocabulary "
                        + CANONICAL_VALUES + " or the legacy mapping " + LEGACY_MAPPING.keySet()
                        + "; flagged for manual review rather than auto-mapped");
    }

    /**
     * Convenience accessor returning the canonical value for a known (canonical or legacy) input, or
     * {@link Optional#empty()} when the value is unknown and would be flagged. This never guesses and
     * is the read-time counterpart to {@link #normalize}.
     *
     * @param raw the raw value (may be {@code null})
     * @return the canonical machine value, or empty when absent/unknown
     */
    public static Optional<String> toCanonical(String raw) {
        ObjectStatusMigrationOutcome outcome = normalize(raw);
        return outcome.isFlagged() ? Optional.empty() : Optional.ofNullable(outcome.value());
    }
}
