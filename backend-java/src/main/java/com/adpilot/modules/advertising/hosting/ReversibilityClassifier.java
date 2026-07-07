package com.adpilot.modules.advertising.hosting;

import org.springframework.stereotype.Component;

/**
 * Classifies whether an operation is reversible based on its {@code changeType}.
 *
 * <p>Reversibility semantics (Requirements 5.11, 10.3):
 * <ul>
 *   <li><b>bid</b> (keyword bid change) → reversible: the original bid value can be restored.</li>
 *   <li><b>budget</b> (campaign daily budget change) → reversible: the original budget can be restored.</li>
 *   <li><b>state</b> (campaign state change, e.g., enable/pause) → reversible: the campaign can be re-enabled or re-paused.</li>
 *   <li><b>keyword</b> (keyword addition) → NOT reversible: a newly created keyword cannot be "uncreated".</li>
 *   <li><b>negative_keyword</b> (negative keyword addition) → NOT reversible: same reasoning.</li>
 * </ul>
 *
 * <p>The {@link com.adpilot.modules.advertising.hosting.OptimizationCoordinatorImpl}
 * and other Operation-creation flows use this classifier to set the {@code reversible}
 * flag on {@link com.adpilot.modules.advertising.operation.CreateOperationCommand}.
 *
 * <p>The {@link com.adpilot.modules.advertising.operation.OperationServiceImpl}'s
 * rollback logic (RollbackService, future task 19.1) will consult this flag to
 * determine which operations can be undone.
 *
 * <p>Validates: Requirements 5.11, 10.3.</p>
 */
@Component
public class ReversibilityClassifier {

    /** Change type for keyword bid modifications. */
    public static final String CHANGE_TYPE_BID = "bid";

    /** Change type for campaign daily budget modifications. */
    public static final String CHANGE_TYPE_BUDGET = "budget";

    /** Change type for campaign state changes (enable/pause). */
    public static final String CHANGE_TYPE_STATE = "state";

    /** Change type for positive keyword additions. */
    public static final String CHANGE_TYPE_KEYWORD = "keyword";

    /** Change type for negative keyword additions. */
    public static final String CHANGE_TYPE_NEGATIVE_KEYWORD = "negative_keyword";

    /**
     * Determines whether the given operation change type is reversible.
     *
     * @param changeType the wire change type of the operation (e.g., "bid", "budget",
     *                   "state", "keyword", "negative_keyword")
     * @return {@code true} if the operation can be rolled back (bid, budget, state changes);
     *         {@code false} if the operation is non-reversible (keyword/negative-keyword additions);
     *         {@code false} for unknown or null change types (fail-safe: non-reversible)
     */
    public boolean isReversible(String changeType) {
        return classify(changeType);
    }

    /**
     * Static classification method for use without Spring injection.
     *
     * <p>This enables callers that build {@link com.adpilot.modules.advertising.operation.CreateOperationCommand}
     * from {@link CandidateDecision} objects to classify reversibility without requiring a
     * bean reference.</p>
     *
     * @param changeType the wire change type
     * @return {@code true} for reversible changes, {@code false} otherwise
     */
    public static boolean classify(String changeType) {
        if (changeType == null || changeType.isBlank()) {
            return false;
        }
        return switch (changeType) {
            case CHANGE_TYPE_BID -> true;
            case CHANGE_TYPE_BUDGET -> true;
            case CHANGE_TYPE_STATE -> true;
            case CHANGE_TYPE_KEYWORD -> false;
            case CHANGE_TYPE_NEGATIVE_KEYWORD -> false;
            default -> false;
        };
    }
}
