package com.adpilot.modules.advertising.support;

import java.util.Optional;

/**
 * Object_Status — the lifecycle state of a Campaign, Keyword, or Target, expressed with the single
 * canonical vocabulary {@code enabled | paused | archived} (Requirement 16.1).
 *
 * <p>Per Requirement 16 the Advertising_Module represents Object_Status as exactly one of three
 * canonical lowercase machine values: {@code enabled}, {@code paused}, and {@code archived}. The
 * Backend contract carries only these machine values and never a Chinese display string; the
 * Frontend owns the display translation (Requirement 16.4, 48.4).</p>
 *
 * <p>{@link #parse(String)} is intentionally LENIENT-by-rejection: it only recognizes the three
 * canonical values (case-insensitively, after trimming) and returns {@link Optional#empty()} for a
 * {@code null}, blank, or non-canonical value, so callers never have to guess. The legacy
 * normalization mapping (for example {@code active → enabled}) lives in {@link ObjectStatusVocabulary},
 * which is the single authority for migrating historical values.</p>
 *
 * <p>Validates: Requirements 16.1.</p>
 */
public enum ObjectStatus {

    ENABLED("enabled"),
    PAUSED("paused"),
    ARCHIVED("archived");

    private final String machineValue;

    ObjectStatus(String machineValue) {
        this.machineValue = machineValue;
    }

    /** The canonical lowercase machine value persisted and exchanged on the API contract (Req 16.1). */
    public String machineValue() {
        return machineValue;
    }

    /**
     * Parse a stored/raw status string into a canonical {@link ObjectStatus}.
     *
     * @param value a raw value (may be {@code null}, blank, or non-canonical)
     * @return the matching status, or {@link Optional#empty()} when the value is absent or is not one
     *     of the three canonical machine values
     */
    public static Optional<ObjectStatus> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (ObjectStatus status : values()) {
            if (status.machineValue.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
