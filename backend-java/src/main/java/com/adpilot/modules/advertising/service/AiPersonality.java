package com.adpilot.modules.advertising.service;

import java.util.Optional;

/**
 * AI_Personality — the enforceable optimizer policy profile that governs how aggressively and how
 * riskily the AI optimizer acts to reach the Optimization_Goal.
 *
 * <p>Per Requirement 49.1 the Advertising_Module represents AI_Personality as exactly one of three
 * canonical lowercase machine values: {@code conservative} (display 常规型), {@code balanced} (display
 * 平衡型), and {@code aggressive} (display 激进型). The Frontend owns the display translation; the
 * backend contract never carries the Chinese display copy and never uses the retired alternate name
 * 稳健型.</p>
 *
 * <p>{@link #parse(String)} is intentionally LENIENT-by-rejection: it only recognizes the three
 * canonical values (case-insensitively, after trimming) and returns {@link Optional#empty()} for a
 * {@code null}, blank, or unrecognized value. This lets {@link PersonalityResolver} treat an unset or
 * non-canonical value at any precedence level as "not set" and fall through to the next level rather
 * than guessing — the resolution rule of Requirement 49.2/49.3.</p>
 *
 * <p>Validates: Requirements 49.1, 49.2, 49.3.</p>
 */
public enum AiPersonality {

    CONSERVATIVE("conservative"),
    BALANCED("balanced"),
    AGGRESSIVE("aggressive");

    /** The system fallback used when no level in the precedence chain resolves a value (Req 49.2/49.3). */
    public static final AiPersonality SYSTEM_FALLBACK = BALANCED;

    private final String machineValue;

    AiPersonality(String machineValue) {
        this.machineValue = machineValue;
    }

    /** The canonical lowercase machine value persisted and exchanged on the API contract (Req 49.1). */
    public String machineValue() {
        return machineValue;
    }

    /**
     * Parse a stored/raw personality string into a canonical {@link AiPersonality}.
     *
     * @param value a raw value (may be {@code null}, blank, or non-canonical)
     * @return the matching personality, or {@link Optional#empty()} when the value is absent or is not
     *     one of the three canonical machine values
     */
    public static Optional<AiPersonality> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (AiPersonality personality : values()) {
            if (personality.machineValue.equals(normalized)) {
                return Optional.of(personality);
            }
        }
        return Optional.empty();
    }
}
