package com.adpilot.modules.advertising.hosting;

/**
 * The mode controlling how negative-keyword candidates proposed by the V3 Keyword Engine
 * are routed through the decision pipeline (Requirements 5.8, 5.9).
 *
 * <ul>
 *   <li><b>{@link #SUGGEST}</b> — negative candidates always require approval; they are
 *       NEVER auto-executed regardless of risk score or execution mode. Under
 *       {@code observe_only}/{@code recommend_only}, no Operation is created (only
 *       {@code ai_decisions} row). Under {@code approval_required}/{@code auto_execute},
 *       the Operation is created in {@code awaiting_approval} state.</li>
 *   <li><b>{@link #AUTO}</b> — negative candidates MAY auto-execute when
 *       {@code ExecutionMode == auto_execute} AND the store explicitly opts in.
 *       Otherwise, normal execution-mode routing rules apply (including the high-risk
 *       gate which classifies negative-keyword additions as high-risk per Req 7.5).</li>
 * </ul>
 *
 * <p>Validates: Requirements 5.8, 5.9.</p>
 */
public enum NegativeKeywordMode {

    /**
     * Negative candidates always require human approval within executing modes.
     * They are never auto-executed regardless of risk score.
     */
    SUGGEST("suggest"),

    /**
     * Negative candidates may auto-execute when execution mode is {@code auto_execute}
     * and the store explicitly opts in to negative auto-execution.
     */
    AUTO("auto");

    private final String value;

    NegativeKeywordMode(String value) {
        this.value = value;
    }

    /**
     * The canonical wire/storage value (lowercase).
     */
    public String value() {
        return value;
    }

    /**
     * Parse a raw string into a {@link NegativeKeywordMode}.
     *
     * @param raw the stored value (case-insensitive)
     * @return the matching mode, or {@code null} if not recognized
     */
    public static NegativeKeywordMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase();
        for (NegativeKeywordMode mode : values()) {
            if (mode.value.equals(trimmed)) {
                return mode;
            }
        }
        return null;
    }
}
