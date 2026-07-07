package com.adpilot.modules.advertising.operation;

import java.util.HashMap;
import java.util.Map;

/**
 * The lifecycle state of a {@code Recommendation} (Requirement 10.1).
 *
 * <p>A Recommendation_Status is exactly one of these seven values. Five of them
 * ({@link #PENDING}, {@link #APPLYING}, {@link #EFFECTIVE}, {@link #FAILED},
 * {@link #LOCAL_ONLY}) are produced by mapping the resulting Operation's {@link SyncState} through
 * {@link RecommendationStatusMapper}. The remaining two ({@link #DISMISSED}, {@link #WATCHING}) are
 * set directly by an operator dismissing or watch-listing a Recommendation and never arise from a
 * Sync_State (Requirement 10.6).</p>
 *
 * <p>Each value carries the canonical lowercase machine value persisted and exchanged on the API
 * contract. Note the HYPHEN in {@code local-only} (per the Requirement glossary), which mirrors the
 * {@link SyncState#LOCAL_ONLY} spelling and is why the machine value is not derivable from
 * {@link Enum#name()} alone. The Frontend owns translating these machine values to display copy
 * (Requirement 10.7).</p>
 *
 * <p>Validates: Requirements 10.1, 10.6, 10.7.</p>
 */
public enum RecommendationStatus {

    /** Not yet applied, or re-actionable again after a cancelled/superseded Operation. */
    PENDING("pending"),

    /** The resulting Operation is in an Unsettled_State; the change is reconciling / in progress. */
    APPLYING("applying"),

    /** The resulting Operation is confirmed applied on the platform. */
    EFFECTIVE("effective"),

    /** The resulting Operation was rejected or errored; the Recommendation stays actionable for retry. */
    FAILED("failed"),

    /** The operator dismissed the Recommendation; no write Operation was created. */
    DISMISSED("dismissed"),

    /** The operator watch-listed the Recommendation; no write Operation was created. */
    WATCHING("watching"),

    /** Applied against a Store that is not write-capable; a terminal local state, never effective. */
    LOCAL_ONLY("local-only");

    private final String machineValue;

    RecommendationStatus(String machineValue) {
        this.machineValue = machineValue;
    }

    /**
     * @return the canonical lowercase machine value for this status (for example {@code local-only}),
     *         as persisted and returned on the API contract.
     */
    public String machineValue() {
        return machineValue;
    }

    private static final Map<String, RecommendationStatus> VALUE_TO_STATUS = new HashMap<>();

    static {
        for (RecommendationStatus status : values()) {
            VALUE_TO_STATUS.put(status.machineValue, status);
        }
    }

    /**
     * Resolves a Recommendation_Status from its canonical machine value.
     *
     * @param value the machine value (for example {@code applying}); may be {@code null}
     * @return the matching status, or {@code null} when {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is a non-null unknown machine value
     */
    public static RecommendationStatus fromMachineValue(String value) {
        if (value == null) {
            return null;
        }
        RecommendationStatus status = VALUE_TO_STATUS.get(value);
        if (status == null) {
            throw new IllegalArgumentException("Unknown Recommendation_Status machine value: " + value);
        }
        return status;
    }
}
