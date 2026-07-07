package com.adpilot.modules.advertising.support;

import java.util.List;

/**
 * The fixed, internal catalogue of advertising-module legacy goal-type / hosting-goal columns to
 * normalize to the canonical Optimization_Goal enum {@code profit_first | sales_growth | rank |
 * clearance} (Requirement 57.2).
 *
 * <p>The existing system stores the optimization objective in two places: the {@code goals.type}
 * goal-type column and the {@code campaigns.hosting_goal} hosting-goal column (the latter also
 * receives goal-type values through goal→campaign propagation). The migration rewrites the known
 * legacy values to their mapped enum and flags any unmappable value rather than guessing
 * (Requirement 57.3).</p>
 *
 * <p>All entries here are compile-time constants and contain no user input, so the
 * {@code (table, column)} pairs are safe to interpolate into migration SQL.</p>
 */
public final class OptimizationGoalColumnRegistry {

    private static final List<OptimizationGoalColumn> COLUMNS = List.of(
            new OptimizationGoalColumn("goals", "type"),
            new OptimizationGoalColumn("campaigns", "hosting_goal"));

    private OptimizationGoalColumnRegistry() {
        // Static catalogue — not instantiable.
    }

    /**
     * @return the immutable, ordered list of goal-type / hosting-goal columns to migrate. Migration
     *         processes the columns in this order.
     */
    public static List<OptimizationGoalColumn> columns() {
        return COLUMNS;
    }
}
