package com.adpilot.modules.advertising.hosting;

/**
 * The execution mode governing whether AI decisions execute (Req 7.2).
 *
 * <p>Values are ordered from most conservative to most autonomous:
 * <ol>
 *   <li>{@link #OBSERVE_ONLY} — persist in {@code ai_decisions} only; no Operation created.</li>
 *   <li>{@link #RECOMMEND_ONLY} — persist in {@code ai_decisions} and surface in dashboard; no Operation.</li>
 *   <li>{@link #APPROVAL_REQUIRED} — create an {@code awaiting_approval} Operation immediately.</li>
 *   <li>{@link #AUTO_EXECUTE} — create a {@code pending} Operation (subject to high-risk gate).</li>
 * </ol>
 *
 * <p>New stores default to {@link #OBSERVE_ONLY} so they start in the safest state.
 *
 * <p>Validates: Requirements 7.2, 7.3.</p>
 */
public enum ExecutionMode {

    /** Persist the decision in ai_decisions only; create no Operation. */
    OBSERVE_ONLY("observe_only"),

    /** Persist and surface in dashboard; create no Operation and never auto-submit. */
    RECOMMEND_ONLY("recommend_only"),

    /** Immediately create an awaiting_approval Operation linked to the ai_decisions row. */
    APPROVAL_REQUIRED("approval_required"),

    /** Create a pending Operation directly, subject to high-risk gate and risk threshold. */
    AUTO_EXECUTE("auto_execute");

    /** The system-wide default for unconfigured stores (Req 7.2). */
    public static final ExecutionMode DEFAULT = OBSERVE_ONLY;

    private final String value;

    ExecutionMode(String value) {
        this.value = value;
    }

    /**
     * The canonical wire/storage value (lowercase, underscored).
     */
    public String value() {
        return value;
    }

    /**
     * Parse a raw string into an {@link ExecutionMode}, returning {@code null}
     * when the input is {@code null}, blank, or unrecognized.
     *
     * <p>This is intentionally lenient-by-rejection: an unrecognized value at
     * any level is treated as "not configured" and falls through to the next
     * level during resolution.
     *
     * @param raw the stored value (case-insensitive)
     * @return the matching mode, or {@code null} if not recognized
     */
    public static ExecutionMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase();
        for (ExecutionMode mode : values()) {
            if (mode.value.equals(trimmed)) {
                return mode;
            }
        }
        return null;
    }
}
