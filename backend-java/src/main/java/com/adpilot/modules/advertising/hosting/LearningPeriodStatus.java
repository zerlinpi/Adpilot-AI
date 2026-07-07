package com.adpilot.modules.advertising.hosting;

import java.time.LocalDate;

/**
 * Exposes the learning-period status for a campaign (Requirement 19.5).
 *
 * <p>Used by the dashboard to display days remaining and by the engines to decide
 * whether conservative limits apply.
 *
 * @param inLearningPeriod  whether the campaign is currently in its learning period
 * @param daysRemaining     days remaining in the learning period (0 when not in period)
 * @param startDate         the date the current learning period started (null when not in period)
 * @param totalDays         total learning period duration in days
 * @param personalityAtStart the personality active when the learning period started
 */
public record LearningPeriodStatus(
        boolean inLearningPeriod,
        int daysRemaining,
        LocalDate startDate,
        int totalDays,
        String personalityAtStart
) {
    /** A status representing a campaign that is NOT in a learning period. */
    public static LearningPeriodStatus notInPeriod() {
        return new LearningPeriodStatus(false, 0, null, 0, null);
    }

    /**
     * Create a learning-period status for a campaign currently in its period.
     *
     * @param daysRemaining     days left
     * @param startDate         when the period started
     * @param totalDays         total configured duration
     * @param personalityAtStart the personality recorded at period start
     * @return active learning-period status
     */
    public static LearningPeriodStatus active(int daysRemaining, LocalDate startDate,
                                              int totalDays, String personalityAtStart) {
        return new LearningPeriodStatus(true, Math.max(0, daysRemaining), startDate, totalDays, personalityAtStart);
    }
}
